package com.packetscreen;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsTest {

    private static YamlConfiguration bundled() throws Exception {
        try (InputStream in = SettingsTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(new String(out.toByteArray(), StandardCharsets.UTF_8));
            return yaml;
        }
    }

    private static List<String> warnings(YamlConfiguration yaml) {
        List<String> warnings = new ArrayList<>();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        Settings.read(yaml, logger);
        return warnings;
    }

    @Test
    void bundledConfigurationIsValid() throws Exception {
        YamlConfiguration yaml = bundled();
        assertEquals(new ArrayList<String>(), warnings(yaml));
        Settings settings = Settings.read(yaml, Logger.getAnonymousLogger());
        assertEquals("", settings.webHost);
        assertEquals("", settings.webPublicUrl);
        assertFalse(settings.webEnabled);
        assertEquals(FitMode.FIT, settings.defaultFitMode);
        assertEquals("packetscreen.web", settings.permission("web"));
        assertTrue(settings.allowedPorts.contains(443));
    }

    @Test
    void invalidValuesFallBackWithWarnings() throws Exception {
        YamlConfiguration yaml = bundled();
        yaml.set("defaults.fit-mode", "SQUISH");
        yaml.set("limits.max-fps", 500);
        yaml.set("rendering.background-color", "blue");
        yaml.set("web.public-url", "javascript:alert(1)");
        List<String> warnings = warnings(yaml);
        assertEquals(4, warnings.size(), String.valueOf(warnings));
        Settings settings = Settings.read(yaml, Logger.getAnonymousLogger());
        assertEquals(FitMode.FIT, settings.defaultFitMode);
        assertEquals(20, settings.maxFps);
        assertEquals(0x000000, settings.backgroundColor);
        assertEquals("", settings.webPublicUrl);
    }
}
