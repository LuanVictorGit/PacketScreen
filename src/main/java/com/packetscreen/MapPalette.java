package com.packetscreen;

import com.github.retrooper.packetevents.manager.server.ServerVersion;

final class MapPalette {

    private static final int[] BASE_COLORS = {
            0x000000, 0x7FB238, 0xF7E9A3, 0xC7C7C7, 0xFF0000, 0xA0A0FF, 0xA7A7A7, 0x007C00,
            0xFFFFFF, 0xA4A8B8, 0x976D4D, 0x707070, 0x4040FF, 0x8F7748, 0xFFFCF5, 0xD87F33,
            0xB24CD8, 0x6699D8, 0xE5E533, 0x7FCC19, 0xF27FA5, 0x4C4C4C, 0x999999, 0x4C7F99,
            0x7F3FB2, 0x334CB2, 0x664C33, 0x667F33, 0x993333, 0x191919, 0xFAEE4D, 0x5CDBD5,
            0x4A80FF, 0x00D93A, 0x815631, 0x700200, 0xD1B1A1, 0x9F5224, 0x95576C, 0x706C8A,
            0xBA8524, 0x677535, 0xA04D4E, 0x392923, 0x876B62, 0x575C5C, 0x7A4958, 0x4C3E5C,
            0x4C3223, 0x4C522A, 0x8E3C2E, 0x251610, 0xBD3031, 0x943F61, 0x5C191D, 0x167E86,
            0x3A8E8C, 0x562C3E, 0x14B485, 0x646464, 0xD8AF93, 0x7FA796
    };
    private static final int[] SHADES = {180, 220, 255, 135};

    final int baseColors;
    private final int[] red = new int[256];
    private final int[] green = new int[256];
    private final int[] blue = new int[256];
    private final int size;
    private volatile byte[] table;

    MapPalette(int baseColors) {
        this.baseColors = Math.max(2, Math.min(BASE_COLORS.length, baseColors));
        this.size = this.baseColors * 4;
        for (int index = 4; index < size; index++) {
            int base = BASE_COLORS[index >> 2];
            int shade = SHADES[index & 3];
            red[index] = ((base >> 16) & 0xFF) * shade / 255;
            green[index] = ((base >> 8) & 0xFF) * shade / 255;
            blue[index] = (base & 0xFF) * shade / 255;
        }
    }

    static MapPalette forVersion(ServerVersion version) {
        if (version.isOlderThan(ServerVersion.V_1_12)) {
            return new MapPalette(36);
        }
        if (version.isOlderThan(ServerVersion.V_1_16)) {
            return new MapPalette(52);
        }
        if (version.isOlderThan(ServerVersion.V_1_17)) {
            return new MapPalette(59);
        }
        return new MapPalette(62);
    }

    void prepare() {
        table();
    }

    byte match(int r, int g, int b) {
        return table()[(r >> 2) << 12 | (g >> 2) << 6 | (b >> 2)];
    }

    byte matchRgb(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int best = 4;
        int bestDistance = Integer.MAX_VALUE;
        for (int index = 4; index < size; index++) {
            int dr = r - red[index];
            int dg = g - green[index];
            int db = b - blue[index];
            int distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = index;
            }
        }
        return (byte) best;
    }

    int red(int index) {
        return red[index & 0xFF];
    }

    int green(int index) {
        return green[index & 0xFF];
    }

    int blue(int index) {
        return blue[index & 0xFF];
    }

    int rgb(int index) {
        int i = index & 0xFF;
        return red[i] << 16 | green[i] << 8 | blue[i];
    }

    int nearest(int r, int g, int b) {
        int best = 4;
        long bestDistance = Long.MAX_VALUE;
        for (int index = 4; index < size; index++) {
            long distance = distance(r, g, b, red[index], green[index], blue[index]);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = index;
            }
        }
        return best;
    }

    private byte[] table() {
        byte[] current = table;
        if (current == null) {
            synchronized (this) {
                current = table;
                if (current == null) {
                    current = build();
                    table = current;
                }
            }
        }
        return current;
    }

    private byte[] build() {
        byte[] lookup = new byte[1 << 18];
        for (int r6 = 0; r6 < 64; r6++) {
            int r = r6 << 2 | r6 >> 4;
            for (int g6 = 0; g6 < 64; g6++) {
                int g = g6 << 2 | g6 >> 4;
                int base = r6 << 12 | g6 << 6;
                for (int b6 = 0; b6 < 64; b6++) {
                    int b = b6 << 2 | b6 >> 4;
                    lookup[base | b6] = (byte) nearest(r, g, b);
                }
            }
        }
        return lookup;
    }

    private static long distance(int r1, int g1, int b1, int r2, int g2, int b2) {
        int mean = (r1 + r2) >> 1;
        int dr = r1 - r2;
        int dg = g1 - g2;
        int db = b1 - b2;
        return (((512L + mean) * dr * dr) >> 8) + 4L * dg * dg + (((767L - mean) * db * db) >> 8);
    }
}
