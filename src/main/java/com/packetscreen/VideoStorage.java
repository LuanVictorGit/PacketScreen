package com.packetscreen;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

final class VideoStorage {

    private static final Pattern OWNED = Pattern.compile("ps-[0-9a-f]{40}\\.(video|part|log)");

    private final PacketScreenPlugin plugin;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    VideoStorage(PacketScreenPlugin plugin) {
        this.plugin = plugin;
    }

    File directory() {
        File configured = new File(plugin.config().settings().tempDirectory);
        File directory = configured.isAbsolute() ? configured : new File(plugin.getDataFolder(), configured.getPath());
        if (!directory.isDirectory() && !directory.mkdirs()) {
            plugin.getLogger().warning("Could not create the temporary directory " + directory.getAbsolutePath());
        }
        return directory;
    }

    File obtain(String url, BooleanSupplier cancelled) throws MediaException {
        if (UploadStore.isUpload(url)) {
            File upload = plugin.uploads().file(UploadStore.id(url));
            if (upload == null) {
                throw new MediaException(MediaException.Reason.MISSING);
            }
            long limit = plugin.config().settings().videoMaxBytes;
            if (upload.length() > limit) {
                throw new MediaException(MediaException.Reason.TOO_LARGE, "max", HttpFetcher.formatBytes(limit));
            }
            return upload;
        }
        String hash = hash(url);
        File directory = directory();
        File file = new File(directory, "ps-" + hash + ".video");
        synchronized (locks.computeIfAbsent(hash, key -> new Object())) {
            if (file.isFile() && file.length() > 0) {
                return file;
            }
            File part = new File(directory, "ps-" + hash + ".part");
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(part), 65536)) {
                plugin.http().download(url, plugin.config().settings().videoMaxBytes, out, cancelled);
            } catch (IOException e) {
                delete(part);
                throw new MediaException(MediaException.Reason.NETWORK, e, "detail", e.getClass().getSimpleName());
            } catch (MediaException | RuntimeException e) {
                delete(part);
                throw e;
            }
            try {
                Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                delete(part);
                throw new MediaException(MediaException.Reason.INTERNAL, e, "detail", "move");
            }
            return file;
        }
    }

    File log(String url) {
        return new File(directory(), "ps-" + hash(url) + ".log");
    }

    void discard(String url) {
        if (UploadStore.isUpload(url)) {
            return;
        }
        String hash = hash(url);
        synchronized (locks.computeIfAbsent(hash, key -> new Object())) {
            File directory = directory();
            delete(new File(directory, "ps-" + hash + ".video"));
            delete(new File(directory, "ps-" + hash + ".log"));
        }
    }

    void cleanup() {
        File[] files = directory().listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isFile() && OWNED.matcher(file.getName()).matches()) {
                delete(file);
            }
        }
    }

    private static void delete(File file) {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException ignored) {
        }
    }

    static String hash(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(40);
            for (byte b : digest) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
