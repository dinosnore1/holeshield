package com.dino.holeshield;

import java.util.Map;

/** SharedPreferences format for Android's hourly and daily statistics. */
final class StatsHistoryCodec {
    private StatsHistoryCodec() { }

    static void load(String text, Map<String, long[]> into) {
        into.clear();
        if (text == null || text.isEmpty()) return;
        for (String line : text.split("\n")) {
            String[] f = line.split("\\|", -1);
            if (f.length < 3) continue;
            try {
                into.put(f[0], new long[]{Long.parseLong(f[1]), Long.parseLong(f[2]),
                        f.length > 3 ? Long.parseLong(f[3]) : 0L,
                        f.length > 4 ? Long.parseLong(f[4]) : 0L,
                        f.length > 5 ? Long.parseLong(f[5]) : 0L});
            } catch (NumberFormatException ignored) { }
        }
    }

    static String encode(Map<String, long[]> history) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, long[]> e : history.entrySet()) {
            if (out.length() > 0) out.append('\n');
            out.append(e.getKey());
            for (long value : e.getValue()) out.append('|').append(value);
        }
        return out.toString();
    }
}
