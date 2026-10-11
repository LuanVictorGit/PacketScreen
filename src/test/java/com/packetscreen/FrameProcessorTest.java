package com.packetscreen;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameProcessorTest {

    private final MapPalette palette = new MapPalette(62);

    @Test
    void resampleKeepsSolidColors() {
        int[] source = new int[100 * 50];
        Arrays.fill(source, 0xFFCC2211);
        int[] result = FrameProcessor.resample(source, 100, 50, 0, 0, 100, 50, 10, 5);
        for (int pixel : result) {
            assertEquals(0xFF, pixel >>> 24);
            assertEquals(0xCC, (pixel >> 16) & 0xFF, 1);
            assertEquals(0x22, (pixel >> 8) & 0xFF, 1);
            assertEquals(0x11, pixel & 0xFF, 1);
        }
    }

    @Test
    void resampleRespectsTransparency() {
        int[] source = new int[40 * 10];
        for (int y = 0; y < 10; y++) {
            for (int x = 20; x < 40; x++) {
                source[y * 40 + x] = 0xFF00FF00;
            }
        }
        int[] result = FrameProcessor.resample(source, 40, 10, 0, 0, 40, 10, 4, 1);
        assertEquals(0, result[0] >>> 24);
        assertEquals(0xFF, result[3] >>> 24);
        assertEquals(0xFF, (result[3] >> 8) & 0xFF);
    }

    @Test
    void contentIsAlignedToTheBottomLeftOfTheGrid() {
        ScreenGeometry geometry = ScreenGeometry.compute(1.5, 0.5, false, FitMode.STRETCH, 0, 0, 0, 0, 1.0, 1280, 1280);
        assertEquals(2, geometry.columns);
        assertEquals(1, geometry.rows);
        byte color = palette.match(255, 255, 255);
        byte[][] tiles = FrameProcessor.solid(geometry, color, (byte) 0);
        assertEquals(0, tiles[0][0]);
        assertEquals(color, tiles[0][127 * 128]);
        assertEquals(color, tiles[0][64 * 128 + 5]);
        assertEquals(0, tiles[0][63 * 128 + 5]);
        assertEquals(color, tiles[1][127 * 128 + 63]);
        assertEquals(0, tiles[1][127 * 128 + 64]);
    }

    @Test
    void transparentPixelsBecomeTheTransparentIndex() {
        int[] pixels = {0x00000000, 0xFFFFFFFF, 0x7F000000, 0x80FF0000};
        byte[] indices = FrameProcessor.quantize(pixels, 4, 1, palette, Settings.Dithering.NONE, (byte) 0);
        assertEquals(0, indices[0]);
        assertEquals(palette.match(255, 255, 255), indices[1]);
        assertEquals(0, indices[2]);
        assertTrue((indices[3] & 0xFF) >= 4);
    }

    @Test
    void floydSteinbergProducesValidIndices() {
        int[] pixels = new int[64 * 64];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 0xFF000000 | (i * 2654435761L > 0 ? (int) (i * 2654435761L) & 0xFFFFFF : i);
        }
        byte[] indices = FrameProcessor.quantize(pixels, 64, 64, palette, Settings.Dithering.FLOYD_STEINBERG, (byte) 0);
        for (byte index : indices) {
            assertTrue((index & 0xFF) >= 4 && (index & 0xFF) < 248);
        }
    }

    @Test
    void videoFramesFillOnlyTheDestinationRectangle() {
        ScreenGeometry geometry = ScreenGeometry.compute(2, 1, false, FitMode.FIT, 64, 64, 100, 100, 1.0, 1280, 1280);
        assertEquals(32, geometry.destWidth);
        assertEquals(64, geometry.destHeight);
        byte[] rgb = new byte[geometry.destWidth * geometry.destHeight * 3];
        for (int i = 0; i < rgb.length; i += 3) {
            rgb[i] = (byte) 255;
        }
        byte[] canvas = new byte[geometry.effectiveWidth * geometry.effectiveHeight];
        byte[][] tiles = FrameProcessor.allocate(geometry.columns * geometry.rows);
        FrameProcessor.videoFrame(rgb, geometry, canvas, palette, Settings.Dithering.NONE, (byte) 0, tiles);
        byte red = palette.match(255, 0, 0);
        assertEquals(0, tiles[0][64 * 128]);
        assertEquals(red, tiles[0][64 * 128 + 127]);
        assertEquals(red, tiles[1][127 * 128]);
        assertEquals(0, tiles[1][127 * 128 + 127]);
    }

    @Test
    void imagesAreRenderedWithLetterboxForFit() throws Exception {
        Settings settings = TestSupport.settings("rendering.image-dithering", "NONE");
        ScreenGeometry geometry = ScreenGeometry.compute(2, 1, false, FitMode.FIT, 0, 0, 100, 100, 1.0, 1280, 1280);
        int[] source = new int[100 * 100];
        Arrays.fill(source, 0xFF0000FF);
        byte[][] tiles = FrameProcessor.renderImage(source, 100, 100, geometry, palette, settings);
        byte blue = palette.match(0, 0, 255);
        assertEquals(0, tiles[0][64 * 128 + 10]);
        assertEquals(blue, tiles[0][100 * 128 + 127]);
        assertEquals(blue, tiles[1][100 * 128]);
    }
}
