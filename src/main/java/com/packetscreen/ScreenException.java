package com.packetscreen;

final class ScreenException extends Exception {

    final String key;
    final Object[] placeholders;

    ScreenException(String key, Object... placeholders) {
        super(key, null, false, false);
        this.key = key;
        this.placeholders = placeholders;
    }
}
