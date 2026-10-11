package com.packetscreen;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ConfigurationManager {

    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");
    private static final int[] LEGACY_RGB = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00,
            0xAAAAAA, 0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
    private static final String LEGACY_CODES = "0123456789abcdef";

    private final JavaPlugin plugin;
    private final boolean hexColors;
    private volatile Settings settings;
    private volatile YamlConfiguration messages;
    private volatile String prefix = "";

    ConfigurationManager(JavaPlugin plugin, boolean hexColors) {
        this.plugin = plugin;
        this.hexColors = hexColors;
    }

    void load() throws IOException, InvalidConfigurationException {
        plugin.saveDefaultConfig();
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(read(configFile));
        config.setDefaults(bundled("config.yml"));
        Settings loaded = Settings.read(config, plugin.getLogger());
        YamlConfiguration loadedMessages = loadMessages(loaded.language);
        settings = loaded;
        messages = loadedMessages;
        prefix = colorize(loadedMessages.getString("prefix", ""));
    }

    Settings settings() {
        return settings;
    }

    private YamlConfiguration loadMessages(String language) throws IOException, InvalidConfigurationException {
        String name = language.equalsIgnoreCase("en") ? "messages.yml" : "messages_" + language + ".yml";
        YamlConfiguration fallback = bundled("messages.yml");
        if (plugin.getResource(name) == null && !new File(plugin.getDataFolder(), name).exists()) {
            plugin.getLogger().warning("Messages file " + name + " not found, using messages.yml.");
            name = "messages.yml";
        }
        if (!name.equals("messages.yml")) {
            saveResource("messages.yml");
        }
        saveResource(name);
        YamlConfiguration loaded = new YamlConfiguration();
        loaded.loadFromString(read(new File(plugin.getDataFolder(), name)));
        YamlConfiguration defaults = plugin.getResource(name) != null ? bundled(name) : fallback;
        if (defaults != fallback) {
            defaults.setDefaults(fallback);
        }
        loaded.setDefaults(defaults);
        return loaded;
    }

    private void saveResource(String name) {
        if (!new File(plugin.getDataFolder(), name).exists() && plugin.getResource(name) != null) {
            plugin.saveResource(name, false);
        }
    }

    private YamlConfiguration bundled(String name) throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        InputStream in = plugin.getResource(name);
        if (in == null) {
            return yaml;
        }
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            yaml.loadFromString(new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
        return yaml;
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    String message(String key, Object... placeholders) {
        String raw = messages.getString(key);
        if (raw == null) {
            return key;
        }
        return apply(colorize(raw).replace("{prefix}", prefix), placeholders);
    }

    List<String> messages(String key, Object... placeholders) {
        List<String> raw = messages.getStringList(key);
        if (raw == null || raw.isEmpty()) {
            return Collections.singletonList(message(key, placeholders));
        }
        List<String> lines = new ArrayList<>(raw.size());
        for (String line : raw) {
            lines.add(apply(colorize(line).replace("{prefix}", prefix), placeholders));
        }
        return lines;
    }

    boolean has(String key) {
        return messages.getString(key) != null;
    }

    String plain(String key, Object... placeholders) {
        String raw = messages.getString(key);
        if (raw == null) {
            return key;
        }
        return apply(strip(raw.replace("{prefix}", "")), placeholders).trim();
    }

    Map<String, String> plainSection(String path) {
        Set<String> keys = new LinkedHashSet<>();
        ConfigurationSection defaults = messages.getDefaults() == null ? null : messages.getDefaults().getConfigurationSection(path);
        if (defaults != null) {
            keys.addAll(defaults.getKeys(false));
        }
        ConfigurationSection section = messages.getConfigurationSection(path);
        if (section != null) {
            keys.addAll(section.getKeys(false));
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : keys) {
            String value = messages.getString(path + "." + key);
            if (value != null) {
                result.put(key, strip(value.replace("{prefix}", "")).trim());
            }
        }
        return result;
    }

    private static String strip(String text) {
        String withoutHex = HEX.matcher(text).replaceAll("");
        return ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', withoutHex));
    }

    void send(CommandSender sender, String key, Object... placeholders) {
        String text = message(key, placeholders);
        if (!text.isEmpty()) {
            sender.sendMessage(text);
        }
    }

    void sendLines(CommandSender sender, String key, Object... placeholders) {
        for (String line : messages(key, placeholders)) {
            sender.sendMessage(line);
        }
    }

    String reason(MediaException exception) {
        return message("reasons." + exception.reason.key, exception.placeholders);
    }

    boolean hasPermission(CommandSender sender, String subcommand) {
        if (sender.hasPermission("packetscreen.admin")) {
            return true;
        }
        String permission = settings.permission(subcommand);
        return permission.isEmpty() || sender.hasPermission(permission);
    }

    private static String apply(String text, Object... placeholders) {
        String result = text;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            result = result.replace("{" + placeholders[i] + "}", String.valueOf(placeholders[i + 1]));
        }
        return result;
    }

    private String colorize(String text) {
        Matcher matcher = HEX.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
            String replacement;
            if (hexColors) {
                StringBuilder builder = new StringBuilder("§x");
                for (char c : hex.toCharArray()) {
                    builder.append('§').append(c);
                }
                replacement = builder.toString();
            } else {
                replacement = "§" + nearestLegacy(Integer.parseInt(hex, 16));
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }

    private static char nearestLegacy(int rgb) {
        int best = 15;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < LEGACY_RGB.length; i++) {
            int dr = ((rgb >> 16) & 255) - ((LEGACY_RGB[i] >> 16) & 255);
            int dg = ((rgb >> 8) & 255) - ((LEGACY_RGB[i] >> 8) & 255);
            int db = (rgb & 255) - (LEGACY_RGB[i] & 255);
            long distance = (long) dr * dr + (long) dg * dg + (long) db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return LEGACY_CODES.charAt(best);
    }
}
