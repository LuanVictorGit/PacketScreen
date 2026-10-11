package com.packetscreen;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class VideoDecoder implements Closeable {

    static final class Probe {
        final String demuxer;
        final int width;
        final int height;
        final double duration;

        Probe(String demuxer, int width, int height, double duration) {
            this.demuxer = demuxer;
            this.width = width;
            this.height = height;
            this.duration = duration;
        }
    }

    private static final Pattern DURATION = Pattern.compile("Duration: (\\d+):(\\d{2}):(\\d{2}(?:\\.\\d+)?)");
    private static final Pattern STREAM = Pattern.compile("Stream #\\d+:\\d+.*?: Video: .*?[ ,](\\d{2,5})x(\\d{2,5})[ ,\\[]");
    private static final Pattern ROTATION = Pattern.compile("(?:rotation of (-?\\d+(?:\\.\\d+)?) degrees|rotate\\s*:\\s*(-?\\d+))");

    private final Process process;
    private final InputStream input;
    private final File log;

    private VideoDecoder(Process process, File log) {
        this.process = process;
        this.input = process.getInputStream();
        this.log = log;
    }

    static boolean available(String ffmpeg, ScheduledExecutorService timer) {
        try {
            Process process = new ProcessBuilder(ffmpeg, "-hide_banner", "-version").redirectErrorStream(true).start();
            process.getOutputStream().close();
            String output = drain(process, timer, 10000, 4096);
            return output.contains("ffmpeg") && process.exitValue() == 0;
        } catch (IOException | IllegalThreadStateException | MediaException e) {
            return false;
        }
    }

    static Probe probe(String ffmpeg, File file, ScheduledExecutorService timer) throws MediaException {
        String demuxer = demuxer(file);
        List<String> command = new ArrayList<>();
        command.add(ffmpeg);
        command.add("-hide_banner");
        command.add("-nostdin");
        command.add("-protocol_whitelist");
        command.add("file");
        command.add("-f");
        command.add(demuxer);
        command.add("-i");
        command.add("file:" + file.getAbsolutePath());
        String output;
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            output = drain(process, timer, 20000, 262144);
        } catch (IOException e) {
            throw new MediaException(MediaException.Reason.FFMPEG_MISSING, e);
        }
        Matcher stream = STREAM.matcher(output);
        if (!stream.find()) {
            throw new MediaException(MediaException.Reason.DECODE);
        }
        int width = Integer.parseInt(stream.group(1));
        int height = Integer.parseInt(stream.group(2));
        Matcher rotation = ROTATION.matcher(output);
        if (rotation.find()) {
            String value = rotation.group(1) != null ? rotation.group(1) : rotation.group(2);
            long degrees = Math.round(Math.abs(Double.parseDouble(value))) % 180;
            if (degrees == 90) {
                int swap = width;
                width = height;
                height = swap;
            }
        }
        double duration = -1;
        Matcher time = DURATION.matcher(output);
        if (time.find()) {
            duration = Integer.parseInt(time.group(1)) * 3600 + Integer.parseInt(time.group(2)) * 60
                    + Double.parseDouble(time.group(3));
        }
        return new Probe(demuxer, width, height, duration);
    }

    static VideoDecoder start(String ffmpeg, File file, Probe probe, double start, double maxSeconds, int fps,
                              ScreenGeometry geometry, int threads, File log) throws MediaException {
        List<String> command = new ArrayList<>();
        command.add(ffmpeg);
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.add("-nostdin");
        command.add("-threads");
        command.add(String.valueOf(threads));
        command.add("-filter_threads");
        command.add("1");
        command.add("-protocol_whitelist");
        command.add("file");
        command.add("-f");
        command.add(probe.demuxer);
        if (start > 0) {
            command.add("-ss");
            command.add(String.format(Locale.ROOT, "%.3f", start));
        }
        command.add("-i");
        command.add("file:" + file.getAbsolutePath());
        command.add("-t");
        command.add(String.format(Locale.ROOT, "%.3f", Math.max(0.1, maxSeconds)));
        command.add("-map");
        command.add("0:v:0");
        command.add("-an");
        command.add("-sn");
        command.add("-dn");
        StringBuilder filter = new StringBuilder("fps=").append(fps);
        int cropWidth = Math.max(1, (int) Math.round(geometry.cropWidth));
        int cropHeight = Math.max(1, (int) Math.round(geometry.cropHeight));
        if (cropWidth < geometry.sourceWidth || cropHeight < geometry.sourceHeight) {
            filter.append(",crop=").append(cropWidth).append(':').append(cropHeight).append(':')
                    .append((int) Math.round(geometry.cropX)).append(':').append((int) Math.round(geometry.cropY));
        }
        filter.append(",scale=").append(geometry.destWidth).append(':').append(geometry.destHeight)
                .append(":flags=bilinear,format=rgb24");
        command.add("-vf");
        command.add(filter.toString());
        command.add("-f");
        command.add("rawvideo");
        command.add("-pix_fmt");
        command.add("rgb24");
        command.add("pipe:1");
        try {
            Process process = new ProcessBuilder(command).redirectError(log).start();
            process.getOutputStream().close();
            return new VideoDecoder(process, log);
        } catch (IOException e) {
            throw new MediaException(MediaException.Reason.FFMPEG_MISSING, e);
        }
    }

    boolean read(byte[] frame) throws IOException {
        int offset = 0;
        while (offset < frame.length) {
            int read = input.read(frame, offset, frame.length - offset);
            if (read < 0) {
                return false;
            }
            offset += read;
        }
        return true;
    }

    String errorOutput() {
        if (log == null || !log.isFile()) {
            return "";
        }
        try (InputStream in = new FileInputStream(log)) {
            byte[] data = new byte[(int) Math.min(log.length(), 2048)];
            int read = in.read(data);
            return read <= 0 ? "" : new String(data, 0, read, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        try {
            input.close();
        } catch (IOException ignored) {
        }
    }

    static String demuxer(File file) throws MediaException {
        byte[] head = new byte[512];
        int length = 0;
        try (InputStream in = new FileInputStream(file)) {
            int read;
            while (length < head.length && (read = in.read(head, length, head.length - length)) != -1) {
                length += read;
            }
        } catch (IOException e) {
            throw new MediaException(MediaException.Reason.DECODE, e);
        }
        return demuxer(head, length);
    }

    static boolean isVideo(byte[] head, int length) {
        try {
            return !demuxer(head, length).equals("gif");
        } catch (MediaException e) {
            return false;
        }
    }

    static String demuxer(byte[] head, int length) throws MediaException {
        if (length >= 12) {
            String box = new String(head, 4, 4, StandardCharsets.ISO_8859_1);
            if (box.equals("ftyp") || box.equals("moov") || box.equals("mdat") || box.equals("free") || box.equals("wide")) {
                return "mov";
            }
            if ((head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45 && (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3) {
                return "matroska";
            }
            String riff = new String(head, 0, 4, StandardCharsets.ISO_8859_1);
            String kind = new String(head, 8, 4, StandardCharsets.ISO_8859_1);
            if (riff.equals("RIFF") && kind.equals("AVI ")) {
                return "avi";
            }
            if (riff.startsWith("GIF8")) {
                return "gif";
            }
            if (riff.startsWith("FLV")) {
                return "flv";
            }
            if (riff.equals("OggS")) {
                return "ogg";
            }
            if (head[0] == 0 && head[1] == 0 && head[2] == 1 && (head[3] & 0xFF) == 0xBA) {
                return "mpeg";
            }
        }
        if (length >= 377 && head[0] == 0x47 && head[188] == 0x47 && head[376] == 0x47) {
            return "mpegts";
        }
        if (ImageLoader.isImage(head, length)) {
            throw new MediaException(MediaException.Reason.IMAGE_SOURCE);
        }
        int i = 0;
        while (i < length && Character.isWhitespace(head[i])) {
            i++;
        }
        if (i < length && head[i] == '<') {
            throw new MediaException(MediaException.Reason.WEB_PAGE);
        }
        throw new MediaException(MediaException.Reason.UNSUPPORTED_FORMAT);
    }

    private static String drain(Process process, ScheduledExecutorService timer, long timeoutMs, int limit)
            throws IOException, MediaException {
        ScheduledFuture<?> killer = timer.schedule(process::destroyForcibly, timeoutMs, TimeUnit.MILLISECONDS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = process.getInputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() < limit) {
                    out.write(buffer, 0, Math.min(read, limit - out.size()));
                }
            }
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new MediaException(MediaException.Reason.CANCELLED, e);
        } finally {
            killer.cancel(false);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
