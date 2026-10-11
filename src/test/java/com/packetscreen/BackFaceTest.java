package com.packetscreen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackFaceTest {

    private static final int FULL = 900;
    private static final byte BLACK = 119;

    private static ScreenLayout layout(int columns, int rows, int rotation) {
        int count = columns * rows;
        TileSet tiles = new TileSet(columns, rows, new int[count], new int[count], new int[count], new int[]{901, 902, 903});
        return new ScreenLayout(tiles, "world", 0, 64, 0, Facing.SOUTH, rotation);
    }

    private static int pixel(byte[] data, int x, int y) {
        return data[y * RenderedFrame.TILE + x];
    }

    @Test
    void fullTilesShareOneMapAndTheTopRowFollowsTheContentHeight() {
        BackFace back = BackFace.compute(layout(4, 3, 0), 512, 288, FULL, BLACK, new boolean[12], 1);
        for (int tile = 0; tile < 4; tile++) {
            assertEquals(901, back.maps[tile]);
        }
        for (int tile = 4; tile < 12; tile++) {
            assertEquals(FULL, back.maps[tile]);
        }
        byte[] top = back.partialData[0];
        assertEquals(0, pixel(top, 0, 95));
        assertEquals(BLACK, pixel(top, 0, 96));
        assertEquals(BLACK, pixel(top, 127, 127));
        assertNull(back.partialData[1]);
        assertNull(back.partialData[2]);
        assertTrue(back.usesFull());
    }

    @Test
    void partialColumnsAreMirroredForTheViewerBehind() {
        BackFace back = BackFace.compute(layout(3, 2, 0), 320, 192, FULL, BLACK, new boolean[6], 1);
        assertArrayEquals(new int[]{901, 901, 903, FULL, FULL, 902}, back.maps);
        byte[] right = back.partialData[1];
        assertEquals(0, pixel(right, 63, 0));
        assertEquals(BLACK, pixel(right, 64, 0));
        assertEquals(BLACK, pixel(right, 127, 127));
        byte[] corner = back.partialData[2];
        assertEquals(0, pixel(corner, 100, 63));
        assertEquals(0, pixel(corner, 63, 100));
        assertEquals(BLACK, pixel(corner, 64, 64));
    }

    @Test
    void tilesCoveredByAnotherScreenHaveNoBack() {
        boolean[] hidden = {false, true};
        BackFace back = BackFace.compute(layout(2, 1, 0), 256, 128, FULL, BLACK, hidden, 1);
        assertArrayEquals(new int[]{FULL, BackFace.NONE}, back.maps);
        BackFace none = BackFace.compute(layout(1, 1, 0), 128, 128, FULL, BLACK, new boolean[]{true}, 1);
        assertFalse(none.usesFull());
    }

    @Test
    void rotationIsReversedSeenFromBehind() {
        assertEquals(0, BackFace.compute(layout(1, 1, 0), 128, 128, FULL, BLACK, new boolean[1], 1).rotation());
        assertEquals(3, BackFace.compute(layout(1, 1, 1), 128, 128, FULL, BLACK, new boolean[1], 1).rotation());
        assertEquals(2, BackFace.compute(layout(1, 1, 2), 128, 128, FULL, BLACK, new boolean[1], 1).rotation());
        assertEquals(1, BackFace.compute(layout(1, 1, 3), 128, 128, FULL, BLACK, new boolean[1], 1).rotation());
    }

    @Test
    void reuseRequiresTheSameInputs() {
        ScreenLayout layout = layout(2, 1, 0);
        boolean[] hidden = new boolean[2];
        BackFace back = BackFace.compute(layout, 256, 128, FULL, BLACK, hidden, 7);
        assertTrue(back.matches(layout, 256, 128, BLACK));
        assertFalse(back.matches(layout, 200, 128, BLACK));
        assertFalse(back.matches(layout, 256, 128, (byte) 4));
        assertFalse(back.matches(layout(2, 1, 0), 256, 128, BLACK));
        assertArrayEquals(hidden, back.hidden);
        assertEquals(7, back.stamp);
    }
}
