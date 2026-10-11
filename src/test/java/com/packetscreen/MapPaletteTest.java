package com.packetscreen;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapPaletteTest {

    @Test
    void paletteSizeFollowsTheProtocolVersion() {
        assertEquals(36, MapPalette.forVersion(ServerVersion.V_1_8_8).baseColors);
        assertEquals(36, MapPalette.forVersion(ServerVersion.V_1_11_2).baseColors);
        assertEquals(52, MapPalette.forVersion(ServerVersion.V_1_12_2).baseColors);
        assertEquals(59, MapPalette.forVersion(ServerVersion.V_1_16_5).baseColors);
        assertEquals(62, MapPalette.forVersion(ServerVersion.V_1_17).baseColors);
        assertEquals(62, MapPalette.forVersion(ServerVersion.V_26_3).baseColors);
    }

    @Test
    void legacyPaletteNeverUsesColorsUnknownToOldClients() {
        MapPalette palette = new MapPalette(36);
        Random random = new Random(7);
        for (int i = 0; i < 20000; i++) {
            int index = palette.match(random.nextInt(256), random.nextInt(256), random.nextInt(256)) & 0xFF;
            assertTrue(index >= 4 && index < 144, "index " + index);
        }
    }

    @Test
    void modernPaletteNeverReturnsTransparentIndices() {
        MapPalette palette = new MapPalette(62);
        Random random = new Random(11);
        for (int i = 0; i < 20000; i++) {
            int index = palette.match(random.nextInt(256), random.nextInt(256), random.nextInt(256)) & 0xFF;
            assertTrue(index >= 4 && index < 248, "index " + index);
        }
    }

    @Test
    void exactPaletteColorsAreMatched() {
        MapPalette palette = new MapPalette(62);
        assertEquals(0xFFFFFF, palette.rgb(palette.match(255, 255, 255)));
        assertEquals(0x7FB238, palette.rgb(palette.nearest(0x7F, 0xB2, 0x38)));
        for (int colors : new int[]{36, 52, 59, 62}) {
            MapPalette flat = new MapPalette(colors);
            assertEquals(0x0D0D0D, flat.rgb(flat.matchRgb(0x000000)), "black with " + colors + " colours");
        }
        int black = palette.rgb(palette.match(0, 0, 0));
        assertTrue(((black >> 16) & 0xFF) < 30 && ((black >> 8) & 0xFF) < 30 && (black & 0xFF) < 30);
    }

    @Test
    void lookupIsPerceptuallyCloseToFullSearch() {
        MapPalette palette = new MapPalette(62);
        Random random = new Random(3);
        for (int i = 0; i < 5000; i++) {
            int r = random.nextInt(256);
            int g = random.nextInt(256);
            int b = random.nextInt(256);
            double lookup = Math.sqrt(distance(r, g, b, palette.rgb(palette.match(r, g, b))));
            double exact = Math.sqrt(distance(r, g, b, palette.rgb(palette.nearest(r, g, b))));
            assertTrue(lookup <= exact + 25, "lookup " + lookup + " exact " + exact);
        }
    }

    private static double distance(int r, int g, int b, int rgb) {
        int pr = (rgb >> 16) & 0xFF;
        int pg = (rgb >> 8) & 0xFF;
        int pb = rgb & 0xFF;
        double mean = (r + pr) / 2.0;
        return (2 + mean / 256) * (r - pr) * (r - pr) + 4.0 * (g - pg) * (g - pg) + (2 + (255 - mean) / 256) * (b - pb) * (b - pb);
    }
}
