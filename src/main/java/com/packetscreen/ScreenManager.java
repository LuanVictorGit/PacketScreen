package com.packetscreen;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.regex.Pattern;

final class ScreenManager {

    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    static final class Placement {
        final String world;
        final int x;
        final int y;
        final int z;
        final Facing facing;
        final int rotation;

        Placement(String world, int x, int y, int z, Facing facing, int rotation) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.facing = facing;
            this.rotation = rotation & 3;
        }
    }

    private final PacketScreenPlugin plugin;
    private final Map<String, VirtualScreen> screens = new ConcurrentHashMap<>();
    private final Set<VideoPlayer> players = ConcurrentHashMap.newKeySet();
    private final AtomicInteger versions = new AtomicInteger();
    private final AtomicBoolean saveQueued = new AtomicBoolean();
    private final Object saveLock = new Object();
    private final IdPool mapIds;
    private final IdPool entityIds;
    private final IdPool backIds;
    private final AtomicInteger backStamp = new AtomicInteger();
    private final int fullBackMap;
    private volatile byte[] fullBackData;
    private volatile int fullBackVersion;
    private volatile byte backColor;
    private final File file;

    ScreenManager(PacketScreenPlugin plugin, Settings settings) {
        this.plugin = plugin;
        int maxMap = plugin.adapter().supportsWideMapIds() ? settings.mapIdMax : Math.min(settings.mapIdMax, 32767);
        int minMap = Math.min(settings.mapIdMin, maxMap);
        this.mapIds = new IdPool(minMap, maxMap);
        this.entityIds = new IdPool(settings.entityIdStart - 499_999, settings.entityIdStart);
        this.backIds = new IdPool(settings.entityIdStart - 999_999, settings.entityIdStart - 500_000);
        int[] full = mapIds.allocate(1);
        this.fullBackMap = full == null ? BackFace.NONE : full[0];
        this.file = new File(plugin.getDataFolder(), "screens.yml");
        updateBack(settings);
    }

    void updateBack(Settings settings) {
        byte color = plugin.palette().matchRgb(settings.backColor);
        if (fullBackData == null || color != backColor) {
            byte[] data = new byte[RenderedFrame.TILE_AREA];
            Arrays.fill(data, color);
            backColor = color;
            fullBackData = data;
            fullBackVersion++;
        }
    }

    int fullBackMap() {
        return fullBackMap;
    }

    byte[] fullBackData() {
        return fullBackData;
    }

    int fullBackVersion() {
        return fullBackVersion;
    }

    Collection<VirtualScreen> all() {
        return screens.values();
    }

    List<VirtualScreen> sorted() {
        List<VirtualScreen> list = new ArrayList<>(screens.values());
        list.sort(Comparator.comparing(screen -> screen.name.toLowerCase(Locale.ROOT)));
        return list;
    }

    VirtualScreen get(String name) {
        return screens.get(name.toLowerCase(Locale.ROOT));
    }

    boolean isVirtualEntity(int id) {
        return entityIds.contains(id) || backIds.contains(id);
    }

    int activeVideos() {
        return players.size();
    }

    private Settings settings() {
        return plugin.config().settings();
    }

    VirtualScreen create(CommandSender requester, String name, ScreenType type, String url, Placement placement,
                         double width, double height) throws ScreenException {
        Settings settings = settings();
        VirtualScreen screen = new VirtualScreen(name, type, url);
        screen.world = placement.world;
        screen.x = placement.x;
        screen.y = placement.y;
        screen.z = placement.z;
        screen.facing = placement.facing;
        screen.rotation = placement.rotation;
        screen.width = width;
        screen.height = height;
        screen.fitMode = settings.defaultFitMode;
        screen.preserveAspect = settings.defaultPreserveAspect;
        screen.fps = Math.min(settings.defaultFps, settings.maxFps);
        screen.loop = true;
        screen.range = settings.defaultViewDistance;
        synchronized (this) {
            if (screens.size() >= settings.maxScreens) {
                throw new ScreenException("screen-limit", "max", settings.maxScreens);
            }
            String key = name.toLowerCase(Locale.ROOT);
            if (screens.containsKey(key)) {
                throw new ScreenException("screen-exists", "name", name);
            }
            checkSpace(null, placement, width, height, false);
            screens.put(key, screen);
        }
        scheduleSave();
        if (type == ScreenType.IMAGE) {
            renderImage(screen, false, requester);
        } else {
            showStill(screen);
        }
        return screen;
    }

    void remove(VirtualScreen screen) {
        screens.remove(screen.name.toLowerCase(Locale.ROOT), screen);
        stopPlayer(screen);
        synchronized (screen) {
            screen.removed = true;
            screen.nextSequence();
            ScreenView view = screen.view;
            screen.view = null;
            if (view != null) {
                plugin.renderer().retire(view.layout.tiles);
            }
        }
        forget(screen.url);
        refreshBacks();
        scheduleSave();
    }

    void changeUrl(VirtualScreen screen, String url, CommandSender requester) throws ScreenException {
        String old = screen.url;
        boolean playing = stopPlayer(screen);
        screen.url = url;
        screen.sourceWidth = 0;
        screen.sourceHeight = 0;
        screen.error = null;
        if (!old.equals(url)) {
            forget(old);
        }
        scheduleSave();
        if (screen.isVideo()) {
            showStill(screen);
            if (playing || settings().autoplayOnCreate) {
                play(screen, requester);
            }
        } else {
            renderImage(screen, false, requester);
        }
    }

    void refresh(VirtualScreen screen, CommandSender requester) throws ScreenException {
        if (screen.isVideo()) {
            boolean playing = stopPlayer(screen);
            plugin.videos().discard(screen.url);
            showStill(screen);
            if (playing) {
                play(screen, requester);
            }
        } else {
            plugin.images().invalidate(screen.url);
            renderImage(screen, true, requester);
        }
    }

    void contentChanged(VirtualScreen screen, CommandSender requester) {
        scheduleSave();
        if (screen.isVideo()) {
            VideoPlayer player = screen.player;
            if (player != null && player.active()) {
                player.requestReconfigure();
            } else {
                showStill(screen);
            }
        } else {
            renderImage(screen, false, requester);
        }
    }

    void move(VirtualScreen screen, Placement placement) throws ScreenException {
        synchronized (this) {
            checkSpace(screen, placement, screen.width, screen.height, screen.hologram);
            screen.world = placement.world;
            screen.x = placement.x;
            screen.y = placement.y;
            screen.z = placement.z;
            screen.facing = placement.facing;
            screen.rotation = placement.rotation;
        }
        placementChanged(screen);
    }

    void resize(VirtualScreen screen, double width, double height, FitMode mode, CommandSender requester)
            throws ScreenException {
        synchronized (this) {
            checkSpace(screen, new Placement(screen.world, screen.x, screen.y, screen.z, screen.facing, screen.rotation),
                    width, height, screen.hologram);
            screen.width = width;
            screen.height = height;
            screen.fitMode = mode;
        }
        contentChanged(screen, requester);
    }

    void hologram(VirtualScreen screen, boolean enabled) throws ScreenException {
        synchronized (this) {
            if (enabled) {
                checkSpace(screen, new Placement(screen.world, screen.x, screen.y, screen.z, screen.facing, screen.rotation),
                        screen.width, screen.height, true);
            }
            screen.hologram = enabled;
        }
        scheduleSave();
        synchronized (screen) {
            ScreenView view = screen.view;
            if (view != null) {
                screen.view = view(screen, view.layout, view.frame, screen.geometry, null);
            }
        }
        refreshBacks();
    }

    private void checkSpace(VirtualScreen moving, Placement placement, double width, double height, boolean hologram)
            throws ScreenException {
        Map<Facing, Set<Long>> mine = occupancy(placement.x, placement.y, placement.z, placement.facing, placement.rotation,
                width, height, hologram);
        for (VirtualScreen other : screens.values()) {
            if (other == moving || !other.world.equals(placement.world)) {
                continue;
            }
            Map<Facing, Set<Long>> theirs = occupancy(other.x, other.y, other.z, other.facing, other.rotation,
                    other.width, other.height, other.hologram);
            for (Map.Entry<Facing, Set<Long>> entry : mine.entrySet()) {
                Set<Long> blocks = theirs.get(entry.getKey());
                if (blocks != null && !Collections.disjoint(blocks, entry.getValue())) {
                    throw new ScreenException("screen-overlap", "name", other.name);
                }
            }
        }
    }

    static Map<Facing, Set<Long>> occupancy(int x, int y, int z, Facing facing, int rotation, double width, double height,
                                            boolean hologram) {
        Map<Facing, Set<Long>> result = new EnumMap<>(Facing.class);
        if (!hologram) {
            result.put(facing, footprint(x, y, z, facing, rotation, width, height));
            return result;
        }
        int columns = cells(width);
        int rows = cells(height);
        int[] tiles = new int[columns * rows];
        ScreenLayout layout = new ScreenLayout(new TileSet(columns, rows, tiles, tiles, tiles, new int[3]), "", x, y, z,
                facing, rotation);
        for (ScreenLayout turn : layout.turns()) {
            Set<Long> blocks = result.get(turn.facing);
            if (blocks == null) {
                blocks = new HashSet<>();
                result.put(turn.facing, blocks);
            }
            for (int i = 0; i < turn.blockX.length; i++) {
                blocks.add(key(turn.blockX[i], turn.blockY[i], turn.blockZ[i]));
            }
        }
        return result;
    }

    static Set<Long> footprint(int x, int y, int z, Facing facing, int rotation, double width, double height) {
        int[] right = facing.right(rotation & 3);
        int[] down = facing.down(rotation & 3);
        int columns = cells(width);
        int rows = cells(height);
        Set<Long> blocks = new HashSet<>(columns * rows * 2);
        for (int up = 0; up < rows; up++) {
            for (int column = 0; column < columns; column++) {
                blocks.add(key(x + column * right[0] - up * down[0], y + column * right[1] - up * down[1],
                        z + column * right[2] - up * down[2]));
            }
        }
        return blocks;
    }

    static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    private static int cells(double size) {
        int pixels = Math.max(1, (int) Math.round(size * RenderedFrame.TILE));
        return (pixels + RenderedFrame.TILE - 1) / RenderedFrame.TILE;
    }

    void placementChanged(VirtualScreen screen) {
        scheduleSave();
        synchronized (screen) {
            ScreenView view = screen.view;
            if (view == null || view.layout.matches(screen.world, screen.x, screen.y, screen.z, screen.facing, screen.rotation)) {
                return;
            }
            ScreenLayout layout = new ScreenLayout(view.layout.tiles, screen.world, screen.x, screen.y, screen.z,
                    screen.facing, screen.rotation);
            screen.view = view(screen, layout, view.frame, screen.geometry, view);
        }
        refreshBacks();
    }

    void refreshBacks() {
        backStamp.incrementAndGet();
        for (VirtualScreen screen : screens.values()) {
            synchronized (screen) {
                ScreenView view = screen.view;
                if (view == null) {
                    continue;
                }
                BackFace back = back(screen, view.layout, screen.geometry, view.back);
                if (back != view.back) {
                    screen.view = new ScreenView(view.layout, view.frame, back, view.turns);
                }
            }
        }
        plugin.renderer().wakeUp();
    }

    private ScreenView view(VirtualScreen screen, ScreenLayout layout, RenderedFrame frame, ScreenGeometry geometry,
                            ScreenView current) {
        ScreenLayout[] turns = null;
        if (screen.hologram) {
            turns = current != null && current.turns != null && current.layout == layout ? current.turns : layout.turns();
        }
        return new ScreenView(layout, frame, back(screen, layout, geometry, current == null ? null : current.back), turns);
    }

    private BackFace back(VirtualScreen screen, ScreenLayout layout, ScreenGeometry geometry, BackFace previous) {
        Settings settings = settings();
        if (!settings.solidBack || screen.hologram || layout.facing.vertical() || fullBackMap == BackFace.NONE
                || geometry == null || !layout.tiles.matches(geometry.columns, geometry.rows)) {
            return null;
        }
        int stamp = backStamp.get();
        byte color = backColor;
        boolean same = previous != null && previous.matches(layout, geometry.contentWidth, geometry.contentHeight, color);
        if (same && previous.stamp == stamp) {
            return previous;
        }
        boolean[] hidden = hidden(screen, layout);
        if (same && Arrays.equals(previous.hidden, hidden)) {
            previous.stamp = stamp;
            return previous;
        }
        return BackFace.compute(layout, geometry.contentWidth, geometry.contentHeight, fullBackMap, color, hidden, stamp);
    }

    private boolean[] hidden(VirtualScreen screen, ScreenLayout layout) {
        Facing facing = layout.facing;
        Facing behind = facing.opposite();
        Set<Long> taken = new HashSet<>();
        for (VirtualScreen other : screens.values()) {
            ScreenView view = other.view;
            if (other == screen || other.hologram || view == null || view.layout.facing != behind
                    || !view.layout.world.equals(layout.world)) {
                continue;
            }
            for (int i = 0; i < view.layout.blockX.length; i++) {
                taken.add(key(view.layout.blockX[i], view.layout.blockY[i], view.layout.blockZ[i]));
            }
        }
        boolean[] hidden = new boolean[layout.blockX.length];
        for (int i = 0; i < hidden.length; i++) {
            hidden[i] = taken.contains(key(layout.blockX[i] - facing.normalX, layout.blockY[i] - facing.normalY,
                    layout.blockZ[i] - facing.normalZ));
        }
        return hidden;
    }

    void rerender() {
        for (VirtualScreen screen : screens.values()) {
            if (screen.isVideo()) {
                VideoPlayer player = screen.player;
                if (player != null && player.active()) {
                    player.requestReconfigure();
                } else {
                    showStill(screen);
                }
            } else {
                renderImage(screen, false, null);
            }
        }
    }

    VideoPlayer play(VirtualScreen screen, CommandSender requester) throws ScreenException {
        synchronized (this) {
            VideoPlayer existing = screen.player;
            if (existing != null && existing.active()) {
                return existing;
            }
            Settings settings = settings();
            if (players.size() >= settings.maxConcurrentVideos) {
                throw new ScreenException("video-limit", "max", settings.maxConcurrentVideos);
            }
            VideoPlayer player = new VideoPlayer(plugin, screen, requester);
            screen.player = player;
            screen.error = null;
            screen.state = ScreenState.LOADING;
            players.add(player);
            player.start();
            return player;
        }
    }

    boolean stop(VirtualScreen screen) {
        if (!stopPlayer(screen)) {
            return false;
        }
        showStill(screen);
        return true;
    }

    private boolean stopPlayer(VirtualScreen screen) {
        VideoPlayer player;
        synchronized (this) {
            player = screen.player;
            screen.player = null;
            if (player != null) {
                players.remove(player);
            }
        }
        if (player == null) {
            return false;
        }
        player.stop();
        return true;
    }

    void playerFinished(VideoPlayer player) {
        synchronized (this) {
            players.remove(player);
            VirtualScreen screen = player.screen();
            if (screen.player == player) {
                screen.player = null;
            }
        }
    }

    void videoEnded(VirtualScreen screen, VideoPlayer player, CommandSender requester) {
        if (screen.player == player) {
            screen.state = ScreenState.STOPPED;
        }
        plugin.notify(requester, "video-ended", "name", screen.name);
    }

    void videoFailed(VirtualScreen screen, VideoPlayer player, MediaException error, CommandSender requester) {
        if (screen.player != player && screen.player != null) {
            return;
        }
        fail(screen, screen.sequence(), error, requester, "video-failed");
    }

    void renderImage(VirtualScreen screen, boolean refresh, CommandSender requester) {
        renderImage(screen, refresh, requester, settings().loadRetries);
    }

    private void renderImage(VirtualScreen screen, boolean refresh, CommandSender requester, int retries) {
        int sequence = screen.nextSequence();
        if (screen.view == null || screen.state == ScreenState.ERROR) {
            screen.state = ScreenState.LOADING;
        }
        String url = screen.url;
        CompletableFuture<byte[]> download = plugin.images().fetch(url, refresh);
        download.thenAcceptAsync(data -> {
            if (!screen.isCurrent(sequence) || screen.removed) {
                return;
            }
            try {
                Settings settings = settings();
                ImageLoader.Header header = ImageLoader.header(data, settings);
                screen.sourceWidth = header.width;
                screen.sourceHeight = header.height;
                ScreenGeometry geometry = screen.computeGeometry(settings);
                if (header.frames > 1) {
                    ImageAnimation animation = ImageAnimation.render(plugin, screen, sequence, data, header, geometry, settings);
                    if (publish(screen, sequence, geometry, animation.first(), ScreenState.READY) != null) {
                        screen.animated = true;
                        screen.frames = animation.frames();
                        screen.error = null;
                        animation.start();
                        scheduleSave();
                        plugin.notify(requester, "animation-ready", "name", screen.name, "source", header.width + "x" + header.height,
                                "frames", animation.frames(), "width", number(geometry.width), "height", number(geometry.height));
                    }
                    return;
                }
                int wantedWidth = (int) Math.ceil(geometry.destWidth * header.width / Math.max(1.0, geometry.cropWidth));
                int wantedHeight = (int) Math.ceil(geometry.destHeight * header.height / Math.max(1.0, geometry.cropHeight));
                ImageLoader.Decoded image = ImageLoader.decode(data, header, wantedWidth, wantedHeight);
                byte[][] tiles = FrameProcessor.renderImage(image.pixels, image.width, image.height, geometry, plugin.palette(), settings);
                if (publish(screen, sequence, geometry, tiles, ScreenState.READY) != null) {
                    screen.animated = false;
                    screen.frames = 0;
                    screen.error = null;
                    scheduleSave();
                    plugin.notify(requester, "image-ready", "name", screen.name, "source", header.width + "x" + header.height,
                            "width", number(geometry.width), "height", number(geometry.height));
                }
            } catch (MediaException e) {
                fail(screen, sequence, e, requester, "load-failed");
            }
        }, plugin.processing()).exceptionally(error -> {
            MediaException exception = MediaException.of(error);
            if (exception.reason.retryable && retries > 0 && screen.isCurrent(sequence) && !screen.removed) {
                try {
                    plugin.timer().schedule(() -> {
                        if (screen.isCurrent(sequence) && !screen.removed) {
                            renderImage(screen, true, requester, retries - 1);
                        }
                    }, settings().retryDelaySeconds, TimeUnit.SECONDS);
                    return null;
                } catch (RejectedExecutionException ignored) {
                    return null;
                }
            }
            fail(screen, sequence, exception, requester, "load-failed");
            return null;
        });
    }

    void showStill(VirtualScreen screen) {
        int sequence = screen.nextSequence();
        try {
            plugin.processing().execute(() -> {
                try {
                    Settings settings = settings();
                    ScreenGeometry geometry = screen.computeGeometry(settings);
                    MapPalette palette = plugin.palette();
                    byte background = FrameProcessor.background(palette, settings);
                    byte[][] tiles = FrameProcessor.solid(geometry, palette.matchRgb(0x000000), background);
                    ScreenState state = screen.state == ScreenState.ERROR ? ScreenState.ERROR : ScreenState.STOPPED;
                    publish(screen, sequence, geometry, tiles, state);
                } catch (MediaException e) {
                    fail(screen, sequence, e, null, "video-failed");
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void fail(VirtualScreen screen, int sequence, MediaException error, CommandSender requester, String key) {
        if (!screen.isCurrent(sequence) || screen.removed) {
            return;
        }
        screen.error = error;
        screen.state = ScreenState.ERROR;
        if (settings().debug) {
            plugin.getLogger().log(Level.WARNING, "Screen " + screen.name + " failed: " + error.reason, error.getCause());
        }
        if (error.reason == MediaException.Reason.FFMPEG_MISSING) {
            plugin.notify(requester, "ffmpeg-missing", "path", settings().ffmpegPath);
        } else {
            plugin.notify(requester, key, "name", screen.name, "reason", plugin.config().reason(error));
        }
        Settings settings = settings();
        if (settings.errorDisplay == Settings.ErrorDisplay.HIDE) {
            synchronized (screen) {
                if (!screen.isCurrent(sequence)) {
                    return;
                }
                ScreenView view = screen.view;
                screen.view = null;
                if (view != null) {
                    plugin.renderer().retire(view.layout.tiles);
                }
            }
            plugin.renderer().wakeUp();
        } else if (settings.errorDisplay == Settings.ErrorDisplay.PLACEHOLDER) {
            try {
                ScreenGeometry geometry = screen.computeGeometry(settings);
                MapPalette palette = plugin.palette();
                byte[][] tiles = FrameProcessor.solid(geometry, palette.matchRgb(settings.placeholderColor),
                        FrameProcessor.background(palette, settings));
                publish(screen, sequence, geometry, tiles, ScreenState.ERROR);
            } catch (MediaException ignored) {
            }
        }
    }

    RenderedFrame publish(VirtualScreen screen, int sequence, ScreenGeometry geometry, byte[][] tiles, ScreenState state)
            throws MediaException {
        RenderedFrame frame;
        boolean moved;
        synchronized (screen) {
            if (screen.removed || !screen.isCurrent(sequence)) {
                return null;
            }
            ScreenView current = screen.view;
            TileSet set = current != null && current.layout.tiles.matches(geometry.columns, geometry.rows)
                    ? current.layout.tiles : allocate(geometry.columns, geometry.rows);
            ScreenLayout layout = current != null && current.layout.tiles == set
                    && current.layout.matches(screen.world, screen.x, screen.y, screen.z, screen.facing, screen.rotation)
                    ? current.layout
                    : new ScreenLayout(set, screen.world, screen.x, screen.y, screen.z, screen.facing, screen.rotation);
            frame = RenderedFrame.next(current == null ? null : current.frame, set, tiles, versions.incrementAndGet());
            screen.geometry = geometry;
            screen.state = state;
            screen.view = view(screen, layout, frame, geometry, current);
            moved = current == null || current.layout != layout;
            if (current != null && current.layout.tiles != set) {
                plugin.renderer().retire(current.layout.tiles);
            }
        }
        if (moved) {
            refreshBacks();
        } else {
            plugin.renderer().wakeUp();
        }
        return frame;
    }

    private TileSet allocate(int columns, int rows) throws MediaException {
        int count = columns * rows;
        int[] maps = mapIds.allocate(count + 3);
        if (maps == null) {
            throw new MediaException(MediaException.Reason.MAP_IDS);
        }
        int[] entities = entityIds.allocate(count);
        int[] backs = entities == null ? null : backIds.allocate(count);
        if (backs == null) {
            mapIds.release(maps);
            if (entities != null) {
                entityIds.release(entities);
            }
            throw new MediaException(MediaException.Reason.MAP_IDS);
        }
        return new TileSet(columns, rows, Arrays.copyOf(maps, count), entities, backs,
                Arrays.copyOfRange(maps, count, count + 3));
    }

    void release(TileSet set) {
        mapIds.release(set.mapIds);
        mapIds.release(set.backMapIds);
        entityIds.release(set.entityIds);
        backIds.release(set.backEntityIds);
    }

    private void forget(String url) {
        for (VirtualScreen other : screens.values()) {
            if (other.url.equals(url)) {
                return;
            }
        }
        plugin.images().invalidate(url);
        plugin.videos().discard(url);
    }

    void load() {
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            File backup = new File(file.getParentFile(), "screens.yml.broken-" + System.currentTimeMillis());
            plugin.getLogger().log(Level.SEVERE, "screens.yml could not be read and was copied to " + backup.getName(), e);
            try {
                Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
            return;
        }
        ConfigurationSection root = yaml.getConfigurationSection("screens");
        if (root == null) {
            return;
        }
        Settings settings = settings();
        for (String name : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(name);
            if (section == null || !NAME.matcher(name).matches()) {
                plugin.getLogger().warning("Skipping invalid screen entry '" + name + "' in screens.yml.");
                continue;
            }
            try {
                VirtualScreen screen = VirtualScreen.read(name, section, settings);
                if (screen.facing.vertical() && !plugin.adapter().supportsVerticalFacing()) {
                    plugin.getLogger().warning("Skipping screen '" + name + "': floor and ceiling screens require 1.13+.");
                    continue;
                }
                screens.put(name.toLowerCase(Locale.ROOT), screen);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Skipping screen '" + name + "' in screens.yml: " + e.getMessage());
            }
        }
        for (VirtualScreen screen : screens.values()) {
            if (screen.isVideo()) {
                showStill(screen);
                if (settings.autoplayOnStartup) {
                    try {
                        play(screen, null);
                    } catch (ScreenException e) {
                        plugin.getLogger().warning("Video '" + screen.name + "' was not started: limit of simultaneous videos reached.");
                    }
                }
            } else {
                renderImage(screen, false, null);
            }
        }
        plugin.getLogger().info("Loaded " + screens.size() + " screen(s).");
    }

    void scheduleSave() {
        if (saveQueued.compareAndSet(false, true)) {
            try {
                plugin.timer().schedule(() -> {
                    saveQueued.set(false);
                    saveNow();
                }, 1, TimeUnit.SECONDS);
            } catch (RejectedExecutionException e) {
                saveQueued.set(false);
            }
        }
    }

    void saveNow() {
        synchronized (saveLock) {
            YamlConfiguration yaml = new YamlConfiguration();
            ConfigurationSection root = yaml.createSection("screens");
            for (VirtualScreen screen : sorted()) {
                screen.write(root.createSection(screen.name));
            }
            File directory = file.getParentFile();
            if (!directory.isDirectory() && !directory.mkdirs()) {
                plugin.getLogger().warning("Could not create " + directory.getAbsolutePath());
                return;
            }
            File temp = new File(directory, "screens.yml.tmp");
            try {
                Files.write(temp.toPath(), yaml.saveToString().getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Could not save screens.yml", e);
            }
        }
    }

    void shutdown() {
        List<VideoPlayer> active = new ArrayList<>(players);
        for (VirtualScreen screen : screens.values()) {
            VideoPlayer player = screen.player;
            if (player != null && !active.contains(player)) {
                active.add(player);
            }
        }
        for (VideoPlayer player : active) {
            player.stop();
        }
        for (VideoPlayer player : active) {
            player.join(3000);
        }
        players.clear();
        saveNow();
    }

    static String number(double value) {
        String text = String.format(Locale.ROOT, "%.2f", value);
        if (text.contains(".")) {
            text = text.replaceAll("0+$", "");
            if (text.endsWith(".")) {
                text = text.substring(0, text.length() - 1);
            }
        }
        return text;
    }
}
