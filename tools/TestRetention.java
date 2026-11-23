package com.dino.holeshield;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/** JVM checks for the rolling window and local bucket keys. */
public final class TestRetention {

    private static int checks;

    public static void main(String[] args) {
        long now = 1_800_000_000_000L;

        check(!Retention.expired(Retention.cutoff(now), now),
                "a row exactly at the cutoff is still retained");
        check(Retention.expired(Retention.cutoff(now) - 1, now),
                "a row one millisecond before the cutoff has expired");
        check(Retention.WINDOW_MS == 30L * 24 * 60 * 60 * 1000,
                "the window is a rolling 30 days");

        ZoneId zone = ZoneId.of("America/New_York");
        long spring = ZonedDateTime.of(2026, 3, 8, 12, 0, 0, 0, zone)
                .toInstant().toEpochMilli();
        long start = Retention.startOfDay(spring, zone);
        check(start <= spring, "start of day is not after the instant");
        check(spring - start < 24L * 60 * 60 * 1000,
                "start of day is within one calendar day");
        long before = start - 1;
        check(!Retention.dayKey(before, zone).equals(Retention.dayKey(start, zone)),
                "the day key changes at midnight");
        check(Retention.dayKey(start, zone).equals("2026-03-08"),
                "the day key uses the local date");

        long autumn = ZonedDateTime.of(2026, 11, 1, 12, 0, 0, 0, zone)
                .toInstant().toEpochMilli();
        check(Retention.startOfDay(autumn, zone) <= autumn,
                "start of day still works across a DST fallback");
        check(Retention.hourKey(autumn, zone).equals("2026-11-01-12"),
                "the hour key uses the local hour");

        System.out.println("OK — " + checks + " retention checks passed");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
