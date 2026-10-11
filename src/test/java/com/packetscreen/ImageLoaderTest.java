package com.packetscreen;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageLoaderTest {

    private final Settings settings = TestSupport.settings();

    @Test
    void detectsFormatsBySignature() throws Exception {
        assertEquals(ImageLoader.Format.PNG, ImageLoader.detect(TestSupport.image("png", 4, 4, 0xFF112233)));
        assertEquals(ImageLoader.Format.JPEG, ImageLoader.detect(TestSupport.image("jpg", 4, 4, 0xFF112233)));
        assertEquals(ImageLoader.Format.GIF, ImageLoader.detect(TestSupport.image("gif", 4, 4, 0xFF112233)));
        assertEquals(ImageLoader.Format.BMP, ImageLoader.detect(TestSupport.image("bmp", 4, 4, 0xFF112233)));
        assertEquals(ImageLoader.Format.WEBP, ImageLoader.detect(TestSupport.WEBP_LOSSLESS));
    }

    @Test
    void rejectsPagesAndUnknownData() {
        MediaException page = assertThrows(MediaException.class,
                () -> ImageLoader.detect("  <!doctype html><html>".getBytes(StandardCharsets.UTF_8)));
        assertEquals(MediaException.Reason.WEB_PAGE, page.reason);
        MediaException unknown = assertThrows(MediaException.class, () -> ImageLoader.detect(new byte[]{1, 2, 3, 4, 5}));
        assertEquals(MediaException.Reason.UNSUPPORTED_FORMAT, unknown.reason);
    }

    @Test
    void enforcesSourceResolutionLimits() throws Exception {
        Settings strict = TestSupport.settings("image.max-source-width", 50);
        byte[] png = TestSupport.image("png", 100, 20, 0xFF000000);
        MediaException error = assertThrows(MediaException.class, () -> ImageLoader.header(png, strict));
        assertEquals(MediaException.Reason.RESOLUTION, error.reason);
        assertEquals(100, ImageLoader.header(png, settings).width);
    }

    @Test
    void decodesTransparencyAndWebP() throws Exception {
        byte[] png = TestSupport.image("png", 8, 8, 0x00000000);
        ImageLoader.Decoded transparent = ImageLoader.decode(png, ImageLoader.header(png, settings), 8, 8);
        assertEquals(0, transparent.pixels[0] >>> 24);

        ImageLoader.Header header = ImageLoader.header(TestSupport.WEBP_LOSSLESS, settings);
        assertEquals(8, header.width);
        assertEquals(6, header.height);
        ImageLoader.Decoded lossless = ImageLoader.decode(TestSupport.WEBP_LOSSLESS, header, 8, 6);
        int pixel = lossless.pixels[0];
        assertEquals(0x33, (pixel >> 16) & 0xFF, 2);
        assertEquals(0x66, (pixel >> 8) & 0xFF, 2);
        assertEquals(0xCC, pixel & 0xFF, 2);

        ImageLoader.Header lossyHeader = ImageLoader.header(TestSupport.WEBP_LOSSY, settings);
        ImageLoader.Decoded lossy = ImageLoader.decode(TestSupport.WEBP_LOSSY, lossyHeader, 16, 12);
        assertEquals(16, lossy.width);
        assertEquals(12, lossy.height);
    }

    @Test
    void largeImagesAreSubsampledWhileDecoding() throws Exception {
        byte[] png = TestSupport.image("png", 800, 800, 0xFF336699);
        ImageLoader.Decoded decoded = ImageLoader.decode(png, ImageLoader.header(png, settings), 50, 50);
        assertTrue(decoded.width <= 200 && decoded.width >= 100, "width " + decoded.width);
    }

    @Test
    void pointsToTheRightCommandWhenTheTypeIsWrong() throws Exception {
        byte[] mp4 = new byte[64];
        byte[] box = {0, 0, 0, 32, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
        System.arraycopy(box, 0, mp4, 0, box.length);
        MediaException video = assertThrows(MediaException.class, () -> ImageLoader.detect(mp4));
        assertEquals(MediaException.Reason.VIDEO_SOURCE, video.reason);
        byte[] png = TestSupport.image("png", 4, 4, 0xFF00FF00);
        MediaException image = assertThrows(MediaException.class, () -> VideoDecoder.demuxer(png, png.length));
        assertEquals(MediaException.Reason.IMAGE_SOURCE, image.reason);
        assertEquals("gif", VideoDecoder.demuxer(TestSupport.image("gif", 4, 4, 0xFF00FF00), 32));
    }
}
