package com.packetscreen;

final class RenderedFrame {

    static final int TILE = 128;
    static final int TILE_AREA = TILE * TILE;

    final TileSet tiles;
    final byte[][] data;
    final int[] versions;
    final int[] previousVersions;
    final byte[][] deltas;
    final int[] rects;

    private RenderedFrame(TileSet tiles) {
        int count = tiles.size();
        this.tiles = tiles;
        this.data = new byte[count][];
        this.versions = new int[count];
        this.previousVersions = new int[count];
        this.deltas = new byte[count][];
        this.rects = new int[count * 4];
    }

    static RenderedFrame next(RenderedFrame previous, TileSet tiles, byte[][] fresh, int version) {
        RenderedFrame frame = new RenderedFrame(tiles);
        boolean compare = previous != null && previous.tiles == tiles;
        for (int tile = 0; tile < tiles.size(); tile++) {
            byte[] now = fresh[tile];
            if (!compare) {
                frame.data[tile] = now;
                frame.versions[tile] = version;
                frame.previousVersions[tile] = -1;
                continue;
            }
            byte[] old = previous.data[tile];
            int minX = TILE;
            int maxX = -1;
            int minY = TILE;
            int maxY = -1;
            for (int y = 0; y < TILE; y++) {
                int base = y * TILE;
                int left = 0;
                while (left < TILE && old[base + left] == now[base + left]) {
                    left++;
                }
                if (left == TILE) {
                    continue;
                }
                int right = TILE - 1;
                while (old[base + right] == now[base + right]) {
                    right--;
                }
                if (left < minX) {
                    minX = left;
                }
                if (right > maxX) {
                    maxX = right;
                }
                if (y < minY) {
                    minY = y;
                }
                maxY = y;
            }
            if (maxX < 0) {
                frame.data[tile] = old;
                frame.versions[tile] = previous.versions[tile];
                frame.previousVersions[tile] = previous.previousVersions[tile];
                frame.deltas[tile] = previous.deltas[tile];
                System.arraycopy(previous.rects, tile * 4, frame.rects, tile * 4, 4);
                continue;
            }
            frame.data[tile] = now;
            frame.versions[tile] = version;
            frame.previousVersions[tile] = previous.versions[tile];
            int width = maxX - minX + 1;
            int height = maxY - minY + 1;
            if (width * height * 4 < TILE_AREA * 3) {
                byte[] delta = new byte[width * height];
                for (int y = 0; y < height; y++) {
                    System.arraycopy(now, (minY + y) * TILE + minX, delta, y * width, width);
                }
                frame.deltas[tile] = delta;
                int r = tile * 4;
                frame.rects[r] = minX;
                frame.rects[r + 1] = minY;
                frame.rects[r + 2] = width;
                frame.rects[r + 3] = height;
            }
        }
        return frame;
    }
}
