package com.packetscreen;

import java.util.Arrays;

final class FrameProcessor {

    private static final int[] BAYER = {
            0, 32, 8, 40, 2, 34, 10, 42,
            48, 16, 56, 24, 50, 18, 58, 26,
            12, 44, 4, 36, 14, 46, 6, 38,
            60, 28, 52, 20, 62, 30, 54, 22,
            3, 35, 11, 43, 1, 33, 9, 41,
            51, 19, 59, 27, 49, 17, 57, 25,
            15, 47, 7, 39, 13, 45, 5, 37,
            63, 31, 55, 23, 61, 29, 53, 21
    };

    private FrameProcessor() {
    }

    static byte[][] renderImage(int[] argb, int width, int height, ScreenGeometry geometry, MapPalette palette,
                                Settings settings) {
        return renderImage(argb, width, height, geometry, palette, settings, settings.imageDithering);
    }

    static byte[][] renderImage(int[] argb, int width, int height, ScreenGeometry geometry, MapPalette palette,
                                Settings settings, Settings.Dithering dithering) {
        int canvasWidth = geometry.effectiveWidth;
        int canvasHeight = geometry.effectiveHeight;
        double scaleX = width / (double) geometry.sourceWidth;
        double scaleY = height / (double) geometry.sourceHeight;
        int[] content = resample(argb, width, height, geometry.cropX * scaleX, geometry.cropY * scaleY,
                geometry.cropWidth * scaleX, geometry.cropHeight * scaleY, geometry.destWidth, geometry.destHeight);
        int[] canvas = new int[canvasWidth * canvasHeight];
        for (int y = 0; y < geometry.destHeight; y++) {
            System.arraycopy(content, y * geometry.destWidth, canvas, (geometry.destY + y) * canvasWidth + geometry.destX,
                    geometry.destWidth);
        }
        byte background = background(palette, settings);
        if (!settings.transparentBackground) {
            composite(canvas, settings.backgroundColor);
        }
        byte[] indices = quantize(canvas, canvasWidth, canvasHeight, palette, dithering, background);
        byte[][] tiles = allocate(geometry.columns * geometry.rows);
        toTiles(indices, canvasWidth, canvasHeight, geometry, background, tiles);
        return tiles;
    }

    static byte[][] solid(ScreenGeometry geometry, byte color, byte background) {
        byte[][] tiles = allocate(geometry.columns * geometry.rows);
        toTiles(new byte[]{color}, 1, 1, geometry, background, tiles);
        return tiles;
    }

    static byte background(MapPalette palette, Settings settings) {
        return settings.transparentBackground ? 0 : palette.matchRgb(settings.backgroundColor);
    }

    static byte[][] allocate(int count) {
        byte[][] tiles = new byte[count][];
        for (int i = 0; i < count; i++) {
            tiles[i] = new byte[RenderedFrame.TILE_AREA];
        }
        return tiles;
    }

    static void videoFrame(byte[] rgb, ScreenGeometry geometry, byte[] canvas, MapPalette palette,
                           Settings.Dithering dithering, byte background, byte[][] tiles) {
        int width = geometry.destWidth;
        int height = geometry.destHeight;
        int canvasWidth = geometry.effectiveWidth;
        if (dithering == Settings.Dithering.FLOYD_STEINBERG) {
            diffuseRgb(rgb, width, height, palette, canvas, canvasWidth, geometry.destX, geometry.destY);
        } else {
            boolean ordered = dithering == Settings.Dithering.ORDERED;
            for (int y = 0; y < height; y++) {
                int source = y * width * 3;
                int target = (geometry.destY + y) * canvasWidth + geometry.destX;
                for (int x = 0; x < width; x++) {
                    int r = rgb[source] & 0xFF;
                    int g = rgb[source + 1] & 0xFF;
                    int b = rgb[source + 2] & 0xFF;
                    source += 3;
                    if (ordered) {
                        int offset = (BAYER[(y & 7) << 3 | (x & 7)] - 32) >> 1;
                        r = clamp(r + offset);
                        g = clamp(g + offset);
                        b = clamp(b + offset);
                    }
                    canvas[target + x] = palette.match(r, g, b);
                }
            }
        }
        toTiles(canvas, canvasWidth, geometry.effectiveHeight, geometry, background, tiles);
    }

    static void toTiles(byte[] canvas, int canvasWidth, int canvasHeight, ScreenGeometry geometry, byte background,
                        byte[][] tiles) {
        int contentWidth = geometry.contentWidth;
        int contentHeight = geometry.contentHeight;
        int offsetY = geometry.rows * RenderedFrame.TILE - contentHeight;
        int[] xs = new int[contentWidth];
        for (int x = 0; x < contentWidth; x++) {
            xs[x] = (int) ((long) x * canvasWidth / contentWidth);
        }
        int[] ys = new int[contentHeight];
        for (int y = 0; y < contentHeight; y++) {
            ys[y] = (int) ((long) y * canvasHeight / contentHeight) * canvasWidth;
        }
        for (int row = 0; row < geometry.rows; row++) {
            for (int column = 0; column < geometry.columns; column++) {
                byte[] tile = tiles[row * geometry.columns + column];
                int startX = column * RenderedFrame.TILE;
                for (int py = 0; py < RenderedFrame.TILE; py++) {
                    int base = py * RenderedFrame.TILE;
                    int cy = row * RenderedFrame.TILE + py - offsetY;
                    if (cy < 0 || cy >= contentHeight) {
                        Arrays.fill(tile, base, base + RenderedFrame.TILE, background);
                        continue;
                    }
                    int line = ys[cy];
                    for (int px = 0; px < RenderedFrame.TILE; px++) {
                        int gx = startX + px;
                        tile[base + px] = gx < contentWidth ? canvas[line + xs[gx]] : background;
                    }
                }
            }
        }
    }

    static byte[] quantize(int[] argb, int width, int height, MapPalette palette, Settings.Dithering dithering,
                           byte transparent) {
        byte[] out = new byte[width * height];
        if (dithering == Settings.Dithering.FLOYD_STEINBERG) {
            int[] current = new int[(width + 2) * 3];
            int[] next = new int[(width + 2) * 3];
            for (int y = 0; y < height; y++) {
                boolean forward = (y & 1) == 0;
                int step = forward ? 3 : -3;
                for (int i = 0; i < width; i++) {
                    int x = forward ? i : width - 1 - i;
                    int index = y * width + x;
                    int pixel = argb[index];
                    int e = (x + 1) * 3;
                    if ((pixel >>> 24) < 128) {
                        out[index] = transparent;
                        continue;
                    }
                    int r = clamp(((pixel >> 16) & 0xFF) + current[e] / 16);
                    int g = clamp(((pixel >> 8) & 0xFF) + current[e + 1] / 16);
                    int b = clamp((pixel & 0xFF) + current[e + 2] / 16);
                    int color = palette.match(r, g, b) & 0xFF;
                    out[index] = (byte) color;
                    int er = r - palette.red(color);
                    int eg = g - palette.green(color);
                    int eb = b - palette.blue(color);
                    current[e + step] += er * 7;
                    current[e + step + 1] += eg * 7;
                    current[e + step + 2] += eb * 7;
                    next[e - step] += er * 3;
                    next[e - step + 1] += eg * 3;
                    next[e - step + 2] += eb * 3;
                    next[e] += er * 5;
                    next[e + 1] += eg * 5;
                    next[e + 2] += eb * 5;
                    next[e + step] += er;
                    next[e + step + 1] += eg;
                    next[e + step + 2] += eb;
                }
                int[] swap = current;
                current = next;
                next = swap;
                Arrays.fill(next, 0);
            }
            return out;
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                int pixel = argb[index];
                if ((pixel >>> 24) < 128) {
                    out[index] = transparent;
                    continue;
                }
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                if (dithering == Settings.Dithering.ORDERED) {
                    int offset = (BAYER[(y & 7) << 3 | (x & 7)] - 32) >> 1;
                    r = clamp(r + offset);
                    g = clamp(g + offset);
                    b = clamp(b + offset);
                }
                out[index] = palette.match(r, g, b);
            }
        }
        return out;
    }

    private static void diffuseRgb(byte[] rgb, int width, int height, MapPalette palette, byte[] canvas,
                                   int canvasWidth, int offsetX, int offsetY) {
        int[] current = new int[(width + 2) * 3];
        int[] next = new int[(width + 2) * 3];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int source = (y * width + x) * 3;
                int e = (x + 1) * 3;
                int r = clamp((rgb[source] & 0xFF) + current[e] / 16);
                int g = clamp((rgb[source + 1] & 0xFF) + current[e + 1] / 16);
                int b = clamp((rgb[source + 2] & 0xFF) + current[e + 2] / 16);
                int color = palette.match(r, g, b) & 0xFF;
                canvas[(offsetY + y) * canvasWidth + offsetX + x] = (byte) color;
                int er = r - palette.red(color);
                int eg = g - palette.green(color);
                int eb = b - palette.blue(color);
                current[e + 3] += er * 7;
                current[e + 4] += eg * 7;
                current[e + 5] += eb * 7;
                next[e - 3] += er * 3;
                next[e - 2] += eg * 3;
                next[e - 1] += eb * 3;
                next[e] += er * 5;
                next[e + 1] += eg * 5;
                next[e + 2] += eb * 5;
                next[e + 3] += er;
                next[e + 4] += eg;
                next[e + 5] += eb;
            }
            int[] swap = current;
            current = next;
            next = swap;
            Arrays.fill(next, 0);
        }
    }

    static void composite(int[] argb, int background) {
        int br = (background >> 16) & 0xFF;
        int bg = (background >> 8) & 0xFF;
        int bb = background & 0xFF;
        for (int i = 0; i < argb.length; i++) {
            int pixel = argb[i];
            int alpha = pixel >>> 24;
            if (alpha == 255) {
                continue;
            }
            int inverse = 255 - alpha;
            int r = (((pixel >> 16) & 0xFF) * alpha + br * inverse) / 255;
            int g = (((pixel >> 8) & 0xFF) * alpha + bg * inverse) / 255;
            int b = ((pixel & 0xFF) * alpha + bb * inverse) / 255;
            argb[i] = 0xFF000000 | r << 16 | g << 8 | b;
        }
    }

    static int[] resample(int[] source, int sourceWidth, int sourceHeight, double cropX, double cropY,
                          double cropWidth, double cropHeight, int width, int height) {
        Filter horizontal = Filter.create(sourceWidth, cropX, cropWidth, width);
        Filter vertical = Filter.create(sourceHeight, cropY, cropHeight, height);
        float[][] rows = new float[sourceHeight][];
        int released = 0;
        int[] out = new int[width * height];
        float[] accumulator = new float[width * 4];
        for (int y = 0; y < height; y++) {
            int start = vertical.start[y];
            int count = vertical.count[y];
            int weights = vertical.offset[y];
            while (released < start) {
                rows[released++] = null;
            }
            Arrays.fill(accumulator, 0f);
            for (int k = 0; k < count; k++) {
                int sourceRow = start + k;
                float[] row = rows[sourceRow];
                if (row == null) {
                    row = horizontal.apply(source, sourceRow * sourceWidth, width);
                    rows[sourceRow] = row;
                }
                float weight = vertical.weights[weights + k];
                for (int i = 0; i < accumulator.length; i++) {
                    accumulator[i] += row[i] * weight;
                }
            }
            for (int x = 0; x < width; x++) {
                int i = x * 4;
                float alpha = accumulator[i];
                if (alpha < 0.5f) {
                    continue;
                }
                int a = clamp(Math.round(alpha));
                int r = clamp(Math.round(accumulator[i + 1] / alpha));
                int g = clamp(Math.round(accumulator[i + 2] / alpha));
                int b = clamp(Math.round(accumulator[i + 3] / alpha));
                out[y * width + x] = a << 24 | r << 16 | g << 8 | b;
            }
        }
        return out;
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }

    private static final class Filter {

        final int[] start;
        final int[] count;
        final int[] offset;
        final float[] weights;

        private Filter(int[] start, int[] count, int[] offset, float[] weights) {
            this.start = start;
            this.count = count;
            this.offset = offset;
            this.weights = weights;
        }

        static Filter create(int size, double origin, double length, int out) {
            double scale = length / out;
            double support = Math.max(1.0, scale);
            int[] start = new int[out];
            int[] count = new int[out];
            int[] offset = new int[out];
            float[] weights = new float[out * ((int) Math.ceil(support) * 2 + 2)];
            int used = 0;
            for (int i = 0; i < out; i++) {
                double center = origin + (i + 0.5) * scale;
                int low = Math.max(0, (int) Math.floor(center - support));
                int high = Math.min(size - 1, (int) Math.ceil(center + support));
                double total = 0;
                int first = -1;
                int last = -1;
                for (int k = low; k <= high; k++) {
                    double w = 1.0 - Math.abs(k + 0.5 - center) / support;
                    if (w > 0) {
                        if (first < 0) {
                            first = k;
                        }
                        last = k;
                        total += w;
                    }
                }
                if (first < 0) {
                    first = Math.max(0, Math.min(size - 1, (int) Math.floor(center)));
                    last = first;
                }
                start[i] = first;
                count[i] = last - first + 1;
                offset[i] = used;
                for (int k = first; k <= last; k++) {
                    double w = total > 0 ? Math.max(0, 1.0 - Math.abs(k + 0.5 - center) / support) / total : 1.0;
                    weights[used++] = (float) w;
                }
            }
            return new Filter(start, count, offset, weights);
        }

        float[] apply(int[] source, int rowOffset, int width) {
            float[] row = new float[width * 4];
            for (int x = 0; x < width; x++) {
                float a = 0;
                float r = 0;
                float g = 0;
                float b = 0;
                int first = start[x];
                int n = count[x];
                int w = offset[x];
                for (int k = 0; k < n; k++) {
                    int pixel = source[rowOffset + first + k];
                    float weight = weights[w + k];
                    float pa = (pixel >>> 24) * weight;
                    a += pa;
                    r += ((pixel >> 16) & 0xFF) * pa;
                    g += ((pixel >> 8) & 0xFF) * pa;
                    b += (pixel & 0xFF) * pa;
                }
                int i = x * 4;
                row[i] = a;
                row[i + 1] = r;
                row[i + 2] = g;
                row[i + 3] = b;
            }
            return row;
        }
    }
}
