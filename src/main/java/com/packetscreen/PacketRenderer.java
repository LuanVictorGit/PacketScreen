package com.packetscreen;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;

final class PacketRenderer implements Listener, PacketListener {

    private static final double HIDE_MARGIN = 4.0;

    private static final class Position {
        final String world;
        final double x;
        final double y;
        final double z;

        Position(String world, double x, double y, double z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final class Shown {
        ScreenLayout layout;
        TileSet tiles;
        int[] sent;
        boolean[] spawned;
        boolean[] backSpawned;
        BackFace back;
        boolean backData;
        int side = -1;
        int cursor;

        Shown(ScreenLayout layout) {
            reset(layout);
        }

        void reset(ScreenLayout layout) {
            this.layout = layout;
            this.tiles = layout.tiles;
            this.sent = new int[tiles.size()];
            Arrays.fill(sent, -1);
            this.spawned = new boolean[tiles.size()];
            this.backSpawned = new boolean[tiles.size()];
            this.back = null;
            this.backData = false;
            this.cursor = 0;
        }

        void relayout(ScreenLayout layout) {
            this.layout = layout;
            Arrays.fill(spawned, false);
            Arrays.fill(backSpawned, false);
        }
    }

    private static final class Viewer {
        final User user;
        final AtomicBoolean reset = new AtomicBoolean();
        final Map<VirtualScreen, Shown> shown = new HashMap<>();
        volatile Position position;
        boolean waiting;
        int fullBack = -1;
        double bytes;
        double packets;
        long refilled;

        Viewer(User user) {
            this.user = user;
        }

        void refill(long now, Settings settings) {
            if (refilled == 0) {
                bytes = settings.bytesPerSecondPerPlayer;
                packets = settings.packetsPerSecondPerPlayer;
            } else {
                double seconds = (now - refilled) / 1_000_000_000.0;
                bytes = Math.min(settings.bytesPerSecondPerPlayer, bytes + seconds * settings.bytesPerSecondPerPlayer);
                packets = Math.min(settings.packetsPerSecondPerPlayer, packets + seconds * settings.packetsPerSecondPerPlayer);
            }
            refilled = now;
        }
    }

    private final PacketScreenPlugin plugin;
    private final Map<UUID, Viewer> viewers = new ConcurrentHashMap<>();
    private final Map<UUID, Object> positionTasks = new ConcurrentHashMap<>();
    private final Queue<TileSet> retired = new ConcurrentLinkedQueue<>();
    private final List<TileSet> releasing = new ArrayList<>();
    private final AtomicBoolean respawnAll = new AtomicBoolean();
    private volatile boolean running;
    private volatile Thread thread;
    private PacketListenerCommon packetListener;
    private Object positionTask;
    private double globalBytes;
    private long globalRefilled;
    private long lastVisibility;

    PacketRenderer(PacketScreenPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        running = true;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        packetListener = PacketEvents.getAPI().getEventManager().registerListener(this, PacketListenerPriority.LOWEST);
        TaskScheduler scheduler = plugin.scheduler();
        if (scheduler.isFolia()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                startTracking(player);
            }
        } else {
            positionTask = scheduler.repeatGlobal(() -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    track(player, player.getLocation());
                }
            }, plugin.config().settings().positionIntervalTicks);
        }
        Thread loop = new Thread(this::loop, "PacketScreen-Network");
        loop.setDaemon(true);
        thread = loop;
        loop.start();
    }

    void stop() {
        running = false;
        if (packetListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
            packetListener = null;
        }
        HandlerList.unregisterAll(this);
        TaskScheduler scheduler = plugin.scheduler();
        scheduler.cancel(positionTask);
        for (Object task : positionTasks.values()) {
            scheduler.cancel(task);
        }
        positionTasks.clear();
        Thread loop = thread;
        if (loop != null) {
            LockSupport.unpark(loop);
            try {
                loop.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        boolean silent = plugin.config().settings().silentPackets;
        for (Viewer viewer : viewers.values()) {
            try {
                boolean wrote = false;
                for (Shown shown : viewer.shown.values()) {
                    wrote |= destroy(viewer, shown, true, silent);
                }
                if (wrote) {
                    viewer.user.flushPackets();
                }
            } catch (RuntimeException e) {
                debug("Could not remove screens from " + viewer.user.getName(), e);
            }
            viewer.shown.clear();
        }
        viewers.clear();
    }

    void wakeUp() {
        Thread loop = thread;
        if (loop != null) {
            LockSupport.unpark(loop);
        }
    }

    void retire(TileSet tiles) {
        retired.add(tiles);
    }

    void respawnAll() {
        respawnAll.set(true);
        wakeUp();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (plugin.scheduler().isFolia()) {
            startTracking(player);
        } else {
            track(player, player.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        viewers.remove(id);
        plugin.scheduler().cancel(positionTasks.remove(id));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        track(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        track(event.getPlayer(), event.getRespawnLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        track(event.getPlayer(), event.getPlayer().getLocation());
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type != PacketType.Play.Server.RESPAWN && type != PacketType.Play.Server.JOIN_GAME
                && type != PacketType.Play.Server.CONFIGURATION_START) {
            return;
        }
        UUID id = event.getUser().getUUID();
        Viewer viewer = id == null ? null : viewers.get(id);
        if (viewer != null) {
            viewer.position = null;
            viewer.reset.set(true);
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) {
            return;
        }
        WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
        if (plugin.screens().isVirtualEntity(packet.getEntityId())) {
            event.setCancelled(true);
        }
    }

    private void startTracking(Player player) {
        Object task = plugin.scheduler().repeatFor(player, () -> track(player, player.getLocation()),
                plugin.config().settings().positionIntervalTicks);
        plugin.scheduler().cancel(positionTasks.put(player.getUniqueId(), task));
    }

    private void track(Player player, Location location) {
        if (location == null || location.getWorld() == null || !player.isOnline()) {
            return;
        }
        Viewer viewer = viewers.get(player.getUniqueId());
        if (viewer == null) {
            User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
            if (user == null) {
                return;
            }
            viewer = new Viewer(user);
            Viewer existing = viewers.putIfAbsent(player.getUniqueId(), viewer);
            if (existing != null) {
                viewer = existing;
            } else {
                trace("Tracking " + player.getName() + " (client " + user.getClientVersion().getReleaseName() + ")");
            }
        }
        viewer.position = new Position(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
    }

    private void loop() {
        while (running) {
            Settings settings = plugin.config().settings();
            try {
                tick(System.nanoTime(), settings);
            } catch (Throwable error) {
                plugin.getLogger().log(Level.WARNING, "Packet renderer tick failed", error);
            }
            if (running) {
                LockSupport.parkNanos(this, settings.tickIntervalMs * 1_000_000L);
            }
        }
    }

    private void tick(long now, Settings settings) {
        if (globalRefilled == 0) {
            globalBytes = settings.bytesPerSecondTotal;
        } else {
            double seconds = (now - globalRefilled) / 1_000_000_000.0;
            globalBytes = Math.min(settings.bytesPerSecondTotal, globalBytes + seconds * settings.bytesPerSecondTotal);
        }
        globalRefilled = now;
        boolean visibility = now - lastVisibility >= settings.trackerIntervalMs * 1_000_000L;
        if (visibility) {
            lastVisibility = now;
        }
        boolean respawn = respawnAll.getAndSet(false);
        Collection<VirtualScreen> screens = plugin.screens().all();
        Map<VirtualScreen, Integer> counts = visibility ? new HashMap<>() : null;
        VersionAdapter adapter = plugin.adapter();
        for (Viewer viewer : viewers.values()) {
            try {
                process(viewer, screens, visibility, respawn, now, settings, adapter, counts);
            } catch (RuntimeException error) {
                debug("Could not update " + viewer.user.getName(), error);
            }
        }
        if (counts != null) {
            for (VirtualScreen screen : screens) {
                Integer count = counts.get(screen);
                screen.viewers = count == null ? 0 : count;
            }
        }
        releaseRetired();
    }

    private void process(Viewer viewer, Collection<VirtualScreen> screens, boolean visibility, boolean respawn, long now,
                         Settings settings, VersionAdapter adapter, Map<VirtualScreen, Integer> counts) {
        if (viewer.reset.getAndSet(false)) {
            viewer.shown.clear();
            viewer.fullBack = -1;
        }
        if (viewer.user.getConnectionState() != ConnectionState.PLAY) {
            if (!viewer.waiting) {
                viewer.waiting = true;
                trace("Waiting for " + viewer.user.getName() + " to enter the play state");
            }
            return;
        }
        viewer.waiting = false;
        boolean silent = settings.silentPackets;
        boolean wrote = false;
        Position position = viewer.position;
        Iterator<Map.Entry<VirtualScreen, Shown>> iterator = viewer.shown.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<VirtualScreen, Shown> entry = iterator.next();
            VirtualScreen screen = entry.getKey();
            ScreenView view = screen.view;
            boolean keep = !respawn && !screen.removed && view != null && position != null
                    && (!visibility || inRange(screen, view.layout, position, HIDE_MARGIN));
            if (!keep) {
                wrote |= destroy(viewer, entry.getValue(), true, silent);
                iterator.remove();
                trace("Hiding " + screen.name + " from " + viewer.user.getName());
            }
        }
        if (visibility && position != null) {
            for (VirtualScreen screen : screens) {
                ScreenView view = screen.view;
                if (view != null && !screen.removed && !viewer.shown.containsKey(screen) && inRange(screen, view.layout, position, 0)) {
                    viewer.shown.put(screen, new Shown(view.layout));
                    trace("Showing " + screen.name + " to " + viewer.user.getName());
                }
            }
        }
        viewer.refill(now, settings);
        for (Map.Entry<VirtualScreen, Shown> entry : viewer.shown.entrySet()) {
            VirtualScreen screen = entry.getKey();
            Shown shown = entry.getValue();
            ScreenView view = screen.view;
            if (view == null) {
                continue;
            }
            if (counts != null) {
                counts.merge(screen, 1, Integer::sum);
            }
            ScreenLayout target = view.layout;
            if (view.turns != null && position != null) {
                shown.side = view.layout.side(shown.side, position.x, position.z);
                target = view.turns[shown.side];
            }
            if (target.tiles != shown.tiles) {
                wrote |= destroy(viewer, shown, true, silent);
                shown.reset(target);
            } else if (target != shown.layout) {
                wrote |= destroy(viewer, shown, true, silent);
                shown.relayout(target);
            }
            if (view.back != shown.back) {
                wrote |= destroy(viewer, shown, false, silent);
                shown.back = view.back;
                shown.backData = false;
            }
            wrote |= update(viewer, shown, view.frame, screen.isVideo() || screen.animated, adapter, silent);
            wrote |= updateBack(viewer, shown, adapter, silent);
        }
        if (wrote) {
            viewer.user.flushPackets();
        }
    }

    private boolean update(Viewer viewer, Shown shown, RenderedFrame frame, boolean atomic, VersionAdapter adapter,
                           boolean silent) {
        int count = frame.versions.length;
        boolean wrote = false;
        boolean allowData = !atomic || canSend(viewer);
        for (int i = 0; i < count; i++) {
            int tile = atomic ? i : (shown.cursor + i) % count;
            if (allowData && shown.sent[tile] != frame.versions[tile]) {
                if (!atomic && !canSend(viewer)) {
                    allowData = false;
                    shown.cursor = tile;
                } else {
                    spend(viewer, writeMap(viewer, shown.tiles.mapIds[tile], frame, tile, shown.sent[tile], silent));
                    shown.sent[tile] = frame.versions[tile];
                    wrote = true;
                }
            }
            if (!shown.spawned[tile] && shown.sent[tile] >= 0) {
                ScreenLayout layout = shown.layout;
                int entity = layout.tiles.entityIds[tile];
                write(viewer, adapter.spawn(entity, layout.blockX[tile], layout.blockY[tile], layout.blockZ[tile], layout.facing), silent);
                write(viewer, adapter.metadata(entity, layout.tiles.mapIds[tile], layout.rotation), silent);
                viewer.packets -= 2;
                shown.spawned[tile] = true;
                wrote = true;
            }
        }
        return wrote;
    }

    private boolean updateBack(Viewer viewer, Shown shown, VersionAdapter adapter, boolean silent) {
        BackFace back = shown.back;
        if (back == null || back.layout.tiles != shown.tiles) {
            return false;
        }
        boolean wrote = false;
        ScreenManager screens = plugin.screens();
        int version = screens.fullBackVersion();
        if (viewer.fullBack != version && back.usesFull()) {
            if (!canSend(viewer)) {
                return false;
            }
            spend(viewer, writeData(viewer, back.fullMap, screens.fullBackData(), silent));
            viewer.fullBack = version;
            wrote = true;
        }
        if (!shown.backData) {
            if (!canSend(viewer)) {
                return wrote;
            }
            for (int i = 0; i < back.partialIds.length; i++) {
                if (back.partialData[i] != null) {
                    spend(viewer, writeData(viewer, back.partialIds[i], back.partialData[i], silent));
                    wrote = true;
                }
            }
            shown.backData = true;
        }
        ScreenLayout layout = back.layout;
        Facing facing = layout.facing;
        Facing behind = facing.opposite();
        int rotation = back.rotation();
        for (int tile = 0; tile < back.maps.length; tile++) {
            if (back.maps[tile] == BackFace.NONE || shown.backSpawned[tile] || !shown.spawned[tile]) {
                continue;
            }
            int entity = layout.tiles.backEntityIds[tile];
            write(viewer, adapter.spawn(entity, layout.blockX[tile] - facing.normalX, layout.blockY[tile] - facing.normalY,
                    layout.blockZ[tile] - facing.normalZ, behind), silent);
            write(viewer, adapter.metadata(entity, back.maps[tile], rotation), silent);
            viewer.packets -= 2;
            shown.backSpawned[tile] = true;
            wrote = true;
        }
        return wrote;
    }

    private void spend(Viewer viewer, int size) {
        viewer.bytes -= size;
        viewer.packets -= 1;
        globalBytes -= size;
    }

    private boolean canSend(Viewer viewer) {
        return viewer.bytes > 0 && viewer.packets > 0 && globalBytes > 0;
    }

    private int writeMap(Viewer viewer, int mapId, RenderedFrame frame, int tile, int sent, boolean silent) {
        byte[] delta = frame.deltas[tile];
        if (delta == null || sent < 0 || sent != frame.previousVersions[tile]) {
            return writeData(viewer, mapId, frame.data[tile], silent);
        }
        int r = tile * 4;
        write(viewer, new WrapperPlayServerMapData(mapId, (byte) 0, false, false, null,
                frame.rects[r + 2], frame.rects[r + 3], frame.rects[r], frame.rects[r + 1], delta), silent);
        return delta.length + 16;
    }

    private int writeData(Viewer viewer, int mapId, byte[] data, boolean silent) {
        write(viewer, new WrapperPlayServerMapData(mapId, (byte) 0, false, false, null,
                RenderedFrame.TILE, RenderedFrame.TILE, 0, 0, data), silent);
        return RenderedFrame.TILE_AREA + 16;
    }

    private boolean destroy(Viewer viewer, Shown shown, boolean front, boolean silent) {
        int count = 0;
        for (int tile = 0; tile < shown.spawned.length; tile++) {
            if (front && shown.spawned[tile]) {
                count++;
            }
            if (shown.backSpawned[tile]) {
                count++;
            }
        }
        if (count == 0) {
            return false;
        }
        int[] ids = new int[count];
        int index = 0;
        for (int tile = 0; tile < shown.spawned.length; tile++) {
            if (front && shown.spawned[tile]) {
                ids[index++] = shown.tiles.entityIds[tile];
                shown.spawned[tile] = false;
            }
            if (shown.backSpawned[tile]) {
                ids[index++] = shown.tiles.backEntityIds[tile];
                shown.backSpawned[tile] = false;
            }
        }
        write(viewer, new WrapperPlayServerDestroyEntities(ids), silent);
        return true;
    }

    private static void write(Viewer viewer, PacketWrapper<?> packet, boolean silent) {
        if (silent) {
            viewer.user.writePacketSilently(packet);
        } else {
            viewer.user.writePacket(packet);
        }
    }

    private static boolean inRange(VirtualScreen screen, ScreenLayout layout, Position position, double margin) {
        if (!layout.world.equals(position.world)) {
            return false;
        }
        double dx = layout.centerX - position.x;
        double dy = layout.centerY - position.y;
        double dz = layout.centerZ - position.z;
        double range = screen.range + layout.radius + margin;
        return dx * dx + dy * dy + dz * dz <= range * range;
    }

    private void releaseRetired() {
        TileSet tiles;
        while ((tiles = retired.poll()) != null) {
            releasing.add(tiles);
        }
        if (releasing.isEmpty()) {
            return;
        }
        Iterator<TileSet> iterator = releasing.iterator();
        outer:
        while (iterator.hasNext()) {
            TileSet candidate = iterator.next();
            for (Viewer viewer : viewers.values()) {
                for (Shown shown : viewer.shown.values()) {
                    if (shown.tiles == candidate) {
                        continue outer;
                    }
                }
            }
            for (VirtualScreen screen : plugin.screens().all()) {
                ScreenView view = screen.view;
                if (view != null && view.layout.tiles == candidate) {
                    continue outer;
                }
            }
            iterator.remove();
            plugin.screens().release(candidate);
        }
    }

    private void trace(String message) {
        if (plugin.config().settings().debug) {
            plugin.getLogger().info("[Debug] " + message);
        }
    }

    private void debug(String message, Throwable error) {
        if (plugin.config().settings().debug) {
            plugin.getLogger().log(Level.WARNING, message, error);
        }
    }
}
