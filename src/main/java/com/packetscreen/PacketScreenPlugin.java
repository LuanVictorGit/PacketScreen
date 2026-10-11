package com.packetscreen;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public final class PacketScreenPlugin extends JavaPlugin {

    private ConfigurationManager config;
    private TaskScheduler scheduler;
    private volatile VersionAdapter adapter;
    private MapPalette palette;
    private HttpFetcher http;
    private ImageLoader images;
    private VideoStorage videos;
    private UploadStore uploads;
    private PluginPanel panel;
    private ScreenManager screens;
    private PacketRenderer renderer;
    private ExecutorService downloads;
    private ExecutorService processing;
    private ScheduledExecutorService timer;
    private volatile String ffmpegPath;
    private volatile long ffmpegCheckedAt;
    private volatile boolean ffmpegFound;

    @Override
    public void onEnable() {
        if (PacketEvents.getAPI() == null || !PacketEvents.getAPI().isInitialized()) {
            getLogger().severe("PacketEvents is not initialized. Install the PacketEvents plugin (2.14.0 or newer).");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        ServerVersion version = PacketEvents.getAPI().getServerManager().getVersion();
        if (version == null || version == ServerVersion.ERROR || version.isOlderThan(ServerVersion.V_1_8)) {
            getLogger().severe("Unsupported server version " + version + ". PacketScreen supports 1.8 to 26.3.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        config = new ConfigurationManager(this, version.isNewerThanOrEquals(ServerVersion.V_1_16));
        try {
            config.load();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not load config.yml or the messages file", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Settings settings = config.settings();
        scheduler = new TaskScheduler(this);
        adapter = new VersionAdapter(version, settings);
        if (settings.frameType == Settings.FrameType.GLOW_ITEM_FRAME && !adapter.supportsGlowFrames()) {
            getLogger().warning("Glow item frames require 1.17+, using item frames.");
        }
        palette = MapPalette.forVersion(version);
        downloads = Executors.newFixedThreadPool(2, factory("PacketScreen-Download"));
        processing = Executors.newFixedThreadPool(settings.processingThreads, factory("PacketScreen-Worker"));
        timer = Executors.newSingleThreadScheduledExecutor(factory("PacketScreen-Timer"));
        processing.execute(palette::prepare);
        http = new HttpFetcher(config::settings, getDescription().getVersion(), timer);
        uploads = new UploadStore(new File(getDataFolder(), "uploads"));
        uploads.cleanup();
        images = new ImageLoader(config::settings, http, uploads, downloads);
        videos = new VideoStorage(this);
        videos.cleanup();
        screens = new ScreenManager(this, settings);
        renderer = new PacketRenderer(this);
        renderer.start();
        screens.load();
        CommandManager commands = new CommandManager(this);
        PluginCommand command = getCommand("packetscreen");
        if (command != null) {
            command.setExecutor(commands);
            command.setTabCompleter(commands);
        }
        panel = new PluginPanel(this);
        panel.start();
        downloads.execute(() -> {
            if (!ffmpegAvailable()) {
                getLogger().warning("FFmpeg was not found at '" + config.settings().ffmpegPath + "'. Video screens will not play until it is installed.");
            }
        });
        getLogger().info("Enabled for Minecraft " + version.getReleaseName() + " using " + adapter.describe()
                + (scheduler.isFolia() ? " (Folia schedulers)." : "."));
    }

    @Override
    public void onDisable() {
        if (panel != null) {
            panel.stop();
        }
        if (screens != null) {
            screens.shutdown();
        }
        if (renderer != null) {
            renderer.stop();
        }
        shutdown(downloads);
        shutdown(processing);
        shutdown(timer);
        if (videos != null) {
            videos.cleanup();
        }
    }

    void reload() throws Exception {
        Settings before = config.settings();
        config.load();
        Settings after = config.settings();
        VersionAdapter next = new VersionAdapter(adapter.version(), after);
        boolean respawn = !next.sameRendering(adapter);
        adapter = next;
        ffmpegCheckedAt = 0;
        if (!after.cacheEnabled) {
            images.clear();
        }
        if (respawn) {
            renderer.respawnAll();
        }
        panel.reload(before, after);
        if (before.solidBack != after.solidBack || before.backColor != after.backColor) {
            screens.updateBack(after);
            screens.refreshBacks();
        }
        if (before.imageDithering != after.imageDithering || before.videoDithering != after.videoDithering
                || before.transparentBackground != after.transparentBackground || before.backgroundColor != after.backgroundColor
                || before.videoResolutionScale != after.videoResolutionScale || before.maxProcessingWidth != after.maxProcessingWidth
                || before.maxProcessingHeight != after.maxProcessingHeight) {
            screens.rerender();
        }
    }

    boolean ffmpegAvailable() {
        String path = config.settings().ffmpegPath;
        long now = System.currentTimeMillis();
        if (!path.equals(ffmpegPath) || now - ffmpegCheckedAt > 60000) {
            ffmpegFound = VideoDecoder.available(path, timer);
            ffmpegPath = path;
            ffmpegCheckedAt = now;
        }
        return ffmpegFound;
    }

    void notify(CommandSender sender, String key, Object... placeholders) {
        if (sender == null || !isEnabled()) {
            return;
        }
        if (sender instanceof Player && !((Player) sender).isOnline()) {
            return;
        }
        scheduler.runFor(sender, () -> config.send(sender, key, placeholders));
    }

    ConfigurationManager config() {
        return config;
    }

    TaskScheduler scheduler() {
        return scheduler;
    }

    VersionAdapter adapter() {
        return adapter;
    }

    MapPalette palette() {
        return palette;
    }

    HttpFetcher http() {
        return http;
    }

    ImageLoader images() {
        return images;
    }

    VideoStorage videos() {
        return videos;
    }

    UploadStore uploads() {
        return uploads;
    }

    PluginPanel panel() {
        return panel;
    }

    ScreenManager screens() {
        return screens;
    }

    PacketRenderer renderer() {
        return renderer;
    }

    ExecutorService processing() {
        return processing;
    }

    ScheduledExecutorService timer() {
        return timer;
    }

    private static ThreadFactory factory(String name) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void shutdown(ExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
