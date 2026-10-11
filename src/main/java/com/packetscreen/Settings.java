package com.packetscreen;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

final class Settings {

    enum FrameType { AUTO, ITEM_FRAME, GLOW_ITEM_FRAME }

    enum Dithering { NONE, ORDERED, FLOYD_STEINBERG }

    enum ErrorDisplay { KEEP_LAST, PLACEHOLDER, HIDE }

    static final List<String> SUBCOMMANDS = Collections.unmodifiableList(java.util.Arrays.asList(
            "create", "video", "url", "refresh", "resize", "scale", "aspect", "resolution", "fps", "play", "pause",
            "stop", "restart", "loop", "move", "rotate", "range", "hologram", "remove", "list", "info", "reload", "web"));

    private static final Pattern LANGUAGE = Pattern.compile("[A-Za-z]{2,3}(_[A-Za-z]{2,4})?");
    private static final Pattern HOST = Pattern.compile("[a-z0-9.-]{1,253}");
    private static final Pattern WEB_HOST = Pattern.compile("[A-Za-z0-9.:\\[\\]-]{0,253}");
    private static final Pattern PUBLIC_URL = Pattern.compile("https?://[A-Za-z0-9.:\\[\\]-]+(/[A-Za-z0-9._~/-]*)?");

    final String language;
    final boolean debug;
    final double defaultWidth;
    final double defaultHeight;
    final FitMode defaultFitMode;
    final boolean defaultPreserveAspect;
    final double defaultViewDistance;
    final int defaultFps;
    final int maxScreens;
    final int maxConcurrentVideos;
    final double minSize;
    final double maxWidth;
    final double maxHeight;
    final double maxViewDistance;
    final int maxFps;
    final int maxProcessingWidth;
    final int maxProcessingHeight;
    final long commandCooldownMs;
    final int maxTargetDistance;
    final int processingThreads;
    final double videoResolutionScale;
    final long imageMaxBytes;
    final int imageMaxSourceWidth;
    final int imageMaxSourceHeight;
    final long imageMaxSourcePixels;
    final int animationMaxFrames;
    final long animationMaxBytes;
    final String ffmpegPath;
    final String tempDirectory;
    final long videoMaxBytes;
    final int videoMaxDurationSeconds;
    final int videoMaxSourceWidth;
    final int videoMaxSourceHeight;
    final int decoderThreads;
    final boolean autoplayOnCreate;
    final boolean autoplayOnStartup;
    final boolean cacheEnabled;
    final long cacheMaxBytes;
    final long cacheExpireMs;
    final long failureCooldownMs;
    final int connectTimeoutMs;
    final int readTimeoutMs;
    final int totalTimeoutMs;
    final int maxRedirects;
    final String userAgent;
    final boolean allowPrivateAddresses;
    final Set<Integer> allowedPorts;
    final List<String> allowedHosts;
    final List<String> blockedHosts;
    final int maxUrlLength;
    final int tickIntervalMs;
    final int trackerIntervalMs;
    final int positionIntervalTicks;
    final long bytesPerSecondPerPlayer;
    final int packetsPerSecondPerPlayer;
    final long bytesPerSecondTotal;
    final boolean silentPackets;
    final FrameType frameType;
    final boolean invisibleFrames;
    final boolean transparentBackground;
    final int backgroundColor;
    final boolean solidBack;
    final int backColor;
    final Dithering imageDithering;
    final Dithering videoDithering;
    final int mapIdMin;
    final int mapIdMax;
    final int entityIdStart;
    final ErrorDisplay errorDisplay;
    final int placeholderColor;
    final int loadRetries;
    final int retryDelaySeconds;
    final Map<String, String> permissions;
    final boolean webEnabled;
    final String webHost;
    final int webPort;
    final String webBindAddress;
    final String webPublicUrl;
    final int webTokenMinutes;
    final int webSessionMinutes;
    final long webMaxStorageBytes;
    final int webLoginAttempts;
    final boolean webTrustProxy;

    private Settings(Reader r) {
        String lang = r.string("language", "en");
        language = LANGUAGE.matcher(lang).matches() ? lang : r.invalid("language", lang, "en");
        debug = r.bool("debug", false);
        defaultWidth = r.decimal("defaults.width", 3.0, 0.0625, 64);
        defaultHeight = r.decimal("defaults.height", 2.0, 0.0625, 64);
        defaultFitMode = r.enumeration("defaults.fit-mode", FitMode.class, FitMode.FIT);
        defaultPreserveAspect = r.bool("defaults.preserve-aspect", true);
        defaultFps = r.integer("defaults.fps", 10, 1, 60);
        maxScreens = r.integer("limits.max-screens", 20, 1, 10000);
        maxConcurrentVideos = r.integer("limits.max-concurrent-videos", 2, 0, 64);
        minSize = r.decimal("limits.min-size", 0.5, 0.0625, 64);
        maxWidth = r.decimal("limits.max-width", 10.0, minSize, 128);
        maxHeight = r.decimal("limits.max-height", 10.0, minSize, 128);
        maxViewDistance = r.decimal("limits.max-view-distance", 128, 4, 1024);
        defaultViewDistance = r.decimal("defaults.view-distance", 48, 4, maxViewDistance);
        maxFps = r.integer("limits.max-fps", 20, 1, 60);
        maxProcessingWidth = r.integer("limits.max-processing-width", 1280, 16, 16384);
        maxProcessingHeight = r.integer("limits.max-processing-height", 1280, 16, 16384);
        commandCooldownMs = r.integer("limits.command-cooldown-ms", 1000, 0, 3600000);
        maxTargetDistance = r.integer("limits.max-target-distance", 64, 4, 512);
        processingThreads = r.integer("processing.threads", 2, 1, 32);
        videoResolutionScale = r.decimal("processing.video-resolution-scale", 0.5, 0.05, 1.0);
        imageMaxBytes = r.integer("image.max-file-size-kb", 10240, 16, 1048576) * 1024L;
        imageMaxSourceWidth = r.integer("image.max-source-width", 8192, 16, 65535);
        imageMaxSourceHeight = r.integer("image.max-source-height", 8192, 16, 65535);
        imageMaxSourcePixels = r.integer("image.max-source-pixels", 33554432, 256, Integer.MAX_VALUE);
        animationMaxFrames = r.integer("image.max-animation-frames", 1000, 2, 20000);
        animationMaxBytes = r.integer("image.max-animation-memory-mb", 32, 1, 1024) * 1048576L;
        ffmpegPath = r.string("video.ffmpeg-path", "ffmpeg");
        tempDirectory = r.string("video.temp-directory", "temp");
        videoMaxBytes = r.integer("video.max-file-size-mb", 100, 1, 8192) * 1048576L;
        videoMaxDurationSeconds = r.integer("video.max-duration-seconds", 600, 1, 86400);
        videoMaxSourceWidth = r.integer("video.max-source-width", 1920, 16, 16384);
        videoMaxSourceHeight = r.integer("video.max-source-height", 1080, 16, 16384);
        decoderThreads = r.integer("video.decoder-threads", 1, 1, 64);
        autoplayOnCreate = r.bool("video.autoplay-on-create", true);
        autoplayOnStartup = r.bool("video.autoplay-on-startup", false);
        cacheEnabled = r.bool("cache.enabled", true);
        cacheMaxBytes = r.integer("cache.max-memory-mb", 32, 0, 65536) * 1048576L;
        cacheExpireMs = r.integer("cache.expire-minutes", 30, 1, 525600) * 60000L;
        failureCooldownMs = r.integer("cache.failure-cooldown-seconds", 30, 0, 86400) * 1000L;
        connectTimeoutMs = r.integer("http.connect-timeout-ms", 5000, 250, 120000);
        readTimeoutMs = r.integer("http.read-timeout-ms", 10000, 250, 300000);
        totalTimeoutMs = r.integer("http.total-timeout-ms", 30000, 1000, 3600000);
        maxRedirects = r.integer("http.max-redirects", 3, 0, 20);
        userAgent = r.string("http.user-agent", "PacketScreen").replace('\r', ' ').replace('\n', ' ');
        allowPrivateAddresses = r.bool("security.allow-private-addresses", false);
        allowedPorts = r.ports("security.allowed-ports");
        allowedHosts = r.hosts("security.allowed-hosts");
        blockedHosts = r.hosts("security.blocked-hosts");
        maxUrlLength = r.integer("security.max-url-length", 2048, 32, 16384);
        tickIntervalMs = r.integer("network.tick-interval-ms", 50, 10, 1000);
        trackerIntervalMs = r.integer("network.tracker-interval-ms", 250, 50, 10000);
        positionIntervalTicks = r.integer("network.position-interval-ticks", 5, 1, 200);
        bytesPerSecondPerPlayer = r.integer("network.max-kilobytes-per-second-per-player", 1024, 16, 10485760) * 1024L;
        packetsPerSecondPerPlayer = r.integer("network.max-packets-per-second-per-player", 600, 20, 1000000);
        bytesPerSecondTotal = r.integer("network.max-kilobytes-per-second-total", 8192, 16, 104857600) * 1024L;
        silentPackets = r.bool("network.silent-packets", true);
        frameType = r.enumeration("rendering.frame-type", FrameType.class, FrameType.AUTO);
        invisibleFrames = r.bool("rendering.invisible-frames", true);
        transparentBackground = r.bool("rendering.transparent-background", true);
        backgroundColor = r.color("rendering.background-color", 0x000000);
        solidBack = r.bool("rendering.solid-back", true);
        backColor = r.color("rendering.back-color", 0x000000);
        imageDithering = r.enumeration("rendering.image-dithering", Dithering.class, Dithering.FLOYD_STEINBERG);
        videoDithering = r.enumeration("rendering.video-dithering", Dithering.class, Dithering.ORDERED);
        mapIdMin = r.integer("rendering.map-id-min", 24000, 0, Integer.MAX_VALUE - 1);
        mapIdMax = r.integer("rendering.map-id-max", 32767, mapIdMin, Integer.MAX_VALUE - 1);
        entityIdStart = r.integer("rendering.entity-id-start", 2100000000, 100000000, Integer.MAX_VALUE);
        errorDisplay = r.enumeration("errors.display", ErrorDisplay.class, ErrorDisplay.KEEP_LAST);
        placeholderColor = r.color("errors.placeholder-color", 0x400000);
        loadRetries = r.integer("errors.load-retries", 1, 0, 10);
        retryDelaySeconds = r.integer("errors.retry-delay-seconds", 5, 1, 3600);
        permissions = r.permissions("command-permissions");
        webEnabled = r.bool("web.enabled", false);
        String host = r.optional("web.host");
        webHost = WEB_HOST.matcher(host).matches() ? host : r.invalid("web.host", host, "");
        webPort = r.integer("web.port", 8125, 1, 65535);
        String bind = r.string("web.bind-address", "0.0.0.0").trim();
        webBindAddress = WEB_HOST.matcher(bind).matches() && !bind.isEmpty() ? bind : r.invalid("web.bind-address", bind, "0.0.0.0");
        String publicUrl = r.optional("web.public-url");
        webPublicUrl = publicUrl.isEmpty() || PUBLIC_URL.matcher(publicUrl).matches()
                ? publicUrl : r.invalid("web.public-url", publicUrl, "");
        webTokenMinutes = r.integer("web.token-minutes", 10, 1, 1440);
        webSessionMinutes = r.integer("web.session-minutes", 30, 1, 1440);
        webMaxStorageBytes = r.integer("web.max-storage-mb", 1024, 1, 1048576) * 1048576L;
        webLoginAttempts = r.integer("web.login-attempts-per-minute", 10, 1, 1000);
        webTrustProxy = r.bool("web.trust-proxy", false);
    }

    static Settings read(ConfigurationSection config, Logger logger) {
        return new Settings(new Reader(config, logger));
    }

    String permission(String subcommand) {
        String permission = permissions.get(subcommand);
        return permission == null ? "packetscreen.admin" : permission;
    }

    private static final class Reader {

        private final ConfigurationSection config;
        private final Logger logger;

        Reader(ConfigurationSection config, Logger logger) {
            this.config = config;
            this.logger = logger;
        }

        <T> T invalid(String path, Object value, T fallback) {
            logger.warning("Invalid value '" + value + "' for " + path + " in config.yml, using " + fallback + ".");
            return fallback;
        }

        String optional(String path) {
            Object value = config.get(path);
            return value == null ? "" : String.valueOf(value).trim();
        }

        String string(String path, String fallback) {
            Object value = config.get(path);
            if (value == null) {
                return fallback;
            }
            String text = String.valueOf(value).trim();
            return text.isEmpty() ? invalid(path, value, fallback) : text;
        }

        boolean bool(String path, boolean fallback) {
            Object value = config.get(path);
            if (value == null) {
                return fallback;
            }
            return value instanceof Boolean ? (Boolean) value : invalid(path, value, fallback);
        }

        int integer(String path, int fallback, int min, int max) {
            Object value = config.get(path);
            if (value == null) {
                return fallback;
            }
            if (!(value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte)) {
                return invalid(path, value, fallback);
            }
            long number = ((Number) value).longValue();
            if (number < min || number > max) {
                return invalid(path, value, Math.max(min, Math.min(max, fallback)));
            }
            return (int) number;
        }

        double decimal(String path, double fallback, double min, double max) {
            Object value = config.get(path);
            if (value == null) {
                return fallback;
            }
            if (!(value instanceof Number)) {
                return invalid(path, value, fallback);
            }
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number) || number < min || number > max) {
                return invalid(path, value, Math.max(min, Math.min(max, fallback)));
            }
            return number;
        }

        <E extends Enum<E>> E enumeration(String path, Class<E> type, E fallback) {
            Object value = config.get(path);
            if (value == null) {
                return fallback;
            }
            try {
                return Enum.valueOf(type, String.valueOf(value).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return invalid(path, value, fallback);
            }
        }

        int color(String path, int fallback) {
            Object value = config.get(path);
            if (value == null) {
                return fallback;
            }
            String text = String.valueOf(value).trim();
            if (text.startsWith("#")) {
                text = text.substring(1);
            }
            if (text.length() != 6) {
                return invalid(path, value, fallback);
            }
            try {
                return Integer.parseInt(text, 16);
            } catch (NumberFormatException e) {
                return invalid(path, value, fallback);
            }
        }

        Set<Integer> ports(String path) {
            Set<Integer> ports = new LinkedHashSet<>();
            for (Object value : config.getList(path, Collections.emptyList())) {
                if (value instanceof Number && ((Number) value).intValue() > 0 && ((Number) value).intValue() <= 65535) {
                    ports.add(((Number) value).intValue());
                } else {
                    invalid(path, value, "ignored");
                }
            }
            if (ports.isEmpty()) {
                ports.add(80);
                ports.add(443);
            }
            return Collections.unmodifiableSet(ports);
        }

        List<String> hosts(String path) {
            List<String> hosts = new ArrayList<>();
            for (Object value : config.getList(path, Collections.emptyList())) {
                String host = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
                while (host.startsWith("*.") || host.startsWith(".")) {
                    host = host.substring(host.indexOf('.') + 1);
                }
                if (HOST.matcher(host).matches()) {
                    hosts.add(host);
                } else {
                    invalid(path, value, "ignored");
                }
            }
            return Collections.unmodifiableList(hosts);
        }

        Map<String, String> permissions(String path) {
            Map<String, String> defaults = new HashMap<>();
            for (String sub : SUBCOMMANDS) {
                defaults.put(sub, "packetscreen.admin");
            }
            defaults.put("create", "packetscreen.create");
            defaults.put("url", "packetscreen.create");
            defaults.put("refresh", "packetscreen.create");
            defaults.put("video", "packetscreen.video");
            for (String sub : new String[]{"fps", "play", "pause", "stop", "restart", "loop"}) {
                defaults.put(sub, "packetscreen.video");
            }
            for (String sub : new String[]{"resize", "scale", "aspect", "resolution"}) {
                defaults.put(sub, "packetscreen.resize");
            }
            for (String sub : new String[]{"move", "rotate", "range", "hologram"}) {
                defaults.put(sub, "packetscreen.move");
            }
            defaults.put("remove", "packetscreen.remove");
            defaults.put("list", "packetscreen.list");
            defaults.put("info", "packetscreen.list");
            defaults.put("reload", "packetscreen.reload");
            defaults.put("web", "packetscreen.web");
            ConfigurationSection section = config.getConfigurationSection(path);
            if (section != null) {
                for (String sub : SUBCOMMANDS) {
                    Object value = section.get(sub);
                    if (value != null) {
                        defaults.put(sub, String.valueOf(value).trim());
                    }
                }
            }
            return Collections.unmodifiableMap(defaults);
        }
    }
}
