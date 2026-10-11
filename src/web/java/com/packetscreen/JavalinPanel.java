package com.packetscreen;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.SameSite;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class JavalinPanel implements WebServer {

    private static final String COOKIE = "ps_session";
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");
    private static final String SECURITY_POLICY = "default-src 'none'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' blob:; media-src 'self' blob:; connect-src 'self'; font-src 'self'; "
            + "frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    private static final Logger JETTY_LOGGER = quiet(Server.class.getPackage().getName().replaceAll("\\.server$", ""));
    private static final Logger JAVALIN_LOGGER = quiet(Javalin.class.getPackage().getName());

    private final PanelBackend backend;
    private final byte[] page;
    private final byte[] script;
    private final byte[] style;
    private final byte[] icon;
    private Javalin app;

    JavalinPanel(PanelBackend backend) throws IOException {
        this.backend = backend;
        this.page = resource("panel/index.html");
        this.script = resource("panel/app.js");
        this.style = resource("panel/app.css");
        this.icon = resource("panel/icon.svg");
    }

    @Override
    public void start() {
        Settings settings = backend.settings();
        QueuedThreadPool pool = new QueuedThreadPool(16, 2, 60000);
        pool.setName("PacketScreen-Web");
        pool.setDaemon(true);
        app = Javalin.create(config -> {
            config.startup.showJavalinBanner = false;
            config.startup.showOldJavalinVersionWarning = false;
            config.startup.startupWatcherEnabled = false;
            config.http.maxRequestSize = 16384;
            config.jetty.threadPool = pool;
            config.jetty.modifyHttpConfiguration(http -> {
                http.setSendServerVersion(false);
                http.setSendXPoweredBy(false);
            });
            config.routes.before(this::headers);
            config.routes.get("/", ctx -> asset(ctx, page, "text/html; charset=utf-8"));
            config.routes.get("/app.js", ctx -> asset(ctx, script, "text/javascript; charset=utf-8"));
            config.routes.get("/app.css", ctx -> asset(ctx, style, "text/css; charset=utf-8"));
            config.routes.get("/icon.svg", ctx -> asset(ctx, icon, "image/svg+xml"));
            config.routes.get("/api/strings", this::strings);
            config.routes.post("/api/login", this::login);
            config.routes.post("/api/logout", this::logout);
            config.routes.get("/api/state", this::state);
            config.routes.post("/api/upload", this::upload);
            config.routes.get("/api/uploads/{id}", this::preview);
            config.routes.delete("/api/uploads/{id}", this::deleteUpload);
            config.routes.post("/api/screens", this::createScreen);
            config.routes.post("/api/screens/{name}/source", this::applySource);
            config.routes.delete("/api/screens/{name}", this::removeScreen);
            config.routes.exception(Exception.class, (error, ctx) -> {
                backend.debug("Web panel request failed: " + ctx.method() + " " + ctx.path(), error);
                fail(ctx, 500, backend.text("panel.error-generic"));
            });
            config.routes.error(404, ctx -> {
                if (ctx.result() == null) {
                    fail(ctx, 404, backend.text("panel.not-found"));
                }
            });
        });
        app.start(settings.webBindAddress, settings.webPort);
    }

    @Override
    public void stop() {
        Javalin current = app;
        app = null;
        if (current != null) {
            current.stop();
        }
    }

    private void headers(Context ctx) {
        ctx.header("X-Content-Type-Options", "nosniff");
        ctx.header("X-Frame-Options", "DENY");
        ctx.header("Referrer-Policy", "no-referrer");
        ctx.header("Cache-Control", "no-store");
        ctx.header("Content-Security-Policy", SECURITY_POLICY);
        ctx.header("Cross-Origin-Opener-Policy", "same-origin");
        ctx.header("Cross-Origin-Resource-Policy", "same-origin");
        ctx.header("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
    }

    private static void asset(Context ctx, byte[] content, String type) {
        ctx.contentType(type).result(content);
    }

    private void strings(Context ctx) {
        json(ctx, 200, backend.texts());
    }

    private void login(Context ctx) {
        Settings settings = backend.settings();
        if (!backend.access().allowAttempt(address(ctx), settings.webLoginAttempts)) {
            fail(ctx, 429, backend.text("panel.too-many-attempts"));
            return;
        }
        Map<String, String> body = body(ctx);
        WebAccess.Session session = backend.access().redeem(body.get("token"), settings.webSessionMinutes * 60000L);
        if (session == null) {
            fail(ctx, 401, backend.text("panel.invalid-link"));
            return;
        }
        int seconds = (int) Math.max(1, (session.expires - System.currentTimeMillis()) / 1000);
        ctx.cookie(new Cookie(COOKIE, session.id, "/", seconds, secure(), true, null, SameSite.STRICT));
        backend.audit(session, "opened the web panel from " + address(ctx));
        json(ctx, 200, Json.object("ok", true));
    }

    private void logout(Context ctx) {
        WebAccess.Session session = session(ctx, true);
        if (session == null) {
            return;
        }
        backend.access().end(session);
        ctx.cookie(new Cookie(COOKIE, "", "/", 0, secure(), true, null, SameSite.STRICT));
        json(ctx, 200, Json.object("ok", true));
    }

    private void state(Context ctx) {
        WebAccess.Session session = session(ctx, false);
        if (session != null) {
            json(ctx, 200, backend.state(session));
        }
    }

    private void upload(Context ctx) throws IOException {
        WebAccess.Session session = session(ctx, true);
        if (session == null) {
            return;
        }
        long length = ctx.req().getContentLengthLong();
        if (length <= 0) {
            fail(ctx, 411, backend.text("panel.error-length"));
            return;
        }
        String header = ctx.header("X-File-Name");
        String name = header == null ? "media" : URLDecoder.decode(header, StandardCharsets.UTF_8);
        if (name.length() > 255) {
            name = name.substring(0, 255);
        }
        if (session.uploads.incrementAndGet() > 2) {
            session.uploads.decrementAndGet();
            fail(ctx, 429, backend.text("panel.too-many-uploads"));
            return;
        }
        try (InputStream body = ctx.bodyInputStream()) {
            UploadStore.Upload upload = backend.store(session, name, body, length);
            json(ctx, 200, Json.object("id", upload.id, "size", upload.size));
        } catch (MediaException error) {
            fail(ctx, status(error), backend.reason(error));
        } finally {
            session.uploads.decrementAndGet();
        }
    }

    private void preview(Context ctx) throws IOException {
        WebAccess.Session session = session(ctx, false);
        if (session == null) {
            return;
        }
        String id = ctx.pathParam("id");
        File file = backend.uploads().file(id);
        if (file == null) {
            fail(ctx, 404, backend.text("panel.not-found"));
            return;
        }
        long length = file.length();
        long start = 0;
        long end = length - 1;
        String range = ctx.header("Range");
        if (range != null) {
            Matcher matcher = RANGE.matcher(range.trim());
            boolean valid = matcher.matches() && !(matcher.group(1).isEmpty() && matcher.group(2).isEmpty());
            if (valid) {
                try {
                    if (matcher.group(1).isEmpty()) {
                        start = Math.max(0, length - Long.parseLong(matcher.group(2)));
                    } else {
                        start = Long.parseLong(matcher.group(1));
                        if (!matcher.group(2).isEmpty()) {
                            end = Math.min(end, Long.parseLong(matcher.group(2)));
                        }
                    }
                } catch (NumberFormatException e) {
                    valid = false;
                }
            }
            if (!valid || start > end || start >= length) {
                ctx.status(416).header("Content-Range", "bytes */" + length);
                return;
            }
            ctx.status(206).header("Content-Range", "bytes " + start + "-" + end + "/" + length);
        }
        ctx.header("Accept-Ranges", "bytes");
        ctx.header("Content-Disposition", "inline");
        ctx.header("Content-Type", UploadStore.contentType(id));
        ctx.header("Content-Length", String.valueOf(end - start + 1));
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            input.seek(start);
            OutputStream output = ctx.res().getOutputStream();
            byte[] buffer = new byte[65536];
            long remaining = end - start + 1;
            while (remaining > 0) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                output.write(buffer, 0, read);
                remaining -= read;
            }
            output.flush();
        }
    }

    private void deleteUpload(Context ctx) {
        WebAccess.Session session = session(ctx, true);
        if (session == null) {
            return;
        }
        try {
            backend.deleteUpload(session, ctx.pathParam("id"));
            json(ctx, 200, Json.object("ok", true));
        } catch (ScreenException error) {
            fail(ctx, error.key.equals("upload-not-found") ? 404 : 409, backend.text(error.key, error.placeholders));
        }
    }

    private void createScreen(Context ctx) {
        WebAccess.Session session = session(ctx, true);
        if (session == null) {
            return;
        }
        try {
            backend.create(session, body(ctx));
            json(ctx, 200, Json.object("ok", true));
        } catch (ScreenException error) {
            fail(ctx, error.key.equals("panel.error-permission") ? 403 : 400, backend.text(error.key, error.placeholders));
        }
    }

    private void applySource(Context ctx) {
        WebAccess.Session session = session(ctx, true);
        if (session == null) {
            return;
        }
        try {
            String upload = body(ctx).get("upload");
            backend.apply(session, ctx.pathParam("name"), upload == null ? "" : upload);
            json(ctx, 200, Json.object("ok", true));
        } catch (ScreenException error) {
            fail(ctx, error.key.equals("panel.error-permission") ? 403 : 400, backend.text(error.key, error.placeholders));
        }
    }

    private void removeScreen(Context ctx) {
        WebAccess.Session session = session(ctx, true);
        if (session == null) {
            return;
        }
        try {
            backend.remove(session, ctx.pathParam("name"));
            json(ctx, 200, Json.object("ok", true));
        } catch (ScreenException error) {
            fail(ctx, error.key.equals("panel.error-permission") ? 403 : 404, backend.text(error.key, error.placeholders));
        }
    }

    private WebAccess.Session session(Context ctx, boolean changes) {
        WebAccess.Session session = backend.access().session(ctx.cookie(COOKIE));
        if (session == null) {
            fail(ctx, 401, backend.text("panel.session-ended"));
            return null;
        }
        if (changes) {
            if (!sameOrigin(ctx) || !WebAccess.safeEquals(session.csrf, ctx.header("X-CSRF-Token"))) {
                fail(ctx, 403, backend.text("panel.error-generic"));
                return null;
            }
        }
        return session;
    }

    private boolean sameOrigin(Context ctx) {
        String origin = ctx.header("Origin");
        if (origin == null) {
            return true;
        }
        String host = ctx.header("Host");
        if (host != null && (origin.equals("http://" + host) || origin.equals("https://" + host))) {
            return true;
        }
        String publicUrl = backend.settings().webPublicUrl;
        if (publicUrl.isEmpty()) {
            return false;
        }
        int path = publicUrl.indexOf('/', publicUrl.indexOf("://") + 3);
        return origin.equalsIgnoreCase(path < 0 ? publicUrl : publicUrl.substring(0, path));
    }

    private Map<String, String> body(Context ctx) {
        try {
            return Json.parseObject(ctx.body());
        } catch (IllegalArgumentException error) {
            return Collections.emptyMap();
        }
    }

    private String address(Context ctx) {
        if (backend.settings().webTrustProxy) {
            String forwarded = ctx.header("X-Forwarded-For");
            if (forwarded != null && !forwarded.isEmpty()) {
                String[] hops = forwarded.split(",");
                return hops[hops.length - 1].trim();
            }
        }
        return ctx.req().getRemoteAddr();
    }

    private boolean secure() {
        return backend.settings().webPublicUrl.startsWith("https://");
    }

    private static int status(MediaException error) {
        switch (error.reason) {
            case TOO_LARGE:
                return 413;
            case STORAGE_FULL:
                return 507;
            case UNSUPPORTED_FORMAT:
            case WEB_PAGE:
                return 415;
            default:
                return 400;
        }
    }

    private static void json(Context ctx, int status, Object value) {
        ctx.status(status).contentType("application/json; charset=utf-8").result(Json.write(value));
    }

    private static void fail(Context ctx, int status, String message) {
        json(ctx, status, Json.object("error", message));
    }

    private static Logger quiet(String name) {
        Logger logger = Logger.getLogger(name);
        logger.setLevel(Level.WARNING);
        return logger;
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream in = JavalinPanel.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Missing resource " + path);
            }
            return in.readAllBytes();
        }
    }
}
