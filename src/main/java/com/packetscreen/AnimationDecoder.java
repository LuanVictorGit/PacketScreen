package com.packetscreen;

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

final class AnimationDecoder {

    interface Sink {
        void frame(int[] argb, int width, int height, int delay) throws MediaException;
    }

    static final class Info {
        final int width;
        final int height;
        final int frames;

        Info(int width, int height, int frames) {
            this.width = width;
            this.height = height;
            this.frames = frames;
        }
    }

    private static final int KEEP = 0;
    private static final int BACKGROUND = 1;
    private static final int PREVIOUS = 2;

    private AnimationDecoder() {
    }

    static int delay(int milliseconds) {
        return milliseconds < 20 ? 100 : milliseconds;
    }

    static Info gif(ImageReader reader) throws IOException {
        int frames = reader.getNumImages(true);
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        IIOMetadata stream = reader.getStreamMetadata();
        if (stream != null) {
            Node screen = child(stream.getAsTree("javax_imageio_gif_stream_1.0"), "LogicalScreenDescriptor");
            int logicalWidth = number(screen, "logicalScreenWidth");
            int logicalHeight = number(screen, "logicalScreenHeight");
            if (logicalWidth > 0 && logicalHeight > 0) {
                width = logicalWidth;
                height = logicalHeight;
            }
        }
        return new Info(width, height, frames);
    }

    static Info webp(byte[] data) {
        List<int[]> frames = new ArrayList<>();
        int[] canvas = webpChunks(data, frames);
        return canvas == null || frames.size() < 2 ? null : new Info(canvas[0], canvas[1], frames.size());
    }

    static void decode(byte[] data, ImageLoader.Format format, Sink sink) throws MediaException {
        try {
            if (format == ImageLoader.Format.GIF) {
                decodeGif(data, sink);
            } else if (format == ImageLoader.Format.WEBP) {
                decodeWebp(data, sink);
            } else {
                throw new MediaException(MediaException.Reason.UNSUPPORTED_FORMAT);
            }
        } catch (IOException | RuntimeException e) {
            throw new MediaException(MediaException.Reason.DECODE, e);
        }
    }

    private static void decodeGif(byte[] data, Sink sink) throws IOException, MediaException {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
        if (!readers.hasNext()) {
            throw new MediaException(MediaException.Reason.UNSUPPORTED_FORMAT);
        }
        ImageReader reader = readers.next();
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            reader.setInput(input, false, false);
            Info info = gif(reader);
            BufferedImage canvas = new BufferedImage(info.width, info.height, BufferedImage.TYPE_INT_ARGB);
            int[] pixels = ((DataBufferInt) canvas.getRaster().getDataBuffer()).getData();
            int[] saved = null;
            int disposal = KEEP;
            int[] area = new int[4];
            for (int i = 0; i < info.frames; i++) {
                BufferedImage frame = reader.read(i);
                Node metadata = reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
                Node descriptor = child(metadata, "ImageDescriptor");
                Node control = child(metadata, "GraphicControlExtension");
                if (disposal == BACKGROUND) {
                    clear(pixels, info.width, info.height, area);
                } else if (disposal == PREVIOUS && saved != null) {
                    System.arraycopy(saved, 0, pixels, 0, pixels.length);
                }
                String method = text(control, "disposalMethod");
                disposal = "restoreToBackgroundColor".equals(method) ? BACKGROUND : "restoreToPrevious".equals(method) ? PREVIOUS : KEEP;
                if (disposal == PREVIOUS) {
                    if (saved == null) {
                        saved = new int[pixels.length];
                    }
                    System.arraycopy(pixels, 0, saved, 0, pixels.length);
                }
                area[0] = number(descriptor, "imageLeftPosition");
                area[1] = number(descriptor, "imageTopPosition");
                area[2] = frame.getWidth();
                area[3] = frame.getHeight();
                Graphics2D graphics = canvas.createGraphics();
                graphics.drawImage(frame, area[0], area[1], null);
                graphics.dispose();
                sink.frame(pixels, info.width, info.height, delay(number(control, "delayTime") * 10));
            }
        } finally {
            reader.dispose();
        }
    }

    private static void decodeWebp(byte[] data, Sink sink) throws IOException, MediaException {
        List<int[]> frames = new ArrayList<>();
        int[] size = webpChunks(data, frames);
        if (size == null || frames.isEmpty()) {
            throw new MediaException(MediaException.Reason.DECODE);
        }
        ImageReader reader = new WebPImageReaderSpi().createReaderInstance(null);
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            reader.setInput(input, false, false);
            int count = Math.min(frames.size(), reader.getNumImages(true));
            BufferedImage canvas = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_ARGB);
            int[] pixels = ((DataBufferInt) canvas.getRaster().getDataBuffer()).getData();
            int[] previous = null;
            for (int i = 0; i < count; i++) {
                int[] frame = frames.get(i);
                if (previous != null && previous[6] == BACKGROUND) {
                    clear(pixels, size[0], size[1], previous);
                }
                BufferedImage image = reader.read(i);
                Graphics2D graphics = canvas.createGraphics();
                graphics.setComposite(frame[5] == 1 ? AlphaComposite.SrcOver : AlphaComposite.Src);
                graphics.drawImage(image, frame[0], frame[1], null);
                graphics.dispose();
                previous = frame;
                sink.frame(pixels, size[0], size[1], delay(frame[4]));
            }
        } finally {
            reader.dispose();
        }
    }

    private static int[] webpChunks(byte[] data, List<int[]> frames) {
        if (data.length < 30 || !"RIFF".equals(new String(data, 0, 4, StandardCharsets.ISO_8859_1))
                || !"WEBP".equals(new String(data, 8, 4, StandardCharsets.ISO_8859_1))) {
            return null;
        }
        int[] canvas = null;
        boolean animated = false;
        int position = 12;
        while (position + 8 <= data.length) {
            String type = new String(data, position, 4, StandardCharsets.ISO_8859_1);
            long size = (data[position + 4] & 0xFFL) | (data[position + 5] & 0xFFL) << 8 | (data[position + 6] & 0xFFL) << 16
                    | (data[position + 7] & 0xFFL) << 24;
            int body = position + 8;
            if (size > data.length - body) {
                break;
            }
            if (type.equals("VP8X") && size >= 10) {
                animated = (data[body] & 0x02) != 0;
                canvas = new int[]{u24(data, body + 4) + 1, u24(data, body + 7) + 1};
            } else if (type.equals("ANMF") && size >= 16) {
                int flags = data[body + 15];
                frames.add(new int[]{u24(data, body) * 2, u24(data, body + 3) * 2, u24(data, body + 6) + 1,
                        u24(data, body + 9) + 1, u24(data, body + 12), (flags & 0x02) == 0 ? 1 : 0,
                        (flags & 0x01) != 0 ? BACKGROUND : KEEP});
            }
            position = body + (int) size + (int) (size & 1);
        }
        return animated ? canvas : null;
    }

    private static void clear(int[] pixels, int width, int height, int[] area) {
        int left = Math.max(0, area[0]);
        int top = Math.max(0, area[1]);
        int right = Math.min(width, area[0] + area[2]);
        int bottom = Math.min(height, area[1] + area[3]);
        for (int y = top; y < bottom; y++) {
            Arrays.fill(pixels, y * width + left, Math.max(y * width + left, y * width + right), 0);
        }
    }

    private static int u24(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF) << 16;
    }

    private static Node child(Node node, String name) {
        if (node == null) {
            return null;
        }
        for (Node item = node.getFirstChild(); item != null; item = item.getNextSibling()) {
            if (name.equals(item.getNodeName())) {
                return item;
            }
        }
        return null;
    }

    private static String text(Node node, String attribute) {
        if (node == null) {
            return null;
        }
        NamedNodeMap attributes = node.getAttributes();
        Node value = attributes == null ? null : attributes.getNamedItem(attribute);
        return value == null ? null : value.getNodeValue();
    }

    private static int number(Node node, String attribute) {
        String value = text(node, attribute);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
