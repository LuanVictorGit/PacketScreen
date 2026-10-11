package com.packetscreen;

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

final class ImageLoader {

    enum Format { PNG, JPEG, GIF, BMP, WEBP }

    static final class Header {
        final Format format;
        final int width;
        final int height;
        final int frames;

        Header(Format format, int width, int height, int frames) {
            this.format = format;
            this.width = width;
            this.height = height;
            this.frames = frames;
        }
    }

    static final class Decoded {
        final int[] pixels;
        final int width;
        final int height;

        Decoded(int[] pixels, int width, int height) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
        }
    }

    private static final class Entry {
        final byte[] data;
        final long created;

        Entry(byte[] data, long created) {
            this.data = data;
            this.created = created;
        }
    }

    private static final class Failure {
        final MediaException exception;
        final long until;

        Failure(MediaException exception, long until) {
            this.exception = exception;
            this.until = until;
        }
    }

    private final Supplier<Settings> settings;
    private final HttpFetcher http;
    private final UploadStore uploads;
    private final Executor executor;
    private final Map<String, CompletableFuture<byte[]>> inFlight = new ConcurrentHashMap<>();
    private final Map<String, Failure> failures = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, Entry> cache = new LinkedHashMap<>(16, 0.75f, true);
    private long cacheBytes;

    ImageLoader(Supplier<Settings> settings, HttpFetcher http, UploadStore uploads, Executor executor) {
        this.settings = settings;
        this.http = http;
        this.uploads = uploads;
        this.executor = executor;
    }

    CompletableFuture<byte[]> fetch(String url, boolean refresh) {
        Settings current = settings.get();
        long now = System.currentTimeMillis();
        if (!refresh) {
            byte[] cached = cached(url, now, current);
            if (cached != null) {
                return CompletableFuture.completedFuture(cached);
            }
            Failure failure = failures.get(url);
            if (failure != null && failure.until > now) {
                CompletableFuture<byte[]> failed = new CompletableFuture<>();
                failed.completeExceptionally(failure.exception);
                return failed;
            }
        }
        failures.remove(url);
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        CompletableFuture<byte[]> existing = inFlight.putIfAbsent(url, future);
        if (existing != null) {
            return existing;
        }
        try {
            executor.execute(() -> download(url, future));
        } catch (RejectedExecutionException e) {
            inFlight.remove(url, future);
            future.completeExceptionally(new MediaException(MediaException.Reason.CANCELLED, e));
        }
        return future;
    }

    private void download(String url, CompletableFuture<byte[]> future) {
        try {
            byte[] data = UploadStore.isUpload(url) ? readUpload(url) : http.fetch(url, settings.get().imageMaxBytes, null);
            store(url, data, settings.get());
            inFlight.remove(url, future);
            future.complete(data);
        } catch (Throwable error) {
            MediaException exception = MediaException.of(error);
            long cooldown = settings.get().failureCooldownMs;
            if (cooldown > 0 && exception.reason != MediaException.Reason.CANCELLED) {
                failures.put(url, new Failure(exception, System.currentTimeMillis() + cooldown));
            }
            inFlight.remove(url, future);
            future.completeExceptionally(exception);
        }
    }

    private byte[] readUpload(String url) throws MediaException {
        File file = uploads.file(UploadStore.id(url));
        if (file == null) {
            throw new MediaException(MediaException.Reason.MISSING);
        }
        long limit = settings.get().imageMaxBytes;
        if (file.length() > limit) {
            throw new MediaException(MediaException.Reason.TOO_LARGE, "max", HttpFetcher.formatBytes(limit));
        }
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            throw new MediaException(MediaException.Reason.MISSING, e);
        }
    }

    void invalidate(String url) {
        synchronized (cache) {
            Entry removed = cache.remove(url);
            if (removed != null) {
                cacheBytes -= removed.data.length;
            }
        }
        failures.remove(url);
    }

    void clear() {
        synchronized (cache) {
            cache.clear();
            cacheBytes = 0;
        }
        failures.clear();
    }

    private byte[] cached(String url, long now, Settings settings) {
        synchronized (cache) {
            Entry entry = cache.get(url);
            if (entry == null) {
                return null;
            }
            if (now - entry.created > settings.cacheExpireMs) {
                cache.remove(url);
                cacheBytes -= entry.data.length;
                return null;
            }
            return entry.data;
        }
    }

    private void store(String url, byte[] data, Settings settings) {
        if (!settings.cacheEnabled || data.length > settings.cacheMaxBytes) {
            return;
        }
        synchronized (cache) {
            Entry previous = cache.put(url, new Entry(data, System.currentTimeMillis()));
            if (previous != null) {
                cacheBytes -= previous.data.length;
            }
            cacheBytes += data.length;
            Iterator<Map.Entry<String, Entry>> iterator = cache.entrySet().iterator();
            while (cacheBytes > settings.cacheMaxBytes && iterator.hasNext()) {
                Map.Entry<String, Entry> eldest = iterator.next();
                cacheBytes -= eldest.getValue().data.length;
                iterator.remove();
            }
        }
    }

    static Format detect(byte[] data) throws MediaException {
        return detect(data, data.length);
    }

    static Format detect(byte[] data, int length) throws MediaException {
        if (startsWith(data, length, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Format.PNG;
        }
        if (startsWith(data, length, 0, 0xFF, 0xD8, 0xFF)) {
            return Format.JPEG;
        }
        if (startsWith(data, length, 0, 'G', 'I', 'F', '8')) {
            return Format.GIF;
        }
        if (startsWith(data, length, 0, 'B', 'M')) {
            return Format.BMP;
        }
        if (startsWith(data, length, 0, 'R', 'I', 'F', 'F') && startsWith(data, length, 8, 'W', 'E', 'B', 'P')) {
            return Format.WEBP;
        }
        if (looksLikeText(data, length)) {
            throw new MediaException(MediaException.Reason.WEB_PAGE);
        }
        if (VideoDecoder.isVideo(data, length)) {
            throw new MediaException(MediaException.Reason.VIDEO_SOURCE);
        }
        throw new MediaException(MediaException.Reason.UNSUPPORTED_FORMAT);
    }

    static boolean isImage(byte[] data, int length) {
        return startsWith(data, length, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)
                || startsWith(data, length, 0, 0xFF, 0xD8, 0xFF)
                || startsWith(data, length, 0, 'B', 'M')
                || startsWith(data, length, 0, 'R', 'I', 'F', 'F') && startsWith(data, length, 8, 'W', 'E', 'B', 'P');
    }

    static Header header(byte[] data, Settings settings) throws MediaException {
        Format format = detect(data);
        ImageReader reader = reader(format);
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            reader.setInput(input, format != Format.GIF, format != Format.GIF);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            int frames = 1;
            AnimationDecoder.Info animation = format == Format.GIF ? AnimationDecoder.gif(reader)
                    : format == Format.WEBP ? AnimationDecoder.webp(data) : null;
            if (animation != null && animation.frames > 1) {
                width = animation.width;
                height = animation.height;
                frames = animation.frames;
            }
            if (width <= 0 || height <= 0 || width > settings.imageMaxSourceWidth || height > settings.imageMaxSourceHeight
                    || (long) width * height > settings.imageMaxSourcePixels) {
                throw new MediaException(MediaException.Reason.RESOLUTION, "width", width, "height", height);
            }
            if (frames > settings.animationMaxFrames) {
                throw new MediaException(MediaException.Reason.FRAMES, "frames", frames, "max", settings.animationMaxFrames);
            }
            return new Header(format, width, height, frames);
        } catch (IOException | RuntimeException e) {
            throw new MediaException(MediaException.Reason.DECODE, e);
        } finally {
            reader.dispose();
        }
    }

    static Decoded decode(byte[] data, Header header, int targetWidth, int targetHeight) throws MediaException {
        ImageReader reader = reader(header.format);
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            reader.setInput(input, true, true);
            ImageReadParam param = reader.getDefaultReadParam();
            int subsampling = Math.max(1, Math.min(header.width / Math.max(1, targetWidth * 2),
                    header.height / Math.max(1, targetHeight * 2)));
            if (subsampling > 1) {
                param.setSourceSubsampling(subsampling, subsampling, 0, 0);
            }
            BufferedImage image = reader.read(0, param);
            int width = image.getWidth();
            int height = image.getHeight();
            int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
            return new Decoded(pixels, width, height);
        } catch (IOException | RuntimeException e) {
            throw new MediaException(MediaException.Reason.DECODE, e);
        } finally {
            reader.dispose();
        }
    }

    private static ImageReader reader(Format format) throws MediaException {
        if (format == Format.WEBP) {
            try {
                return new WebPImageReaderSpi().createReaderInstance(null);
            } catch (RuntimeException | LinkageError e) {
                throw new MediaException(MediaException.Reason.UNSUPPORTED_FORMAT, e);
            }
        }
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format.name().toLowerCase(java.util.Locale.ROOT));
        if (!readers.hasNext()) {
            throw new MediaException(MediaException.Reason.UNSUPPORTED_FORMAT);
        }
        return readers.next();
    }

    private static boolean startsWith(byte[] data, int length, int offset, int... signature) {
        if (length < offset + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((data[offset + i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    static boolean looksLikeText(byte[] data, int length) {
        int limit = Math.min(length, 512);
        int i = 0;
        if (limit >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            i = 3;
        }
        while (i < limit && Character.isWhitespace(data[i])) {
            i++;
        }
        return i < limit && data[i] == '<';
    }
}
