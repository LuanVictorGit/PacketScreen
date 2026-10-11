package com.packetscreen;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavalinPanelTest {

    private static final HttpClient CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private static TestBackend backend;
    private static JavalinPanel panel;
    private static String base;

    static final class TestBackend implements PanelBackend {
        final Settings settings;
        final WebAccess access = new WebAccess();
        final UploadStore uploads;
        final List<String> actions = Collections.synchronizedList(new ArrayList<>());

        TestBackend(Settings settings, File directory) {
            this.settings = settings;
            this.uploads = new UploadStore(directory);
        }

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public WebAccess access() {
            return access;
        }

        @Override
        public UploadStore uploads() {
            return uploads;
        }

        @Override
        public void audit(WebAccess.Session session, String action) {
            actions.add(action);
        }

        @Override
        public void debug(String message, Throwable error) {
        }

        @Override
        public Map<String, String> texts() {
            return Collections.singletonMap("title", "PacketScreen");
        }

        @Override
        public String text(String key, Object... placeholders) {
            return key;
        }

        @Override
        public String reason(MediaException error) {
            return error.reason.key;
        }

        @Override
        public Map<String, Object> state(WebAccess.Session session) {
            return Json.object("user", session.grant.user, "csrf", session.csrf);
        }

        @Override
        public UploadStore.Upload store(WebAccess.Session session, String name, InputStream body, long length) throws MediaException {
            return uploads.store(name, body, length, 100_000, 100_000, 1_000_000, (file, kind) -> {
            });
        }

        @Override
        public void deleteUpload(WebAccess.Session session, String id) throws ScreenException {
            if (!uploads.delete(id)) {
                throw new ScreenException("upload-not-found");
            }
        }

        @Override
        public void apply(WebAccess.Session session, String screen, String upload) {
            actions.add("apply " + screen + " " + upload);
        }

        @Override
        public void create(WebAccess.Session session, Map<String, String> fields) throws ScreenException {
            if (!fields.containsKey("name")) {
                throw new ScreenException("panel.missing-field");
            }
            actions.add("create " + fields.get("name"));
        }

        @Override
        public void remove(WebAccess.Session session, String screen) throws ScreenException {
            if (!session.grant.remove) {
                throw new ScreenException("panel.error-permission");
            }
            actions.add("remove " + screen);
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static TestBackend start(int attempts) throws Exception {
        int port = freePort();
        File directory = Files.createTempDirectory("packetscreen-panel").toFile();
        Settings settings = TestSupport.settings("web.enabled", true, "web.port", port, "web.bind-address", "127.0.0.1",
                "web.login-attempts-per-minute", attempts);
        return new TestBackend(settings, directory);
    }

    @BeforeAll
    static void startPanel() throws Exception {
        backend = start(100);
        panel = new JavalinPanel(backend);
        panel.start();
        base = "http://127.0.0.1:" + backend.settings.webPort;
    }

    @AfterAll
    static void stopPanel() {
        panel.stop();
    }

    private static HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + path));
    }

    private static HttpResponse<String> login(String token) throws Exception {
        return send(request("/api/login").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"" + token + "\"}")));
    }

    private static String[] session() throws Exception {
        String token = backend.access.createToken(new WebAccess.Grant("Admin", null, true, true, false, "world", 0, 64, 0, 0f), 60000);
        HttpResponse<String> response = login(token);
        assertEquals(200, response.statusCode());
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        String value = cookie.substring(0, cookie.indexOf(';'));
        String state = send(request("/api/state").header("Cookie", value)).body();
        String csrf = Json.parseObject(state).get("csrf");
        return new String[]{value, csrf};
    }

    @Test
    void pageIsServedWithStrictSecurityHeaders() throws Exception {
        HttpResponse<String> response = send(request("/"));
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("<title>PacketScreen</title>"));
        assertTrue(response.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'none'"));
        assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElse(""));
        assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElse(""));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertTrue(response.headers().firstValue("Server").isEmpty());
        assertEquals(200, send(request("/app.js")).statusCode());
        int traversal = send(request("/../config.yml")).statusCode();
        assertTrue(traversal == 400 || traversal == 404, "status " + traversal);
    }

    @Test
    void apiRequiresASession() throws Exception {
        assertEquals(401, send(request("/api/state")).statusCode());
        assertEquals(401, send(request("/api/uploads/x.png")).statusCode());
        assertEquals(401, send(request("/api/state").header("Cookie", "ps_session=forged")).statusCode());
    }

    @Test
    void linksAreSingleUseAndCreateHardenedCookies() throws Exception {
        assertEquals(401, login("not-a-real-token-value-at-all").statusCode());
        String token = backend.access.createToken(new WebAccess.Grant("Admin", null, true, true, true, null, 0, 0, 0, 0f), 60000);
        HttpResponse<String> response = login(token);
        assertEquals(200, response.statusCode());
        String cookie = response.headers().firstValue("Set-Cookie").orElse("");
        assertTrue(cookie.startsWith("ps_session="), cookie);
        assertTrue(cookie.contains("HttpOnly"), cookie);
        assertTrue(cookie.contains("SameSite=Strict"), cookie);
        assertEquals(401, login(token).statusCode());
    }

    @Test
    void changesRequireTheCsrfTokenAndSameOrigin() throws Exception {
        String[] session = session();
        byte[] png = TestSupport.image("png", 8, 8, 0xFF00AA00);
        HttpResponse<String> missing = send(request("/api/upload").header("Cookie", session[0])
                .POST(HttpRequest.BodyPublishers.ofByteArray(png)));
        assertEquals(403, missing.statusCode());
        HttpResponse<String> foreign = send(request("/api/logout").header("Cookie", session[0]).header("X-CSRF-Token", session[1])
                .header("Origin", "http://evil.example").POST(HttpRequest.BodyPublishers.noBody()));
        assertEquals(403, foreign.statusCode());
        HttpResponse<String> remove = send(request("/api/screens/lobby").header("Cookie", session[0]).header("X-CSRF-Token", session[1])
                .DELETE());
        assertEquals(403, remove.statusCode());
        assertEquals("panel.error-permission", Json.parseObject(remove.body()).get("error"));
    }

    @Test
    void uploadsAreValidatedPreviewedAndDeleted() throws Exception {
        String[] session = session();
        byte[] png = TestSupport.image("png", 8, 8, 0xFF00AA00);
        HttpResponse<String> uploaded = send(request("/api/upload").header("Cookie", session[0]).header("X-CSRF-Token", session[1])
                .header("X-File-Name", "Logo%20Final.png").POST(HttpRequest.BodyPublishers.ofByteArray(png)));
        assertEquals(200, uploaded.statusCode(), uploaded.body());
        String id = Json.parseObject(uploaded.body()).get("id");
        assertTrue(id.startsWith("logo-final-"), id);

        HttpResponse<byte[]> range = CLIENT.send(request("/api/uploads/" + id).header("Cookie", session[0])
                .header("Range", "bytes=0-3").build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(206, range.statusCode());
        assertEquals(4, range.body().length);
        assertEquals("image/png", range.headers().firstValue("Content-Type").orElse(""));
        assertEquals(416, send(request("/api/uploads/" + id).header("Cookie", session[0]).header("Range", "bytes=9999-")).statusCode());
        assertEquals(404, send(request("/api/uploads/unknown-000000.png").header("Cookie", session[0])).statusCode());

        byte[] page = "<html>hi</html>".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> html = send(request("/api/upload").header("Cookie", session[0]).header("X-CSRF-Token", session[1])
                .POST(HttpRequest.BodyPublishers.ofByteArray(page)));
        assertEquals(415, html.statusCode());
        byte[] huge = TestSupport.image("bmp", 300, 300, 0xFF000000);
        HttpResponse<String> large = send(request("/api/upload").header("Cookie", session[0]).header("X-CSRF-Token", session[1])
                .POST(HttpRequest.BodyPublishers.ofByteArray(huge)));
        assertEquals(413, large.statusCode());

        HttpResponse<String> deleted = send(request("/api/uploads/" + id).header("Cookie", session[0]).header("X-CSRF-Token", session[1])
                .DELETE());
        assertEquals(200, deleted.statusCode());
        assertEquals(404, send(request("/api/uploads/" + id).header("Cookie", session[0])).statusCode());
    }

    @Test
    void screenActionsReachTheBackend() throws Exception {
        String[] session = session();
        HttpResponse<String> create = send(request("/api/screens").header("Cookie", session[0]).header("X-CSRF-Token", session[1])
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"hall\",\"upload\":\"a.png\"}")));
        assertEquals(200, create.statusCode());
        HttpResponse<String> invalid = send(request("/api/screens").header("Cookie", session[0]).header("X-CSRF-Token", session[1])
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("not json")));
        assertEquals(400, invalid.statusCode());
        HttpResponse<String> apply = send(request("/api/screens/hall/source").header("Cookie", session[0])
                .header("X-CSRF-Token", session[1]).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"upload\":\"b.png\"}")));
        assertEquals(200, apply.statusCode());
        assertTrue(backend.actions.contains("create hall"));
        assertTrue(backend.actions.contains("apply hall b.png"));
    }

    @Test
    void loginAttemptsAreLimitedPerAddress() throws Exception {
        TestBackend limited = start(2);
        JavalinPanel other = new JavalinPanel(limited);
        other.start();
        try {
            String url = "http://127.0.0.1:" + limited.settings.webPort + "/api/login";
            int last = 0;
            for (int i = 0; i < 3; i++) {
                last = CLIENT.send(HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"xxxxxxxxxxxxxxxxxxxxxxxx\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString()).statusCode();
            }
            assertEquals(429, last);
        } finally {
            other.stop();
        }
    }
}
