package com.packetscreen;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

final class VirtualScreen {

    final String name;
    volatile ScreenType type;
    volatile String url;
    volatile String world;
    volatile int x;
    volatile int y;
    volatile int z;
    volatile Facing facing;
    volatile int rotation;
    volatile double width;
    volatile double height;
    volatile FitMode fitMode;
    volatile boolean preserveAspect;
    volatile int processingWidth;
    volatile int processingHeight;
    volatile int fps;
    volatile boolean loop;
    volatile double range;
    volatile boolean hologram;
    volatile boolean animated;
    volatile int frames;
    volatile int sourceWidth;
    volatile int sourceHeight;
    volatile ScreenState state = ScreenState.LOADING;
    volatile MediaException error;
    volatile ScreenGeometry geometry;
    volatile ScreenView view;
    volatile VideoPlayer player;
    volatile boolean removed;
    volatile int viewers;
    private final AtomicInteger sequence = new AtomicInteger();

    VirtualScreen(String name, ScreenType type, String url) {
        this.name = name;
        this.type = type;
        this.url = url;
    }

    int nextSequence() {
        return sequence.incrementAndGet();
    }

    boolean isCurrent(int value) {
        return sequence.get() == value;
    }

    int sequence() {
        return sequence.get();
    }

    boolean isVideo() {
        return type == ScreenType.VIDEO;
    }

    ScreenGeometry computeGeometry(Settings settings) {
        return ScreenGeometry.compute(width, height, preserveAspect, fitMode, processingWidth, processingHeight,
                sourceWidth, sourceHeight, isVideo() ? settings.videoResolutionScale : 1.0,
                settings.maxProcessingWidth, settings.maxProcessingHeight);
    }

    void write(ConfigurationSection section) {
        section.set("type", type.name());
        section.set("url", url);
        section.set("world", world);
        section.set("x", x);
        section.set("y", y);
        section.set("z", z);
        section.set("facing", facing.name());
        section.set("rotation", rotation * 90);
        section.set("width", width);
        section.set("height", height);
        section.set("fit-mode", fitMode.name());
        section.set("preserve-aspect", preserveAspect);
        section.set("processing-width", processingWidth);
        section.set("processing-height", processingHeight);
        section.set("fps", fps);
        section.set("loop", loop);
        section.set("view-distance", range);
        section.set("hologram", hologram);
        section.set("source-width", sourceWidth);
        section.set("source-height", sourceHeight);
    }

    static VirtualScreen read(String name, ConfigurationSection section, Settings settings) {
        ScreenType type = ScreenType.valueOf(section.getString("type", "IMAGE").toUpperCase(Locale.ROOT));
        String url = section.getString("url");
        String world = section.getString("world");
        Facing facing = Facing.parse(section.getString("facing", "NORTH"));
        FitMode fitMode = FitMode.parse(section.getString("fit-mode", settings.defaultFitMode.name()));
        int rotation = section.getInt("rotation", 0);
        if (url == null || url.isEmpty() || world == null || world.isEmpty() || facing == null || fitMode == null
                || rotation % 90 != 0) {
            throw new IllegalArgumentException("missing or invalid values");
        }
        VirtualScreen screen = new VirtualScreen(name, type, url);
        screen.world = world;
        screen.x = section.getInt("x");
        screen.y = section.getInt("y");
        screen.z = section.getInt("z");
        screen.facing = facing;
        screen.rotation = Math.floorMod(rotation / 90, 4);
        screen.width = clamp(section.getDouble("width", settings.defaultWidth), settings.minSize, settings.maxWidth);
        screen.height = clamp(section.getDouble("height", settings.defaultHeight), settings.minSize, settings.maxHeight);
        screen.fitMode = fitMode;
        screen.preserveAspect = section.getBoolean("preserve-aspect", settings.defaultPreserveAspect);
        int processingWidth = section.getInt("processing-width", 0);
        int processingHeight = section.getInt("processing-height", 0);
        boolean manual = processingWidth >= 16 && processingHeight >= 16
                && processingWidth <= settings.maxProcessingWidth && processingHeight <= settings.maxProcessingHeight;
        screen.processingWidth = manual ? processingWidth : 0;
        screen.processingHeight = manual ? processingHeight : 0;
        screen.fps = Math.max(1, Math.min(settings.maxFps, section.getInt("fps", settings.defaultFps)));
        screen.loop = section.getBoolean("loop", true);
        screen.range = clamp(section.getDouble("view-distance", settings.defaultViewDistance), 4, settings.maxViewDistance);
        screen.hologram = section.getBoolean("hologram", false);
        screen.sourceWidth = Math.max(0, section.getInt("source-width", 0));
        screen.sourceHeight = Math.max(0, section.getInt("source-height", 0));
        if (screen.sourceWidth == 0 || screen.sourceHeight == 0) {
            screen.sourceWidth = 0;
            screen.sourceHeight = 0;
        }
        return screen;
    }

    private static double clamp(double value, double min, double max) {
        return Double.isNaN(value) ? min : Math.max(min, Math.min(max, value));
    }
}
