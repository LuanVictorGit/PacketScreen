package com.packetscreen;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class UploadStore {

    enum Kind { IMAGE, VIDEO }

    interface Validator {
        void validate(File file, Kind kind) throws MediaException;
    }

    static final class Upload {
        final String id;
        final Kind kind;
        final long size;
        final long modified;

        Upload(String id, Kind kind, long size, long modified) {
            this.id = id;
            this.kind = kind;
            this.size = size;
            this.modified = modified;
        }
    }

    static final String PREFIX = "upload:";
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}\\.(png|jpg|gif|bmp|webp|mp4|mov|webm|mkv|avi|flv|ogg|ts|mpg)");
    private static final Pattern UNSAFE = Pattern.compile("[^a-z0-9_-]+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    private final File directory;
    private final SecureRandom random = new SecureRandom();
    private final Object lock = new Object();

    UploadStore(File directory) {
        this.directory = directory;
    }

    static boolean isUpload(String source) {
        return source != null && source.startsWith(PREFIX);
    }

    static String id(String source) {
        return source.substring(PREFIX.length());
    }

    static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    static Kind kind(String id) {
        String extension = id.substring(id.lastIndexOf('.') + 1);
        switch (extension) {
            case "png":
            case "jpg":
            case "gif":
            case "bmp":
            case "webp":
                return Kind.IMAGE;
            default:
                return Kind.VIDEO;
        }
    }

    static String contentType(String id) {
        String extension = id.substring(id.lastIndexOf('.') + 1);
        switch (extension) {
            case "png":
                return "image/png";
            case "jpg":
                return "image/jpeg";
            case "gif":
                return "image/gif";
            case "bmp":
                return "image/bmp";
            case "webp":
                return "image/webp";
            case "mp4":
                return "video/mp4";
            case "mov":
                return "video/quicktime";
            case "webm":
                return "video/webm";
            case "mkv":
                return "video/x-matroska";
            case "avi":
                return "video/x-msvideo";
            case "flv":
                return "video/x-flv";
            case "ogg":
                return "video/ogg";
            case "ts":
                return "video/mp2t";
            default:
                return "video/mpeg";
        }
    }

    File file(String id) {
        if (!validId(id)) {
            return null;
        }
        File file = new File(directory, id);
        return file.isFile() ? file : null;
    }

    boolean exists(String id) {
        return file(id) != null;
    }

    List<Upload> list() {
        List<Upload> uploads = new ArrayList<>();
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (file.isFile() && validId(name)) {
                    uploads.add(new Upload(name, kind(name), file.length(), file.lastModified()));
                }
            }
        }
        uploads.sort(Comparator.comparingLong((Upload upload) -> upload.modified).reversed());
        return uploads;
    }

    long usage() {
        long total = 0;
        for (Upload upload : list()) {
            total += upload.size;
        }
        return total;
    }

    boolean delete(String id) {
        File file = file(id);
        if (file == null) {
            return false;
        }
        try {
            return Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            return false;
        }
    }

    Upload store(String originalName, InputStream input, long declared, long imageLimit, long videoLimit, long quota,
                 Validator validator) throws MediaException {
        byte[] head = new byte[512];
        int length;
        try {
            length = fill(input, head);
        } catch (IOException e) {
            throw new MediaException(MediaException.Reason.NETWORK, e, "detail", e.getClass().getSimpleName());
        }
        String extension = extension(head, length);
        Kind kind = kind("x." + extension);
        long limit = kind == Kind.IMAGE ? imageLimit : videoLimit;
        if (declared > limit) {
            throw new MediaException(MediaException.Reason.TOO_LARGE, "max", HttpFetcher.formatBytes(limit));
        }
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new MediaException(MediaException.Reason.INTERNAL, "detail", "uploads directory");
        }
        File temp = new File(directory, ".upload-" + hex(8) + ".part");
        try {
            long total = length;
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(temp), 65536)) {
                out.write(head, 0, length);
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > limit) {
                        throw new MediaException(MediaException.Reason.TOO_LARGE, "max", HttpFetcher.formatBytes(limit));
                    }
                    out.write(buffer, 0, read);
                }
            } catch (IOException e) {
                throw new MediaException(MediaException.Reason.NETWORK, e, "detail", e.getClass().getSimpleName());
            }
            if (declared >= 0 && total != declared) {
                throw new MediaException(MediaException.Reason.NETWORK, "detail", "incomplete upload");
            }
            validator.validate(temp, kind);
            synchronized (lock) {
                if (usage() + total > quota) {
                    throw new MediaException(MediaException.Reason.STORAGE_FULL, "max", HttpFetcher.formatBytes(quota));
                }
                String id;
                do {
                    id = base(originalName) + "-" + hex(3) + "." + extension;
                } while (new File(directory, id).exists());
                File target = new File(directory, id);
                try {
                    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp.toPath(), target.toPath());
                }
                return new Upload(id, kind, target.length(), target.lastModified());
            }
        } catch (IOException e) {
            throw new MediaException(MediaException.Reason.INTERNAL, e, "detail", "store");
        } finally {
            try {
                Files.deleteIfExists(temp.toPath());
            } catch (IOException ignored) {
            }
        }
    }

    void cleanup() {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isFile() && file.getName().startsWith(".upload-") && file.getName().endsWith(".part")) {
                try {
                    Files.deleteIfExists(file.toPath());
                } catch (IOException ignored) {
                }
            }
        }
    }

    static String extension(byte[] head, int length) throws MediaException {
        try {
            switch (ImageLoader.detect(head, length)) {
                case PNG:
                    return "png";
                case JPEG:
                    return "jpg";
                case GIF:
                    return "gif";
                case BMP:
                    return "bmp";
                default:
                    return "webp";
            }
        } catch (MediaException image) {
            if (image.reason == MediaException.Reason.WEB_PAGE) {
                throw image;
            }
        }
        String demuxer = VideoDecoder.demuxer(head, length);
        switch (demuxer) {
            case "mov":
                return length >= 12 && new String(head, 8, 4, StandardCharsets.ISO_8859_1).equals("qt  ") ? "mov" : "mp4";
            case "matroska":
                return new String(head, 0, length, StandardCharsets.ISO_8859_1).contains("webm") ? "webm" : "mkv";
            case "mpeg":
                return "mpg";
            case "mpegts":
                return "ts";
            default:
                return demuxer;
        }
    }

    static String base(String originalName) {
        String name = originalName == null ? "" : originalName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        name = MARKS.matcher(Normalizer.normalize(name, Normalizer.Form.NFD)).replaceAll("");
        name = UNSAFE.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("-");
        while (name.startsWith("-") || name.startsWith("_")) {
            name = name.substring(1);
        }
        if (name.length() > 32) {
            name = name.substring(0, 32);
        }
        while (name.endsWith("-") || name.endsWith("_")) {
            name = name.substring(0, name.length() - 1);
        }
        return name.isEmpty() ? "media" : name;
    }

    private static int fill(InputStream input, byte[] buffer) throws IOException {
        int length = 0;
        int read;
        while (length < buffer.length && (read = input.read(buffer, length, buffer.length - length)) != -1) {
            length += read;
        }
        return length;
    }

    private String hex(int bytes) {
        byte[] data = new byte[bytes];
        random.nextBytes(data);
        StringBuilder builder = new StringBuilder(bytes * 2);
        for (byte b : data) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }
}
