package com.packetscreen;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class CommandManager implements TabExecutor {

    private static final Set<String> HEAVY = new HashSet<>(Arrays.asList(
            "create", "video", "url", "refresh", "resize", "scale", "aspect", "resolution", "web"));
    private static final List<String> FACINGS = Arrays.asList("north", "south", "east", "west", "up", "down");
    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>(Arrays.asList("png", "jpg", "jpeg", "bmp", "webp"));
    private static final Set<String> VIDEO_EXTENSIONS = new HashSet<>(Arrays.asList(
            "mp4", "m4v", "mov", "webm", "mkv", "avi", "flv", "ogv", "ogg", "ts", "mpg", "mpeg"));

    private final PacketScreenPlugin plugin;
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();

    CommandManager(PacketScreenPlugin plugin) {
        this.plugin = plugin;
    }

    private Settings settings() {
        return plugin.config().settings();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ConfigurationManager config = plugin.config();
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender, label);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!Settings.SUBCOMMANDS.contains(sub)) {
            config.send(sender, "unknown-command", "label", label);
            return true;
        }
        if (!config.hasPermission(sender, sub)) {
            config.send(sender, "no-permission");
            return true;
        }
        if (HEAVY.contains(sub) && onCooldown(sender)) {
            config.send(sender, "cooldown");
            return true;
        }
        try {
            execute(sender, label, sub, args);
        } catch (ScreenException e) {
            if (e.key.equals("usage")) {
                config.send(sender, "usage." + sub, "label", label);
            } else {
                config.send(sender, e.key, e.placeholders);
            }
        }
        return true;
    }

    private void execute(CommandSender sender, String label, String sub, String[] args) throws ScreenException {
        ConfigurationManager config = plugin.config();
        ScreenManager screens = plugin.screens();
        Settings settings = settings();
        switch (sub) {
            case "create":
            case "video": {
                boolean sized = args.length == 5 || args.length >= 9 || args.length == 8 && Facing.parse(args[6]) == null;
                int start = sized ? 5 : 3;
                int coordinates = args.length - start;
                if (args.length < 3 || coordinates != 0 && (coordinates < 3 || coordinates > 5)) {
                    throw new ScreenException("usage");
                }
                String name = name(args[1]);
                if (screens.get(name) != null) {
                    throw new ScreenException("screen-exists", "name", name);
                }
                String url = url(args[2]);
                ScreenType type = sub.equals("video") ? ScreenType.VIDEO : ScreenType.IMAGE;
                checkKind(type, url, label, name);
                double width = settings.defaultWidth;
                double height = settings.defaultHeight;
                if (sized) {
                    width = size(args[3]);
                    height = size(args[4]);
                }
                checkSize(width, height);
                ScreenManager.Placement placement = coordinates > 0 ? placement(sender, args, start) : target(sender);
                VirtualScreen screen = screens.create(sender, name, type, url, placement, width, height);
                if (type == ScreenType.IMAGE) {
                    config.send(sender, "screen-created", "name", name);
                } else {
                    config.send(sender, "video-created", "name", name);
                    if (settings.autoplayOnCreate) {
                        screens.play(screen, sender);
                        config.send(sender, "video-loading", "name", name);
                    }
                }
                return;
            }
            case "url": {
                expect(args, 3);
                VirtualScreen screen = screen(args[1]);
                String url = url(args[2]);
                checkKind(screen.type, url, label, screen.name);
                screens.changeUrl(screen, url, sender);
                config.send(sender, "url-changed", "name", screen.name);
                return;
            }
            case "refresh": {
                expect(args, 2);
                VirtualScreen screen = screen(args[1]);
                screens.refresh(screen, sender);
                config.send(sender, "refreshing", "name", screen.name);
                return;
            }
            case "resize": {
                if (args.length != 4 && args.length != 5) {
                    throw new ScreenException("usage");
                }
                VirtualScreen screen = screen(args[1]);
                double width = size(args[2]);
                double height = size(args[3]);
                checkSize(width, height);
                FitMode mode = screen.fitMode;
                if (args.length == 5) {
                    mode = FitMode.parse(args[4]);
                    if (mode == null) {
                        throw new ScreenException("invalid-fit-mode", "value", args[4]);
                    }
                }
                screens.resize(screen, width, height, mode, sender);
                ScreenGeometry geometry = screen.computeGeometry(settings);
                config.send(sender, "resized", "name", screen.name, "width", ScreenManager.number(geometry.width),
                        "height", ScreenManager.number(geometry.height), "mode", mode.name());
                return;
            }
            case "scale": {
                expect(args, 3);
                VirtualScreen screen = screen(args[1]);
                double factor = size(args[2]);
                double width = screen.width * factor;
                double height = screen.height * factor;
                checkSize(width, height);
                screens.resize(screen, width, height, screen.fitMode, sender);
                ScreenGeometry geometry = screen.computeGeometry(settings);
                config.send(sender, "scaled", "name", screen.name, "factor", ScreenManager.number(factor),
                        "width", ScreenManager.number(geometry.width), "height", ScreenManager.number(geometry.height));
                return;
            }
            case "aspect": {
                expect(args, 3);
                VirtualScreen screen = screen(args[1]);
                String value = args[2].toLowerCase(Locale.ROOT);
                if (!value.equals("preserve") && !value.equals("free")) {
                    throw new ScreenException("invalid-aspect");
                }
                screen.preserveAspect = value.equals("preserve");
                screens.contentChanged(screen, sender);
                config.send(sender, screen.preserveAspect ? "aspect-preserve" : "aspect-free", "name", screen.name);
                return;
            }
            case "resolution": {
                if (args.length != 3 && args.length != 4) {
                    throw new ScreenException("usage");
                }
                VirtualScreen screen = screen(args[1]);
                if (args.length == 3 && args[2].equalsIgnoreCase("auto")) {
                    screen.processingWidth = 0;
                    screen.processingHeight = 0;
                    screens.contentChanged(screen, sender);
                    config.send(sender, "resolution-auto", "name", screen.name);
                    return;
                }
                int width = whole(args[2], "invalid-resolution");
                int height;
                if (args.length == 4) {
                    height = whole(args[3], "invalid-resolution");
                } else {
                    ScreenGeometry geometry = screen.computeGeometry(settings);
                    height = (int) Math.round(width * (double) geometry.contentHeight / geometry.contentWidth);
                }
                if (width < 16 || height < 16 || width > settings.maxProcessingWidth || height > settings.maxProcessingHeight) {
                    throw new ScreenException("resolution-out-of-bounds", "max-width", settings.maxProcessingWidth,
                            "max-height", settings.maxProcessingHeight);
                }
                screen.processingWidth = width;
                screen.processingHeight = height;
                screens.contentChanged(screen, sender);
                config.send(sender, "resolution-set", "name", screen.name, "width", width, "height", height);
                return;
            }
            case "fps": {
                expect(args, 3);
                VirtualScreen screen = video(screen(args[1]));
                int fps;
                try {
                    fps = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    throw new ScreenException("invalid-fps", "max", settings.maxFps);
                }
                if (fps < 1 || fps > settings.maxFps) {
                    throw new ScreenException("invalid-fps", "max", settings.maxFps);
                }
                screen.fps = fps;
                screens.contentChanged(screen, sender);
                config.send(sender, "fps-set", "name", screen.name, "fps", fps);
                return;
            }
            case "play": {
                expect(args, 2);
                VirtualScreen screen = video(screen(args[1]));
                VideoPlayer player = screen.player;
                if (player != null && player.active()) {
                    if (player.isPaused()) {
                        player.resume(sender);
                        config.send(sender, "video-resumed", "name", screen.name);
                    } else {
                        config.send(sender, "video-already-playing", "name", screen.name);
                    }
                    return;
                }
                screens.play(screen, sender);
                config.send(sender, "video-loading", "name", screen.name);
                return;
            }
            case "pause": {
                expect(args, 2);
                VirtualScreen screen = video(screen(args[1]));
                VideoPlayer player = screen.player;
                if (player == null || !player.active() || player.isPaused()) {
                    throw new ScreenException("video-not-playing", "name", screen.name);
                }
                player.pause();
                config.send(sender, "video-paused", "name", screen.name);
                return;
            }
            case "stop": {
                expect(args, 2);
                VirtualScreen screen = video(screen(args[1]));
                if (!screens.stop(screen)) {
                    throw new ScreenException("video-not-playing", "name", screen.name);
                }
                config.send(sender, "video-stopped", "name", screen.name);
                return;
            }
            case "restart": {
                expect(args, 2);
                VirtualScreen screen = video(screen(args[1]));
                VideoPlayer player = screen.player;
                if (player != null && player.active()) {
                    player.requestRestart(sender);
                } else {
                    screens.play(screen, sender);
                    config.send(sender, "video-loading", "name", screen.name);
                }
                return;
            }
            case "loop": {
                expect(args, 3);
                VirtualScreen screen = video(screen(args[1]));
                String value = args[2].toLowerCase(Locale.ROOT);
                if (!value.equals("true") && !value.equals("false")) {
                    throw new ScreenException("invalid-boolean");
                }
                screen.loop = value.equals("true");
                screens.scheduleSave();
                config.send(sender, "loop-set", "name", screen.name, "loop", config.message(screen.loop ? "values.yes" : "values.no"));
                return;
            }
            case "move": {
                if (args.length != 2 && (args.length < 5 || args.length > 7)) {
                    throw new ScreenException("usage");
                }
                VirtualScreen screen = screen(args[1]);
                ScreenManager.Placement placement = args.length == 2 ? target(sender) : placement(sender, args, 2);
                screens.move(screen, placement);
                config.send(sender, "moved", "name", screen.name, "world", placement.world, "x", placement.x,
                        "y", placement.y, "z", placement.z, "facing", placement.facing.name());
                return;
            }
            case "rotate": {
                expect(args, 3);
                VirtualScreen screen = screen(args[1]);
                int degrees = whole(args[2], "invalid-rotation");
                if (degrees % 90 != 0) {
                    throw new ScreenException("invalid-rotation");
                }
                screens.move(screen, new ScreenManager.Placement(screen.world, screen.x, screen.y, screen.z, screen.facing,
                        Math.floorMod(degrees / 90, 4)));
                config.send(sender, "rotated", "name", screen.name, "rotation", screen.rotation * 90);
                return;
            }
            case "hologram": {
                expect(args, 3);
                VirtualScreen screen = screen(args[1]);
                String value = args[2].toLowerCase(Locale.ROOT);
                if (!value.equals("true") && !value.equals("false")) {
                    throw new ScreenException("invalid-boolean");
                }
                screens.hologram(screen, value.equals("true"));
                config.send(sender, "hologram-set", "name", screen.name,
                        "value", config.message(screen.hologram ? "values.yes" : "values.no"));
                return;
            }
            case "range": {
                expect(args, 3);
                VirtualScreen screen = screen(args[1]);
                double range;
                try {
                    range = Double.parseDouble(args[2].replace(',', '.'));
                } catch (NumberFormatException e) {
                    throw new ScreenException("invalid-range", "max", ScreenManager.number(settings.maxViewDistance));
                }
                if (Double.isNaN(range) || range < 4 || range > settings.maxViewDistance) {
                    throw new ScreenException("invalid-range", "max", ScreenManager.number(settings.maxViewDistance));
                }
                screen.range = range;
                screens.scheduleSave();
                config.send(sender, "range-set", "name", screen.name, "range", ScreenManager.number(range));
                return;
            }
            case "remove": {
                expect(args, 2);
                VirtualScreen screen = screen(args[1]);
                screens.remove(screen);
                config.send(sender, "screen-removed", "name", screen.name);
                return;
            }
            case "list": {
                List<VirtualScreen> list = screens.sorted();
                if (list.isEmpty()) {
                    config.send(sender, "list.empty");
                    return;
                }
                config.send(sender, "list.header", "count", list.size());
                for (VirtualScreen screen : list) {
                    ScreenGeometry geometry = screen.computeGeometry(settings);
                    config.send(sender, "list.entry", "name", screen.name, "type", type(screen), "state", state(screen),
                            "world", screen.world, "x", screen.x, "y", screen.y, "z", screen.z,
                            "width", ScreenManager.number(geometry.width), "height", ScreenManager.number(geometry.height));
                }
                return;
            }
            case "info": {
                expect(args, 2);
                info(sender, screen(args[1]));
                return;
            }
            case "web": {
                if (args.length == 2 && args[1].equalsIgnoreCase("revoke")) {
                    plugin.panel().revoke(sender);
                    config.send(sender, "web-revoked");
                    return;
                }
                expect(args, 1);
                sendLink(sender, plugin.panel().link(sender, label), settings.webTokenMinutes);
                return;
            }
            case "reload": {
                try {
                    plugin.reload();
                    config.send(sender, "reload-done");
                } catch (Exception e) {
                    plugin.config().send(sender, "reload-failed", "error", String.valueOf(e.getMessage()));
                }
                return;
            }
            default:
                config.send(sender, "unknown-command", "label", label);
        }
    }

    private void help(CommandSender sender, String label) {
        ConfigurationManager config = plugin.config();
        config.send(sender, "help.header", "version", plugin.getDescription().getVersion(), "label", label);
        for (String sub : Settings.SUBCOMMANDS) {
            if (config.has("help.commands." + sub) && config.hasPermission(sender, sub)) {
                config.send(sender, "help.commands." + sub, "label", label);
            }
        }
        config.send(sender, "help.footer", "label", label);
    }

    private void info(CommandSender sender, VirtualScreen screen) {
        ConfigurationManager config = plugin.config();
        Settings settings = settings();
        ScreenGeometry geometry = screen.computeGeometry(settings);
        ScreenView view = screen.view;
        int columns = view != null ? view.layout.tiles.columns : geometry.columns;
        int rows = view != null ? view.layout.tiles.rows : geometry.rows;
        String unknown = config.message("values.unknown");
        String source = screen.sourceWidth > 0 ? screen.sourceWidth + "x" + screen.sourceHeight : unknown;
        String processing = screen.processingWidth > 0 ? screen.processingWidth + "x" + screen.processingHeight
                : config.message("values.auto") + " (" + geometry.processingWidth + "x" + geometry.processingHeight + ")";
        MediaException error = screen.error;
        config.sendLines(sender, "info", "name", screen.name, "type", type(screen), "state", state(screen), "url", screen.url,
                "world", screen.world, "x", screen.x, "y", screen.y, "z", screen.z, "facing", screen.facing.name(),
                "rotation", screen.rotation * 90, "width", ScreenManager.number(geometry.width),
                "height", ScreenManager.number(geometry.height), "box-width", ScreenManager.number(screen.width),
                "box-height", ScreenManager.number(screen.height), "columns", columns, "rows", rows, "maps", columns * rows,
                "source", source, "processing", processing, "effective", geometry.effectiveWidth + "x" + geometry.effectiveHeight,
                "mode", screen.fitMode.name(), "aspect", config.message(screen.preserveAspect ? "values.preserve" : "values.free"),
                "fps", screen.isVideo() ? String.valueOf(screen.fps) : "-",
                "loop", config.message(screen.loop ? "values.yes" : "values.no"),
                "range", ScreenManager.number(screen.range), "viewers", screen.viewers,
                "hologram", config.message(screen.hologram ? "values.yes" : "values.no"),
                "error", error == null ? config.message("values.none") : config.reason(error),
                "renderer", plugin.adapter().describe());
    }

    private String type(VirtualScreen screen) {
        if (screen.animated) {
            return plugin.config().message("types.animation", "frames", screen.frames);
        }
        return plugin.config().message(screen.isVideo() ? "types.video" : "types.image");
    }

    private String state(VirtualScreen screen) {
        return plugin.config().message("states." + screen.state.name().toLowerCase(Locale.ROOT));
    }

    private boolean onCooldown(CommandSender sender) {
        long cooldown = settings().commandCooldownMs;
        if (cooldown <= 0 || !(sender instanceof Player)) {
            return false;
        }
        String key = ((Player) sender).getUniqueId().toString();
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(key);
        if (last != null && now - last < cooldown) {
            return true;
        }
        cooldowns.put(key, now);
        return false;
    }

    private static void expect(String[] args, int length) throws ScreenException {
        if (args.length != length) {
            throw new ScreenException("usage");
        }
    }

    private VirtualScreen screen(String name) throws ScreenException {
        VirtualScreen screen = plugin.screens().get(name);
        if (screen == null) {
            throw new ScreenException("screen-not-found", "name", name);
        }
        return screen;
    }

    private static VirtualScreen video(VirtualScreen screen) throws ScreenException {
        if (!screen.isVideo()) {
            throw new ScreenException("not-a-video", "name", screen.name);
        }
        return screen;
    }

    private static String name(String value) throws ScreenException {
        if (!ScreenManager.NAME.matcher(value).matches()) {
            throw new ScreenException("invalid-name", "value", value);
        }
        return value;
    }

    private void sendLink(CommandSender sender, String url, int minutes) {
        String text = plugin.config().message("web-link", "url", url, "minutes", minutes);
        if (sender instanceof Player) {
            BaseComponent[] components = TextComponent.fromLegacyText(text);
            ClickEvent click = new ClickEvent(ClickEvent.Action.OPEN_URL, url);
            for (BaseComponent component : components) {
                component.setClickEvent(click);
            }
            ((Player) sender).spigot().sendMessage(components);
        } else {
            sender.sendMessage(text);
        }
    }

    private String url(String value) throws ScreenException {
        if (UploadStore.isUpload(value)) {
            String id = UploadStore.id(value);
            if (!plugin.uploads().exists(id)) {
                throw new ScreenException("upload-not-found", "value", id, "label", "packetscreen");
            }
            return value;
        }
        String link = HttpFetcher.directLink(value);
        try {
            plugin.http().validate(link, settings());
            return link.trim();
        } catch (MediaException e) {
            throw new ScreenException("invalid-url");
        }
    }

    private static void checkKind(ScreenType type, String source, String label, String name) throws ScreenException {
        String path = source;
        int cut = path.length();
        for (char mark : new char[]{'?', '#'}) {
            int index = path.indexOf(mark);
            if (index >= 0 && index < cut) {
                cut = index;
            }
        }
        path = path.substring(0, cut);
        String file = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        int dot = file.lastIndexOf('.');
        String extension = dot < 0 ? "" : file.substring(dot + 1);
        if (type == ScreenType.IMAGE && VIDEO_EXTENSIONS.contains(extension)) {
            throw new ScreenException("source-is-video", "label", label, "name", name);
        }
        if (type == ScreenType.VIDEO && IMAGE_EXTENSIONS.contains(extension)) {
            throw new ScreenException("source-is-image", "label", label, "name", name);
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

    private static int whole(String value, String key) throws ScreenException {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new ScreenException(key, "value", value);
        }
    }

    private void checkSize(double width, double height) throws ScreenException {
        Settings settings = settings();
        if (width < settings.minSize || height < settings.minSize || width > settings.maxWidth || height > settings.maxHeight) {
            throw new ScreenException("size-out-of-bounds", "min", ScreenManager.number(settings.minSize),
                    "max-width", ScreenManager.number(settings.maxWidth), "max-height", ScreenManager.number(settings.maxHeight));
        }
    }

    private ScreenManager.Placement placement(CommandSender sender, String[] args, int start) throws ScreenException {
        Player player = sender instanceof Player ? (Player) sender : null;
        Location base = player != null ? player.getLocation() : null;
        int x = coordinate(args[start], base == null ? null : base.getBlockX());
        int y = coordinate(args[start + 1], base == null ? null : base.getBlockY());
        int z = coordinate(args[start + 2], base == null ? null : base.getBlockZ());
        Facing facing = player != null ? Facing.fromYaw(base.getYaw()).opposite() : Facing.SOUTH;
        if (args.length > start + 3) {
            facing = Facing.parse(args[start + 3]);
            if (facing == null) {
                throw new ScreenException("invalid-facing", "value", args[start + 3]);
            }
        }
        String world;
        if (args.length > start + 4) {
            World found = Bukkit.getWorld(args[start + 4]);
            if (found == null) {
                throw new ScreenException("invalid-world", "value", args[start + 4]);
            }
            world = found.getName();
        } else if (player != null) {
            world = player.getWorld().getName();
        } else {
            throw new ScreenException("world-required");
        }
        return placement(world, x, y, z, facing, player == null ? 0f : base.getYaw());
    }

    private ScreenManager.Placement placement(String world, int x, int y, int z, Facing facing, float yaw)
            throws ScreenException {
        int rotation = 0;
        if (facing.vertical()) {
            if (!plugin.adapter().supportsVerticalFacing()) {
                throw new ScreenException("compatibility-error", "version", plugin.adapter().version().getReleaseName(),
                        "feature", plugin.config().message("features.floor-screens"));
            }
            Facing ahead = Facing.fromYaw(yaw);
            rotation = facing.uprightRotation(facing == Facing.UP ? ahead : ahead.opposite());
        }
        return new ScreenManager.Placement(world, x, y, z, facing, rotation);
    }

    private static int coordinate(String value, Integer base) throws ScreenException {
        try {
            if (value.startsWith("~")) {
                if (base == null) {
                    throw new ScreenException("players-only");
                }
                return value.length() == 1 ? base : base + (int) Math.floor(Double.parseDouble(value.substring(1)));
            }
            return (int) Math.floor(Double.parseDouble(value));
        } catch (NumberFormatException e) {
            throw new ScreenException("invalid-number", "value", value);
        }
    }

    private ScreenManager.Placement target(CommandSender sender) throws ScreenException {
        if (!(sender instanceof Player)) {
            throw new ScreenException("players-only");
        }
        Player player = (Player) sender;
        int distance = settings().maxTargetDistance;
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        World world = eye.getWorld();
        double dx = direction.getX();
        double dy = direction.getY();
        double dz = direction.getZ();
        int x = eye.getBlockX();
        int y = eye.getBlockY();
        int z = eye.getBlockZ();
        int stepX = dx > 0 ? 1 : -1;
        int stepY = dy > 0 ? 1 : -1;
        int stepZ = dz > 0 ? 1 : -1;
        double deltaX = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double deltaY = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double deltaZ = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        double maxX = dx == 0 ? Double.POSITIVE_INFINITY : (dx > 0 ? x + 1 - eye.getX() : eye.getX() - x) * deltaX;
        double maxY = dy == 0 ? Double.POSITIVE_INFINITY : (dy > 0 ? y + 1 - eye.getY() : eye.getY() - y) * deltaY;
        double maxZ = dz == 0 ? Double.POSITIVE_INFINITY : (dz > 0 ? z + 1 - eye.getZ() : eye.getZ() - z) * deltaZ;
        try {
            while (true) {
                int nx = 0;
                int ny = 0;
                int nz = 0;
                if (maxX < maxY && maxX < maxZ) {
                    if (maxX > distance) {
                        break;
                    }
                    x += stepX;
                    maxX += deltaX;
                    nx = -stepX;
                } else if (maxY < maxZ) {
                    if (maxY > distance) {
                        break;
                    }
                    y += stepY;
                    maxY += deltaY;
                    ny = -stepY;
                } else {
                    if (maxZ > distance) {
                        break;
                    }
                    z += stepZ;
                    maxZ += deltaZ;
                    nz = -stepZ;
                }
                if (world.getBlockAt(x, y, z).getType().isSolid()) {
                    Facing facing = Facing.fromNormal(nx, ny, nz);
                    return placement(world.getName(), x + nx, y + ny, z + nz, facing, player.getLocation().getYaw());
                }
            }
        } catch (RuntimeException e) {
            throw new ScreenException("no-target-block", "distance", distance);
        }
        throw new ScreenException("no-target-block", "distance", distance);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        ConfigurationManager config = plugin.config();
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            options.add("help");
            for (String sub : Settings.SUBCOMMANDS) {
                if (config.hasPermission(sender, sub)) {
                    options.add(sub);
                }
            }
            return filter(options, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!Settings.SUBCOMMANDS.contains(sub) || !config.hasPermission(sender, sub)) {
            return Collections.emptyList();
        }
        int index = args.length - 1;
        String current = args[index];
        Settings settings = settings();
        if (sub.equals("create") || sub.equals("video")) {
            if (index == 2) {
                return filter(sources(sub.equals("video") ? ScreenType.VIDEO : ScreenType.IMAGE), current);
            }
            if (index == 3) {
                return filter(Arrays.asList(ScreenManager.number(settings.defaultWidth), "~"), current);
            }
            if (index < 3) {
                return Collections.emptyList();
            }
            if (args[3].startsWith("~")) {
                return location(sender, index - 3, current);
            }
            if (index == 4) {
                return filter(Collections.singletonList(ScreenManager.number(settings.defaultHeight)), current);
            }
            return location(sender, index - 5, current);
        }
        if (sub.equals("list") || sub.equals("reload")) {
            return Collections.emptyList();
        }
        if (sub.equals("web")) {
            return index == 1 ? filter(Collections.singletonList("revoke"), current) : Collections.<String>emptyList();
        }
        if (index == 1) {
            List<String> names = new ArrayList<>();
            boolean videoOnly = Arrays.asList("fps", "play", "pause", "stop", "restart", "loop").contains(sub);
            for (VirtualScreen screen : plugin.screens().sorted()) {
                if (!videoOnly || screen.isVideo()) {
                    names.add(screen.name);
                }
            }
            return filter(names, current);
        }
        switch (sub) {
            case "url": {
                VirtualScreen screen = plugin.screens().get(args[1]);
                if (index != 2 || screen == null) {
                    return Collections.emptyList();
                }
                return filter(sources(screen.type), current);
            }
            case "resize":
                return index == 4 ? filter(Arrays.asList("FIT", "FILL", "STRETCH"), current) : Collections.<String>emptyList();
            case "scale":
                return index == 2 ? filter(Arrays.asList("0.5", "2"), current) : Collections.<String>emptyList();
            case "aspect":
                return index == 2 ? filter(Arrays.asList("preserve", "free"), current) : Collections.<String>emptyList();
            case "resolution":
                return index == 2 ? filter(Arrays.asList("auto", "128", "256", "512"), current) : Collections.<String>emptyList();
            case "fps":
                return index == 2 ? filter(Arrays.asList("5", "10", "15", "20"), current) : Collections.<String>emptyList();
            case "loop":
            case "hologram":
                return index == 2 ? filter(Arrays.asList("true", "false"), current) : Collections.<String>emptyList();
            case "rotate":
                return index == 2 ? filter(Arrays.asList("0", "90", "180", "270"), current) : Collections.<String>emptyList();
            case "range":
                return index == 2 ? filter(Arrays.asList("16", "32", "48", "64"), current) : Collections.<String>emptyList();
            case "move":
                return location(sender, index - 2, current);
            default:
                return Collections.emptyList();
        }
    }

    private List<String> sources(ScreenType type) {
        List<String> sources = new ArrayList<>();
        sources.add("https://");
        for (UploadStore.Upload upload : plugin.uploads().list()) {
            boolean image = upload.kind == UploadStore.Kind.IMAGE;
            if (type == ScreenType.IMAGE ? image : !image || upload.id.endsWith(".gif")) {
                sources.add(UploadStore.PREFIX + upload.id);
            }
        }
        return sources;
    }

    private List<String> location(CommandSender sender, int position, String current) {
        if (position >= 0 && position <= 2) {
            return filter(Collections.singletonList("~"), current);
        }
        if (position == 3) {
            return filter(FACINGS, current);
        }
        if (position == 4) {
            List<String> worlds = new ArrayList<>();
            for (World world : Bukkit.getWorlds()) {
                worlds.add(world.getName());
            }
            return filter(worlds, current);
        }
        return Collections.emptyList();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
