package com.packetscreen;

import java.util.Arrays;

final class BackFace {

    static final int NONE = -1;

    final ScreenLayout layout;
    final int contentWidth;
    final int contentHeight;
    final byte color;
    final int fullMap;
    final int[] maps;
    final int[] partialIds;
    final byte[][] partialData;
    final boolean[] hidden;
    volatile int stamp;

    private BackFace(ScreenLayout layout, int contentWidth, int contentHeight, byte color, int fullMap, int[] maps,
                     int[] partialIds, byte[][] partialData, boolean[] hidden, int stamp) {
        this.layout = layout;
        this.contentWidth = contentWidth;
        this.contentHeight = contentHeight;
        this.color = color;
        this.fullMap = fullMap;
        this.maps = maps;
        this.partialIds = partialIds;
        this.partialData = partialData;
        this.hidden = hidden;
        this.stamp = stamp;
    }

    static BackFace compute(ScreenLayout layout, int contentWidth, int contentHeight, int fullMap, byte color,
                            boolean[] hidden, int stamp) {
        TileSet tiles = layout.tiles;
        int tile = RenderedFrame.TILE;
        int[] maps = new int[tiles.columns * tiles.rows];
        int[] partialIds = new int[3];
        byte[][] partialData = new byte[3][];
        Arrays.fill(partialIds, NONE);
        int top = tiles.rows * tile - contentHeight;
        int lastWidth = contentWidth - (tiles.columns - 1) * tile;
        for (int row = 0; row < tiles.rows; row++) {
            int start = Math.max(0, top - row * tile);
            for (int column = 0; column < tiles.columns; column++) {
                int index = row * tiles.columns + column;
                int width = column == tiles.columns - 1 ? lastWidth : tile;
                if (hidden[index] || start >= tile || width <= 0) {
                    maps[index] = NONE;
                } else if (start == 0 && width == tile) {
                    maps[index] = fullMap;
                } else {
                    int pattern = (start > 0 ? 1 : 0) + (width < tile ? 2 : 0) - 1;
                    if (partialData[pattern] == null) {
                        partialData[pattern] = mask(start, width, color);
                        partialIds[pattern] = tiles.backMapIds[pattern];
                    }
                    maps[index] = partialIds[pattern];
                }
            }
        }
        return new BackFace(layout, contentWidth, contentHeight, color, fullMap, maps, partialIds, partialData, hidden, stamp);
    }

    static byte[] mask(int start, int width, byte color) {
        int tile = RenderedFrame.TILE;
        byte[] data = new byte[RenderedFrame.TILE_AREA];
        for (int y = start; y < tile; y++) {
            Arrays.fill(data, y * tile + tile - width, (y + 1) * tile, color);
        }
        return data;
    }

    boolean usesFull() {
        for (int map : maps) {
            if (map == fullMap) {
                return true;
            }
        }
        return false;
    }

    boolean matches(ScreenLayout layout, int contentWidth, int contentHeight, byte color) {
        return this.layout == layout && this.contentWidth == contentWidth && this.contentHeight == contentHeight
                && this.color == color;
    }

    int rotation() {
        return (4 - layout.rotation) & 3;
    }
}
