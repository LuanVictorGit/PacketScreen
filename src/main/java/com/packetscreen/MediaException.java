package com.packetscreen;

final class MediaException extends Exception {

    enum Reason {
        INVALID_URL("invalid-url", false),
        BLOCKED_ADDRESS("blocked-address", false),
        NETWORK("network", true),
        TIMEOUT("timeout", true),
        TOO_LARGE("too-large", false),
        HTTP_STATUS("http-status", true),
        TOO_MANY_REDIRECTS("too-many-redirects", false),
        WEB_PAGE("web-page", false),
        UNSUPPORTED_FORMAT("unsupported-format", false),
        FRAMES("frames", false),
        VIDEO_SOURCE("video-source", false),
        IMAGE_SOURCE("image-source", false),
        RESOLUTION("resolution", false),
        DURATION("duration", false),
        DECODE("decode", false),
        FFMPEG_MISSING("ffmpeg-missing", false),
        MAP_IDS("map-ids", false),
        MISSING("missing", false),
        STORAGE_FULL("storage-full", false),
        CANCELLED("cancelled", false),
        INTERNAL("internal", false);

        final String key;
        final boolean retryable;

        Reason(String key, boolean retryable) {
            this.key = key;
            this.retryable = retryable;
        }
    }

    final Reason reason;
    final Object[] placeholders;

    MediaException(Reason reason, Object... placeholders) {
        super(reason.key);
        this.reason = reason;
        this.placeholders = placeholders;
    }

    MediaException(Reason reason, Throwable cause, Object... placeholders) {
        super(reason.key, cause);
        this.reason = reason;
        this.placeholders = placeholders;
    }

    static MediaException of(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null && !(cause instanceof MediaException) && cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause instanceof MediaException) {
            return (MediaException) cause;
        }
        String detail = cause == null ? "unknown" : cause.getClass().getSimpleName();
        return new MediaException(Reason.INTERNAL, throwable, "detail", detail);
    }
}
