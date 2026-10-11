package com.packetscreen;

import org.bukkit.command.CommandSender;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

final class VideoPlayer implements Runnable {

    private enum Outcome { STOPPED, ENDED, RECONFIGURE, RESTART, LOOP }

    private final PacketScreenPlugin plugin;
    private final VirtualScreen screen;
    private final Object lock = new Object();
    private volatile CommandSender requester;
    private volatile boolean running = true;
    private volatile boolean paused;
    private volatile boolean reconfigure;
    private volatile boolean restart;
    private volatile VideoDecoder decoder;
    private volatile double position;
    private Thread thread;

    VideoPlayer(PacketScreenPlugin plugin, VirtualScreen screen, CommandSender requester) {
        this.plugin = plugin;
        this.screen = screen;
        this.requester = requester;
    }

    VirtualScreen screen() {
        return screen;
    }

    void start() {
        thread = new Thread(this, "PacketScreen-Video-" + screen.name);
        thread.setDaemon(true);
        thread.start();
    }

    boolean isPaused() {
        return paused;
    }

    boolean active() {
        return running;
    }

    void pause() {
        paused = true;
        wake();
    }

    void resume(CommandSender sender) {
        requester = sender;
        paused = false;
        wake();
    }

    void requestReconfigure() {
        reconfigure = true;
        wake();
    }

    void requestRestart(CommandSender sender) {
        requester = sender;
        paused = false;
        restart = true;
        wake();
    }

    void stop() {
        running = false;
        VideoDecoder current = decoder;
        if (current != null) {
            current.close();
        }
        wake();
    }

    void join(long millis) {
        Thread current = thread;
        if (current != null && current != Thread.currentThread()) {
            try {
                current.join(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void wake() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    @Override
    public void run() {
        ScreenManager manager = plugin.screens();
        try {
            screen.state = ScreenState.LOADING;
            Settings settings = plugin.config().settings();
            if (!plugin.ffmpegAvailable()) {
                throw new MediaException(MediaException.Reason.FFMPEG_MISSING);
            }
            File file = plugin.videos().obtain(screen.url, () -> !running);
            if (!running) {
                return;
            }
            VideoDecoder.Probe probe = VideoDecoder.probe(settings.ffmpegPath, file, plugin.timer());
            if (probe.width > settings.videoMaxSourceWidth || probe.height > settings.videoMaxSourceHeight) {
                throw new MediaException(MediaException.Reason.RESOLUTION, "width", probe.width, "height", probe.height);
            }
            if (probe.duration > settings.videoMaxDurationSeconds) {
                throw new MediaException(MediaException.Reason.DURATION, "max", settings.videoMaxDurationSeconds);
            }
            screen.sourceWidth = probe.width;
            screen.sourceHeight = probe.height;
            manager.scheduleSave();
            double offset = 0;
            boolean announce = true;
            while (running) {
                Outcome outcome = session(file, probe, offset, announce);
                announce = false;
                if (outcome == Outcome.RECONFIGURE) {
                    offset = position;
                } else if (outcome == Outcome.RESTART) {
                    offset = 0;
                    plugin.notify(requester, "video-restarted", "name", screen.name);
                } else if (outcome == Outcome.LOOP) {
                    offset = 0;
                } else if (outcome == Outcome.ENDED) {
                    running = false;
                    manager.videoEnded(screen, this, requester);
                    return;
                } else {
                    return;
                }
            }
        } catch (Throwable error) {
            if (running) {
                manager.videoFailed(screen, this, MediaException.of(error), requester);
            }
        } finally {
            VideoDecoder current = decoder;
            if (current != null) {
                current.close();
            }
            manager.playerFinished(this);
        }
    }

    private Outcome session(File file, VideoDecoder.Probe probe, double offset, boolean announce)
            throws MediaException, IOException, InterruptedException {
        Settings settings = plugin.config().settings();
        MapPalette palette = plugin.palette();
        int fps = Math.max(1, screen.fps);
        int sequence = screen.nextSequence();
        ScreenGeometry geometry = screen.computeGeometry(settings);
        double remaining = settings.videoMaxDurationSeconds - offset;
        VideoDecoder session = VideoDecoder.start(settings.ffmpegPath, file, probe, offset, remaining, fps, geometry,
                settings.decoderThreads, plugin.videos().log(screen.url));
        decoder = session;
        try {
            if (!running) {
                return Outcome.STOPPED;
            }
            byte[] frame = new byte[geometry.destWidth * geometry.destHeight * 3];
            byte background = FrameProcessor.background(palette, settings);
            byte[] canvas = new byte[geometry.effectiveWidth * geometry.effectiveHeight];
            Arrays.fill(canvas, background);
            byte[][] scratch = FrameProcessor.allocate(geometry.columns * geometry.rows);
            long frameNanos = 1_000_000_000L / fps;
            long base = System.nanoTime();
            long consumed = 0;
            boolean first = true;
            while (running) {
                if (restart) {
                    restart = false;
                    return Outcome.RESTART;
                }
                if (reconfigure) {
                    reconfigure = false;
                    position = offset + consumed / (double) fps;
                    return Outcome.RECONFIGURE;
                }
                if (paused) {
                    screen.state = ScreenState.PAUSED;
                    long pausedAt = System.nanoTime();
                    synchronized (lock) {
                        while (paused && running && !restart && !reconfigure) {
                            lock.wait(500);
                        }
                    }
                    base += System.nanoTime() - pausedAt;
                    if (running && !paused) {
                        screen.state = ScreenState.PLAYING;
                    }
                    continue;
                }
                long now = System.nanoTime();
                long target = (now - base) / frameNanos;
                if (consumed > target) {
                    long wait = base + consumed * frameNanos - now;
                    synchronized (lock) {
                        if (wait > 0 && running && !paused && !restart && !reconfigure) {
                            lock.wait(wait / 1_000_000L, (int) (wait % 1_000_000L));
                        }
                    }
                    continue;
                }
                while (consumed <= target) {
                    if (!session.read(frame)) {
                        if (consumed == 0 && offset == 0) {
                            if (plugin.config().settings().debug) {
                                plugin.getLogger().warning("FFmpeg produced no frames for " + screen.name + ": " + session.errorOutput());
                            }
                            throw new MediaException(MediaException.Reason.DECODE);
                        }
                        return screen.loop ? Outcome.LOOP : Outcome.ENDED;
                    }
                    consumed++;
                }
                FrameProcessor.videoFrame(frame, geometry, canvas, palette, settings.videoDithering, background, scratch);
                RenderedFrame published = plugin.screens().publish(screen, sequence, geometry, scratch, ScreenState.PLAYING);
                if (published == null) {
                    return Outcome.STOPPED;
                }
                for (int tile = 0; tile < scratch.length; tile++) {
                    if (published.data[tile] == scratch[tile]) {
                        scratch[tile] = new byte[RenderedFrame.TILE_AREA];
                    }
                }
                if (first) {
                    first = false;
                    if (announce) {
                        plugin.notify(requester, "video-started", "name", screen.name,
                                "source", probe.width + "x" + probe.height, "fps", fps);
                    }
                }
            }
            return Outcome.STOPPED;
        } finally {
            decoder = null;
            session.close();
        }
    }
}
