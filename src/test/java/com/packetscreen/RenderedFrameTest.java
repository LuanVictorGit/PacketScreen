package com.packetscreen;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class RenderedFrameTest {

    private final TileSet tiles = new TileSet(2, 1, new int[]{1, 2}, new int[]{3, 4}, new int[]{5, 6}, new int[]{7, 8, 9});

    private static byte[][] fresh(byte value) {
        byte[][] data = FrameProcessor.allocate(2);
        for (byte[] tile : data) {
            Arrays.fill(tile, value);
        }
        return data;
    }

    @Test
    void firstFrameSendsEverything() {
        RenderedFrame frame = RenderedFrame.next(null, tiles, fresh((byte) 10), 1);
        assertEquals(1, frame.versions[0]);
        assertEquals(1, frame.versions[1]);
        assertEquals(-1, frame.previousVersions[0]);
        assertNull(frame.deltas[0]);
    }

    @Test
    void smallChangesProduceDeltaRectangles() {
        RenderedFrame first = RenderedFrame.next(null, tiles, fresh((byte) 10), 1);
        byte[][] next = fresh((byte) 10);
        next[0][5 * 128 + 7] = 20;
        next[0][6 * 128 + 9] = 30;
        RenderedFrame second = RenderedFrame.next(first, tiles, next, 2);
        assertEquals(2, second.versions[0]);
        assertEquals(1, second.previousVersions[0]);
        assertArrayEquals(new int[]{7, 5, 3, 2}, Arrays.copyOfRange(second.rects, 0, 4));
        assertArrayEquals(new byte[]{20, 10, 10, 10, 10, 30}, second.deltas[0]);
        assertEquals(1, second.versions[1]);
        assertSame(first.data[1], second.data[1]);
    }

    @Test
    void largeChangesFallBackToFullTiles() {
        RenderedFrame first = RenderedFrame.next(null, tiles, fresh((byte) 10), 1);
        RenderedFrame second = RenderedFrame.next(first, tiles, fresh((byte) 11), 2);
        assertNull(second.deltas[0]);
        assertEquals(2, second.versions[0]);
        assertEquals(1, second.previousVersions[0]);
    }

    @Test
    void unchangedTilesKeepTheirLastDelta() {
        RenderedFrame first = RenderedFrame.next(null, tiles, fresh((byte) 10), 1);
        byte[][] second = fresh((byte) 10);
        second[1][0] = 99;
        RenderedFrame changed = RenderedFrame.next(first, tiles, second, 2);
        byte[][] third = fresh((byte) 10);
        third[1][0] = 99;
        RenderedFrame same = RenderedFrame.next(changed, tiles, third, 3);
        assertEquals(2, same.versions[1]);
        assertEquals(1, same.previousVersions[1]);
        assertSame(changed.deltas[1], same.deltas[1]);
    }
}
