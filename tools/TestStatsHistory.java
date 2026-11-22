package com.dino.holeshield;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Regression checks for the production Android statistics persistence format. */
public final class TestStatsHistory {
    private static int checks;

    public static void main(String[] args) {
        Map<String, long[]> hourly = new LinkedHashMap<>();
        StatsHistoryCodec.load("2026-11-22-23|8|2", hourly);
        // Loaded hours have five slots; a new hour created by Stats has only two.
        hourly.put("2026-11-23-00", new long[]{12, 4});
        String saved = StatsHistoryCodec.encode(hourly);
        check(saved.equals("2026-11-22-23|8|2|0|0|0\n2026-11-23-00|12|4"),
                "saving a new hour alongside a loaded hour");
        StatsHistoryCodec.load(saved, hourly);
        check(Arrays.equals(hourly.get("2026-11-22-23"), new long[]{8, 2, 0, 0, 0}),
                "old hourly counters survive save and reload");
        check(Arrays.equals(hourly.get("2026-11-23-00"), new long[]{12, 4, 0, 0, 0}),
                "new hourly counters survive save and reload");

        Map<String, long[]> daily = new LinkedHashMap<>();
        daily.put("2026-11-23", new long[]{24, 5, 6, 12, 1});
        StatsHistoryCodec.load(StatsHistoryCodec.encode(daily), daily);
        check(Arrays.equals(daily.get("2026-11-23"), new long[]{24, 5, 6, 12, 1}),
                "daily history preserves all five counters");
        System.out.println("OK — " + checks + " Android statistics history checks passed");
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError("FAILED: " + name);
        checks++;
    }
}
