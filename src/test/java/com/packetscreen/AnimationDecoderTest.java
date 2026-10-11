package com.packetscreen;

import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnimationDecoderTest {

    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;
    private static final int GREEN = 0xFF00FF00;
    private static final int YELLOW = 0xFFFFFF00;

    private static final byte[] WEBP = Base64.getDecoder().decode(
            "UklGRlgBAABXRUJQVlA4WAoAAAASAAAADwAADwAAQU5JTQYAAAD/////AABBTk1GdgAAAAAAAAAAAA8AAA8AAGQAAAJWUDhMXgAAAC8PwAMA"
                    + "N6CojRRoL8C/RjTwxoaiRlKYKzT/DhHBGwJJGxbymR9k7/H5D+D/UamZsmdXdwFVkW0lWAX+XwQsgCXwBvQP4ZAgov8TgD6GYMggWqeD5ZHt"
                    + "idbpAMGQ4VdBTk1GdgAAAAIAAAIAAAsAAAYAAGQAAABWUDhMXQAAAC8LgAEQP6CojRRoL8C/RjTwxoaiRlKYKzT/DhHBGwIBCkbabEh+mP8A"
                    + "BPh/q8RM2bOLu4CiSJKak7BjgfQ/CUiIFsC/hys0RPRfSJBg8oqFBL6PoL8rGC1Fn/F9AABBTk1GMAAAAAUAAAIAAAUAAAYAAGQAAABWUDhM"
                    + "FwAAAC8FgAEQDxAx//MfDIFAEtn+YENE9D80AA==");

    private static final class Frames implements AnimationDecoder.Sink {
        final List<int[]> pixels = new ArrayList<>();
        final List<Integer> delays = new ArrayList<>();
        int width;

        @Override
        public void frame(int[] argb, int width, int height, int delay) {
            this.width = width;
            pixels.add(argb.clone());
            delays.add(delay);
        }

        int pixel(int frame, int x, int y) {
            return pixels.get(frame)[y * width + x];
        }
    }

    private static byte[] gif() throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            writer.prepareWriteSequence(null);
            write(writer, solid(4, 4, RED), 0, 0, 5, "none");
            write(writer, solid(2, 2, BLUE), 2, 2, 10, "restoreToBackgroundColor");
            write(writer, solid(1, 1, GREEN), 0, 0, 0, "none");
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static BufferedImage solid(int width, int height, int argb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }

    private static void write(ImageWriter writer, BufferedImage image, int x, int y, int delay, String disposal) throws Exception {
        ImageWriteParam param = writer.getDefaultWriteParam();
        IIOMetadata metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), param);
        String format = metadata.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);
        IIOMetadataNode control = node(root, "GraphicControlExtension");
        control.setAttribute("disposalMethod", disposal);
        control.setAttribute("userInputFlag", "FALSE");
        control.setAttribute("transparentColorFlag", "FALSE");
        control.setAttribute("delayTime", String.valueOf(delay));
        control.setAttribute("transparentColorIndex", "0");
        IIOMetadataNode descriptor = node(root, "ImageDescriptor");
        descriptor.setAttribute("imageLeftPosition", String.valueOf(x));
        descriptor.setAttribute("imageTopPosition", String.valueOf(y));
        descriptor.setAttribute("imageWidth", String.valueOf(image.getWidth()));
        descriptor.setAttribute("imageHeight", String.valueOf(image.getHeight()));
        descriptor.setAttribute("interlaceFlag", "FALSE");
        metadata.setFromTree(format, root);
        writer.writeToSequence(new IIOImage(image, null, metadata), param);
    }

    private static IIOMetadataNode node(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equals(name)) {
                return (IIOMetadataNode) root.item(i);
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }

    @Test
    void gifFramesAreCompositedWithTheirDisposal() throws Exception {
        byte[] data = gif();
        ImageLoader.Header header = ImageLoader.header(data, TestSupport.settings());
        assertEquals(3, header.frames);
        assertEquals(4, header.width);
        Frames frames = new Frames();
        AnimationDecoder.decode(data, ImageLoader.Format.GIF, frames);
        assertEquals(3, frames.pixels.size());
        assertEquals(RED, frames.pixel(0, 3, 3));
        assertEquals(BLUE, frames.pixel(1, 3, 3));
        assertEquals(RED, frames.pixel(1, 1, 1));
        assertEquals(GREEN, frames.pixel(2, 0, 0));
        assertEquals(RED, frames.pixel(2, 1, 1));
        assertEquals(0, frames.pixel(2, 3, 3) >>> 24);
        assertEquals(50, (int) frames.delays.get(0));
        assertEquals(100, (int) frames.delays.get(1));
        assertEquals(100, (int) frames.delays.get(2));
    }

    @Test
    void webpPartialFramesAreDrawnOnTheCanvas() throws Exception {
        ImageLoader.Header header = ImageLoader.header(WEBP, TestSupport.settings());
        assertEquals(3, header.frames);
        assertEquals(16, header.width);
        assertEquals(16, header.height);
        Frames frames = new Frames();
        AnimationDecoder.decode(WEBP, ImageLoader.Format.WEBP, frames);
        assertEquals(3, frames.pixels.size());
        assertEquals(YELLOW, frames.pixel(0, 7, 7));
        assertEquals(BLUE, frames.pixel(1, 7, 7));
        assertEquals(YELLOW, frames.pixel(1, 13, 7));
        assertEquals(BLUE, frames.pixel(2, 13, 7));
        assertEquals(BLUE, frames.pixel(2, 0, 0));
        assertEquals(100, (int) frames.delays.get(2));
    }

    @Test
    void stillImagesAndLimitsAreRespected() throws Exception {
        assertEquals(1, ImageLoader.header(TestSupport.image("gif", 8, 8, 0xFF112233), TestSupport.settings()).frames);
        assertEquals(1, ImageLoader.header(TestSupport.WEBP_LOSSLESS, TestSupport.settings()).frames);
        MediaException limit = assertThrows(MediaException.class,
                () -> ImageLoader.header(gif(), TestSupport.settings("image.max-animation-frames", 2)));
        assertEquals(MediaException.Reason.FRAMES, limit.reason);
        assertEquals(100, AnimationDecoder.delay(0));
        assertEquals(100, AnimationDecoder.delay(10));
        assertEquals(40, AnimationDecoder.delay(40));
    }
}
