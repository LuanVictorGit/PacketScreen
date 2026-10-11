package com.packetscreen;

import org.bukkit.configuration.file.YamlConfiguration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.logging.Logger;

final class TestSupport {

    static final byte[] WEBP_LOSSLESS = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4TBYAAAAvB0ABAAdQs8p0uf8BgCD8b5uI6H8I");
    static final byte[] WEBP_LOSSY = Base64.getDecoder().decode(
            "UklGRvIAAABXRUJQVlA4IOYAAACwAwCdASoQAAwAAUAmJbACdHIrtQC+AP0z6xXyZneADaYMsCEigAD++6H0J/tKX55d3dyvz/9w7hkn/2K0"
                    + "BH2cRQ/5t6U9R2LHz//6uQH3+gCZZ8uBxdHfnvYll9f4pY2c6B4mckL/+X3SP0riKT/vn//GmjX8l48co2vfvu3xzZk596//n8nxqCh2"
                    + "P/pYfjFTmhP8Oj0Jc//zT/i1AQKwQSf8f9LAH5ej4a/2aYfeFb/q+qXmBd+iRfTePDJBf88P5vR060SDZj9N9sQz+O+vZXX+QOoKNImaF//yDP0f8MIClaAAAA==");

    private TestSupport() {
    }

    static Settings settings(Object... overrides) {
        try (InputStream in = TestSupport.class.getClassLoader().getResourceAsStream("config.yml")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(new String(out.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i + 1 < overrides.length; i += 2) {
                yaml.set((String) overrides[i], overrides[i + 1]);
            }
            return Settings.read(yaml, Logger.getLogger("PacketScreenTest"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] image(String format, int width, int height, int argb) throws IOException {
        int type = format.equals("png") ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage image = new BufferedImage(width, height, type);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, argb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, out)) {
            throw new IOException("No writer for " + format);
        }
        return out.toByteArray();
    }
}
