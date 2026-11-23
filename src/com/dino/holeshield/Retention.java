package com.dino.holeshield;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The single definition of the rolling retention window and the local day and
 * hour keys used by the Android SQLite store and the desktop log files.
 * Keeping it free of Android and file APIs lets the expiry rules be tested on
 * the JVM.
 */
final class Retention {

    /** Retained history, measured back from the current instant. */
    static final long WINDOW_MS = 30L * 24 * 60 * 60 * 1000;

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US);
    private static final DateTimeFormatter HOUR =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH", Locale.US);

    private Retention() { }

    static long cutoff(long now) {
        return now - WINDOW_MS;
    }

    static boolean expired(long at, long now) {
        return at < cutoff(now);
    }

    static String dayKey(long at) {
        return dayKey(at, ZoneId.systemDefault());
    }

    static String dayKey(long at, ZoneId zone) {
        return DAY.format(Instant.ofEpochMilli(at).atZone(zone));
    }

    static String hourKey(long at) {
        return hourKey(at, ZoneId.systemDefault());
    }

    static String hourKey(long at, ZoneId zone) {
        return HOUR.format(Instant.ofEpochMilli(at).atZone(zone));
    }

    /** First millisecond of the local day that contains {@code at}. */
    static long startOfDay(long at) {
        return startOfDay(at, ZoneId.systemDefault());
    }

    static long startOfDay(long at, ZoneId zone) {
        return Instant.ofEpochMilli(at).atZone(zone)
                .toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli();
    }
}
