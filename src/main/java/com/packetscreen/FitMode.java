package com.packetscreen;

import java.util.Locale;

enum FitMode {
    FIT,
    FILL,
    STRETCH;

    static FitMode parse(String value) {
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
