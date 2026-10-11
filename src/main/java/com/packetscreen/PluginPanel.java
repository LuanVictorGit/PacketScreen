package com.packetscreen;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

final class PluginPanel implements PanelBackend, Listener {

    private static final String SERVER_CLASS = "com.packetscreen.JavalinPanel";
    private static final int MIN_JAVA = 17;

    private final PacketScreenPlugin plugin;
    private final WebAccess access = new WebAccess();
    private volatile WebServer server;
    private volatile String failure;
    private volatile List<String> worlds = Collections.emptyList();
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "PacketScreen-Panel");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean closed;

    PluginPanel(PacketScreenPlugin plugin) {
        this.plugin = plugin;
    }

    static int javaVersion() {
        String version = System.getProperty("java.specification.version", "8");
        if (version.startsWith("1.")) {
            version = version.substring(2);
        }
        try {
            return Integer.parseInt(version.split("\\.")[0]);
        } catch (NumberFormatException e) {
            return 8;
        }
    }

    void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        refreshWorlds();
        if (settings().webEnabled) {
            failure = "starting";
        }
        submit(this::launch);
    }

    void stop() {
        HandlerList.unregisterAll(this);
        closed = true;
        try {
            lifecycle.submit(this::shutdown).get(10, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            shutdown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            plugin.getLogger().log(Level.WARNING, "Could not stop the web panel", e);
        }
        lifecycle.shutdown();
    }

    void reload(Settings before, Settings after) {
        boolean changed = before.webEnabled != after.webEnabled || before.webPort != after.webPort
                || !before.webBindAddress.equals(after.webBindAddress) || before.webTrustProxy != after.webTrustProxy;
        if (changed) {
            if (after.webEnabled) {
                failure = "starting";
            }
            submit(() -> {
                shutdown();
                launch();
            });
        }
    }

    boolean running() {
        return server != null;
    }

    private void submit(Runnable task) {
        try {
            lifecycle.execute(task);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void shutdown() {
        WebServer current = server;
        server = null;
        access.revokeAll();
        if (current != null) {
            try {
                current.stop();
            } catch (Throwable error) {
                plugin.getLogger().log(Level.WARNING, "Could not stop the web panel", error);
            }
        }
    }

    private void launch() {
        Settings settings = settings();
        if (closed || !settings.webEnabled) {
            failure = null;
            return;
        }
        if (javaVersion() < MIN_JAVA) {
            failure = "Java " + javaVersion();
            plugin.getLogger().warning("The web panel requires Java " + MIN_JAVA + " or newer; this server runs Java " + javaVersion() + ".");
            return;
        }
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        try {
            Class<?> type = Class.forName(SERVER_CLASS, true, getClass().getClassLoader());
            WebServer created = (WebServer) type.getDeclaredConstructor(PanelBackend.class).newInstance(this);
            created.start();
            if (closed) {
                created.stop();
                return;
            }
            server = created;
            failure = null;
            plugin.getLogger().info("Web panel listening on " + settings.webBindAddress + ":" + settings.webPort
                    + ", links use " + baseUrl() + ".");
        } catch (Throwable error) {
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            failure = cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
            plugin.getLogger().log(Level.WARNING, "Could not start the web panel on port " + settings.webPort, cause);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    String link(CommandSender sender, String label) throws ScreenException {
        Settings settings = settings();
        if (!settings.webEnabled) {
            throw new ScreenException("web-disabled", "label", label);
        }
        if (javaVersion() < MIN_JAVA) {
            throw new ScreenException("web-unavailable", "java", javaVersion());
        }
        if (server == null) {
            throw new ScreenException("web-not-running", "error", failure == null ? "-" : failure);
        }
        String token = access.createToken(grant(sender), settings.webTokenMinutes * 60000L);
        plugin.getLogger().info(sender.getName() + " generated a web panel link.");
        return baseUrl() + "/#" + token;
    }

    void revoke(CommandSender sender) {
        access.revokeAll();
        plugin.getLogger().info(sender.getName() + " revoked every web panel link and session.");
    }

    private WebAccess.Grant grant(CommandSender sender) {
        ConfigurationManager config = plugin.config();
        boolean create = config.hasPermission(sender, "create");
        boolean video = config.hasPermission(sender, "video");
        boolean remove = config.hasPermission(sender, "remove");
        if (sender instanceof Player) {
            Player player = (Player) sender;
            Location location = player.getLocation();
            return new WebAccess.Grant(player.getName(), player.getUniqueId(), create, video, remove,
                    location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), location.getYaw());
        }
        return new WebAccess.Grant(sender.getName(), null, create, video, remove, null, 0, 0, 0, 0f);
    }

    String baseUrl() {
        Settings settings = settings();
        if (!settings.webPublicUrl.isEmpty()) {
            String url = settings.webPublicUrl;
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            return url;
        }
        String host = settings.webHost.isEmpty() ? detectHost(settings) : settings.webHost;
        if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        return "http://" + host + ":" + settings.webPort;
    }

    private static String detectHost(Settings settings) {
        String serverIp = Bukkit.getIp();
        if (serverIp != null && !serverIp.trim().isEmpty() && !serverIp.equals("0.0.0.0")) {
            return serverIp.trim();
        }
        String bind = settings.webBindAddress;
        if (!bind.equals("0.0.0.0") && !bind.equals("::") && !bind.isEmpty()) {
            return bind;
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                        if (!address.isSiteLocalAddress()) {
                            return address.getHostAddress();
                        }
                        if (fallback == null) {
                            fallback = address.getHostAddress();
                        }
                    }
                }
            }
            if (fallback != null) {
                return fallback;
            }
        } catch (IOException ignored) {
        }
        return "localhost";
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        refreshWorlds();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        refreshWorlds();
    }

    private void refreshWorlds() {
        List<String> names = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            names.add(world.getName());
        }
        worlds = Collections.unmodifiableList(names);
    }

    @Override
    public Settings settings() {
        return plugin.config().settings();
    }

    @Override
    public WebAccess access() {
        return access;
    }

    @Override
    public UploadStore uploads() {
        return plugin.uploads();
    }

    @Override
    public void audit(WebAccess.Session session, String action) {
        plugin.getLogger().info("[Web] " + session.grant.user + " " + action);
    }

    @Override
    public void debug(String message, Throwable error) {
        if (settings().debug) {
            plugin.getLogger().log(Level.WARNING, message, error);
        }
    }

    @Override
    public Map<String, String> texts() {
        return plugin.config().plainSection("panel");
    }

    @Override
    public String text(String key, Object... placeholders) {
        return plugin.config().plain(key, placeholders);
    }

    @Override
    public String reason(MediaException error) {
        return plugin.config().plain("reasons." + error.reason.key, error.placeholders);
    }

    @Override
    public Map<String, Object> state(WebAccess.Session session) {
        Settings settings = settings();
        WebAccess.Grant grant = session.grant;
        List<VirtualScreen> screens = plugin.screens().sorted();
        List<Object> uploadList = new ArrayList<>();
        long used = 0;
        for (UploadStore.Upload upload : uploads().list()) {
            used += upload.size;
            List<String> usedBy = new ArrayList<>();
            for (VirtualScreen screen : screens) {
                if (screen.url.equals(UploadStore.PREFIX + upload.id)) {
                    usedBy.add(screen.name);
                }
            }
            uploadList.add(Json.object("id", upload.id, "kind", upload.kind.name().toLowerCase(Locale.ROOT),
                    "size", upload.size, "modified", upload.modified, "usedBy", usedBy));
        }
        List<Object> screenList = new ArrayList<>();
        for (VirtualScreen screen : screens) {
            ScreenGeometry geometry = screen.computeGeometry(settings);
            screenList.add(Json.object("name", screen.name, "type", screen.isVideo() ? "video" : "image",
                    "source", screen.url, "width", ScreenManager.number(geometry.width),
                    "height", ScreenManager.number(geometry.height), "world", screen.world, "x", screen.x, "y", screen.y,
                    "z", screen.z, "facing", screen.facing.name().toLowerCase(Locale.ROOT),
                    "state", plugin.config().plain("states." + screen.state.name().toLowerCase(Locale.ROOT)),
                    "viewers", screen.viewers));
        }
        String facing = grant.world == null ? "south" : Facing.fromYaw(grant.yaw).opposite().name().toLowerCase(Locale.ROOT);
        return Json.object(
                "user", grant.user,
                "expires", session.expires,
                "now", System.currentTimeMillis(),
                "csrf", session.csrf,
                "can", Json.object("create", grant.create, "video", grant.video, "remove", grant.remove),
                "limits", Json.object("image", settings.imageMaxBytes, "video", settings.videoMaxBytes,
                        "storage", settings.webMaxStorageBytes, "used", used,
                        "minSize", settings.minSize, "maxWidth", settings.maxWidth, "maxHeight", settings.maxHeight),
                "uploads", uploadList,
                "screens", screenList,
                "worlds", worlds,
                "vertical", plugin.adapter().supportsVerticalFacing(),
                "defaults", Json.object("world", grant.world == null ? (worlds.isEmpty() ? null : worlds.get(0)) : grant.world,
                        "x", grant.x, "y", grant.y, "z", grant.z, "facing", facing,
                        "width", settings.defaultWidth, "height", settings.defaultHeight));
    }

    @Override
    public UploadStore.Upload store(WebAccess.Session session, String name, InputStream body, long length) throws MediaException {
        Settings settings = settings();
        UploadStore.Upload upload = uploads().store(name, body, length, settings.imageMaxBytes, settings.videoMaxBytes,
                settings.webMaxStorageBytes, this::validate);
        audit(session, "uploaded " + upload.id + " (" + HttpFetcher.formatBytes(upload.size) + ")");
        return upload;
    }

    private void validate(File file, UploadStore.Kind kind) throws MediaException {
        Settings settings = settings();
        if (kind == UploadStore.Kind.IMAGE) {
            try {
                ImageLoader.header(Files.readAllBytes(file.toPath()), settings);
            } catch (IOException e) {
                throw new MediaException(MediaException.Reason.DECODE, e);
            }
            return;
        }
        if (!plugin.ffmpegAvailable()) {
            return;
        }
        VideoDecoder.Probe probe = VideoDecoder.probe(settings.ffmpegPath, file, plugin.timer());
        if (probe.width > settings.videoMaxSourceWidth || probe.height > settings.videoMaxSourceHeight) {
            throw new MediaException(MediaException.Reason.RESOLUTION, "width", probe.width, "height", probe.height);
        }
        if (probe.duration > settings.videoMaxDurationSeconds) {
            throw new MediaException(MediaException.Reason.DURATION, "max", settings.videoMaxDurationSeconds);
        }
    }

    @Override
    public void deleteUpload(WebAccess.Session session, String id) throws ScreenException {
        if (!uploads().exists(id)) {
            throw new ScreenException("upload-not-found", "value", id, "label", "packetscreen");
        }
        List<String> usedBy = new ArrayList<>();
        for (VirtualScreen screen : plugin.screens().sorted()) {
            if (screen.url.equals(UploadStore.PREFIX + id)) {
                usedBy.add(screen.name);
            }
        }
        if (!usedBy.isEmpty()) {
            throw new ScreenException("panel.in-use", "name", id, "screens", String.join(", ", usedBy));
        }
        if (!uploads().delete(id)) {
            throw new ScreenException("panel.error-generic");
        }
        plugin.images().invalidate(UploadStore.PREFIX + id);
        audit(session, "deleted the upload " + id);
    }

    @Override
    public void apply(WebAccess.Session session, String name, String id) throws ScreenException {
        VirtualScreen screen = plugin.screens().get(name);
        if (screen == null) {
            throw new ScreenException("screen-not-found", "name", name);
        }
        if (screen.isVideo() ? !session.grant.video : !session.grant.create) {
            throw new ScreenException("panel.error-permission");
        }
        if (!uploads().exists(id)) {
            throw new ScreenException("upload-not-found", "value", id, "label", "packetscreen");
        }
        if (!compatible(screen.type, id)) {
            throw new ScreenException("panel.wrong-kind", "name", id, "screen", screen.name);
        }
        plugin.screens().changeUrl(screen, UploadStore.PREFIX + id, null);
        audit(session, "set the source of " + screen.name + " to " + id);
    }

    @Override
    public void create(WebAccess.Session session, Map<String, String> fields) throws ScreenException {
        Settings settings = settings();
        String name = field(fields, "name");
        if (!ScreenManager.NAME.matcher(name).matches()) {
            throw new ScreenException("invalid-name", "value", name);
        }
        String id = field(fields, "upload");
        if (!uploads().exists(id)) {
            throw new ScreenException("upload-not-found", "value", id, "label", "packetscreen");
        }
        ScreenType type = UploadStore.kind(id) == UploadStore.Kind.VIDEO || "video".equals(fields.get("type"))
                ? ScreenType.VIDEO : ScreenType.IMAGE;
        if (!compatible(type, id)) {
            throw new ScreenException("panel.wrong-kind", "name", id, "screen", name);
        }
        if (type == ScreenType.VIDEO ? !session.grant.video : !session.grant.create) {
            throw new ScreenException("panel.error-permission");
        }
        String world = field(fields, "world");
        if (!worlds.contains(world)) {
            throw new ScreenException("invalid-world", "value", world);
        }
        int x = coordinate(field(fields, "x"));
        int y = coordinate(field(fields, "y"));
        int z = coordinate(field(fields, "z"));
        Facing facing = Facing.parse(field(fields, "facing"));
        if (facing == null) {
            throw new ScreenException("invalid-facing", "value", fields.get("facing"));
        }
        int rotation = 0;
        if (facing.vertical()) {
            if (!plugin.adapter().supportsVerticalFacing()) {
                throw new ScreenException("compatibility-error", "version", plugin.adapter().version().getReleaseName(),
                        "feature", plugin.config().plain("features.floor-screens"));
            }
            Facing ahead = Facing.fromYaw(session.grant.yaw);
            rotation = facing.uprightRotation(facing == Facing.UP ? ahead : ahead.opposite());
        }
        double width = size(field(fields, "width"));
        double height = size(field(fields, "height"));
        if (width < settings.minSize || height < settings.minSize || width > settings.maxWidth || height > settings.maxHeight) {
            throw new ScreenException("size-out-of-bounds", "min", ScreenManager.number(settings.minSize),
                    "max-width", ScreenManager.number(settings.maxWidth), "max-height", ScreenManager.number(settings.maxHeight));
        }
        ScreenManager.Placement placement = new ScreenManager.Placement(world, x, y, z, facing, rotation);
        VirtualScreen screen = plugin.screens().create(null, name, type, UploadStore.PREFIX + id, placement, width, height);
        audit(session, "created the screen " + name + " with " + id + " at " + world + " " + x + ", " + y + ", " + z);
        if (type == ScreenType.VIDEO && settings.autoplayOnCreate) {
            try {
                plugin.screens().play(screen, null);
            } catch (ScreenException limit) {
                audit(session, "could not start " + name + ": " + plugin.config().plain(limit.key, limit.placeholders));
            }
        }
    }

    @Override
    public void remove(WebAccess.Session session, String name) throws ScreenException {
        if (!session.grant.remove) {
            throw new ScreenException("panel.error-permission");
        }
        VirtualScreen screen = plugin.screens().get(name);
        if (screen == null) {
            throw new ScreenException("screen-not-found", "name", name);
        }
        plugin.screens().remove(screen);
        audit(session, "removed the screen " + screen.name);
    }

    private static boolean compatible(ScreenType type, String id) {
        UploadStore.Kind kind = UploadStore.kind(id);
        if (type == ScreenType.IMAGE) {
            return kind == UploadStore.Kind.IMAGE;
        }
        return kind == UploadStore.Kind.VIDEO || id.endsWith(".gif");
    }

    private static String field(Map<String, String> fields, String key) throws ScreenException {
        String value = fields.get(key);
        if (value == null || value.trim().isEmpty() || value.length() > 256) {
            throw new ScreenException("panel.missing-field", "field", key);
        }
        return value.trim();
    }

    private static int coordinate(String value) throws ScreenException {
        try {
            double number = Double.parseDouble(value);
            if (Double.isNaN(number) || Math.abs(number) > 30_000_000) {
                throw new ScreenException("invalid-number", "value", value);
            }
            return (int) Math.floor(number);
        } catch (NumberFormatException e) {
            throw new ScreenException("invalid-number", "value", value);
        }
    }

    private static double size(String value) throws ScreenException {
        try {
            double number = Double.parseDouble(value.replace(',', '.'));
            if (Double.isNaN(number) || Double.isInfinite(number) || number <= 0) {
                throw new ScreenException("invalid-size", "value", value);
            }
            return number;
        } catch (NumberFormatException e) {
            throw new ScreenException("invalid-size", "value", value);
        }
    }
}
