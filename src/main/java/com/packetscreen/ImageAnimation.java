package com.packetscreen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

final class ImageAnimation implements Runnable {

    private final PacketScreenPlugin plugin;
    private final VirtualScreen screen;
    private final int sequence;
    private final ScreenGeometry geometry;
    private final byte[][][] frames;
    private final long[] ends;
    private final long step;
    private long started;
    private int current;

    private ImageAnimation(PacketScreenPlugin plugin, VirtualScreen screen, int sequence, ScreenGeometry geometry,
                           byte[][][] frames, long[] ends, long step) {
        this.plugin = plugin;
        this.screen = screen;
        this.sequence = sequence;
        this.geometry = geometry;
        this.frames = frames;
        this.ends = ends;
        this.step = step;
    }

    static ImageAnimation render(PacketScreenPlugin plugin, VirtualScreen screen, int sequence, byte[] data,
                                 ImageLoader.Header header, ScreenGeometry geometry, Settings settings) throws MediaException {
        MapPalette palette = plugin.palette();
        long perFrame = (long) geometry.columns * geometry.rows * RenderedFrame.TILE_AREA;
        int keep = (int) Math.max(1, Math.min(header.frames, settings.animationMaxBytes / Math.max(1, perFrame)));
        int stride = (header.frames + keep - 1) / keep;
        List<byte[][]> frames = new ArrayList<>();
        List<Long> delays = new ArrayList<>();
        int[] index = {0};
        AnimationDecoder.decode(data, header.format, (argb, width, height, delay) -> {
            if (!screen.isCurrent(sequence) || screen.removed) {
                throw new MediaException(MediaException.Reason.CANCELLED);
            }
            if (index[0]++ % stride == 0) {
                frames.add(FrameProcessor.renderImage(argb, width, height, geometry, palette, settings, settings.videoDithering));
                delays.add((long) delay);
            } else {
                delays.set(delays.size() - 1, delays.get(delays.size() - 1) + delay);
            }
        });
        if (frames.isEmpty()) {
            throw new MediaException(MediaException.Reason.DECODE);
        }
        long[] ends = new long[frames.size()];
        long total = 0;
        for (int i = 0; i < ends.length; i++) {
            total += delays.get(i);
            ends[i] = total;
        }
        return new ImageAnimation(plugin, screen, sequence, geometry, frames.toArray(new byte[0][][]), ends,
                Math.max(1, 1000L / Math.max(1, settings.maxFps)));
    }

    int frames() {
        return frames.length;
    }

    byte[][] first() {
        return frames[0];
    }

    void start() {
        started = System.nanoTime();
        current = 0;
        if (frames.length > 1) {
            schedule(Math.max(step, ends[0]));
        }
    }

    @Override
    public void run() {
        if (!screen.isCurrent(sequence) || screen.removed) {
            return;
        }
        long elapsed = ((System.nanoTime() - started) / 1_000_000L) % ends[ends.length - 1];
        int index = frameAt(elapsed);
        if (index != current) {
            try {
                if (plugin.screens().publish(screen, sequence, geometry, frames[index], ScreenState.READY) == null) {
                    return;
                }
            } catch (MediaException e) {
                return;
            }
            current = index;
        }
        schedule(Math.max(step, ends[index] - elapsed));
    }

    private int frameAt(long elapsed) {
        int low = 0;
        int high = ends.length - 1;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (ends[middle] > elapsed) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return low;
    }

    private void schedule(long delay) {
        try {
            plugin.timer().schedule(this::dispatch, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void dispatch() {
        try {
            plugin.processing().execute(this);
        } catch (RejectedExecutionException ignored) {
        }
    }
}
