package com.dino.holeshield;

import java.util.Arrays;

/**
 * Compact, read-only gravity index.
 *
 * Domains are reduced to 64-bit FNV-1a hashes and kept in a sorted array with
 * one category bitmask per entry. A 1.51M-domain list costs about 18 MB
 * instead of the tens of megabytes a HashSet of strings needs. Lookups are a
 * binary search, and the category mask lets the resolver switch categories off
 * at runtime without rebuilding the list.
 *
 * Free of Android imports so the desktop checks can exercise it.
 */
final class Gravity {

    static final int ADS = 1;
    static final int TRACKERS = 2;
    static final int MALWARE = 4;
    static final int ADULT = 8;
    static final int PIRACY = 16;
    static final int GAMBLING = 32;
    static final int DATING = 64;
    static final int SOCIAL = 128;
    static final int OTHER = 256;

    static final int ALL = ADS | TRACKERS | MALWARE | ADULT | PIRACY
            | GAMBLING | DATING | SOCIAL | OTHER;

    private static final Gravity EMPTY = new Gravity(new long[0], new int[0]);

    private final long[] hashes;
    private final int[] flags;

    private Gravity(long[] hashes, int[] flags) {
        this.hashes = hashes;
        this.flags = flags;
    }

    static Gravity empty() {
        return EMPTY;
    }

    /**
     * Rehydrates a persisted index. The caller must provide sorted, unique
     * hashes and one category mask per entry.
     */
    static Gravity fromHashes(long[] hashes, int[] flags) {
        if (hashes == null || flags == null || hashes.length != flags.length
                || hashes.length == 0) {
            return EMPTY;
        }
        return new Gravity(hashes, flags);
    }

    int size() {
        return hashes.length;
    }

    /**
     * Number of entries that can actually block under {@code mask}. A domain
     * listed in several categories still counts once while any of its
     * categories is enabled.
     */
    int activeSize(int mask) {
        int n = 0;
        for (int flag : flags) {
            if ((flag & mask) != 0) n++;
        }
        return n;
    }

    /** FNV-1a over the lowercased domain bytes. */
    static long hash(String domain) {
        if (domain == null || domain.isEmpty()) return 0L;
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < domain.length(); i++) {
            char c = domain.charAt(i);
            if (c >= 'A' && c <= 'Z') c += 32;
            h ^= (c & 0xFF);
            h *= 0x100000001b3L;
            if (c > 0xFF) {
                // Non-ASCII input is not a valid DNS hostname, but keep the
                // upper bits meaningful instead of silently dropping them.
                h ^= (c >> 8);
                h *= 0x100000001b3L;
            }
        }
        return h;
    }

    /** Category flags for one hash, or 0 when it is absent. */
    int flagsOf(long hash) {
        int at = Arrays.binarySearch(hashes, hash);
        return at < 0 ? 0 : flags[at];
    }

    boolean contains(long hash) {
        return Arrays.binarySearch(hashes, hash) >= 0;
    }

    /**
     * Merges sorted, deduplicated category arrays. When a hash appears in more
     * than one category the flags are OR-ed. The result is capped at
     * {@code max} domains.
     */
    static Gravity build(long[][] sources, int[] bits, int max) {
        if (sources == null || sources.length == 0 || max <= 0) return EMPTY;
        int total = 0;
        for (long[] source : sources) {
            if (source != null) total += source.length;
        }
        if (total == 0) return EMPTY;
        if (total > max) total = max;

        long[] outHashes = new long[total];
        int[] outFlags = new int[total];
        int[] at = new int[sources.length];
        int written = 0;

        while (written < total) {
            long smallest = 0L;
            boolean found = false;
            for (int i = 0; i < sources.length; i++) {
                long[] source = sources[i];
                if (source == null || at[i] >= source.length) continue;
                long value = source[at[i]];
                if (!found || value < smallest) {
                    smallest = value;
                    found = true;
                }
            }
            if (!found) break;
            int merged = 0;
            for (int i = 0; i < sources.length; i++) {
                long[] source = sources[i];
                if (source == null || at[i] >= source.length) continue;
                if (source[at[i]] != smallest) continue;
                merged |= bits == null || i >= bits.length ? ALL : bits[i];
                at[i]++;
                while (at[i] < source.length && source[at[i]] == smallest) at[i]++;
            }
            outHashes[written] = smallest;
            outFlags[written] = merged;
            written++;
        }

        if (written == 0) return EMPTY;
        if (written < outHashes.length) {
            outHashes = Arrays.copyOf(outHashes, written);
            outFlags = Arrays.copyOf(outFlags, written);
        }
        return new Gravity(outHashes, outFlags);
    }

    static long[][] fairCap(long[][] sources, int max) {
        return fairCap(sources, max, sources.length);
    }

    /**
     * Shares a global memory guard across the category arrays. Each category
     * keeps its first entries in list order, so the selected Lite/Medium/
     * Maximum lists keep useful coverage instead of one large category
     * consuming the whole guard. The guard is split over {@code slots}
     * categories, so a switched-off category leaves its share unused and the
     * domain count drops instead of flowing into the other lists.
     */
    static long[][] fairCap(long[][] sources, int max, int slots) {
        int total = 0;
        for (long[] source : sources) total += source.length;
        if (total <= max) return sources;
        int active = 0;
        for (long[] source : sources) if (source.length > 0) active++;
        if (active == 0 || slots <= 0) return sources;
        int budget = (int) Math.min(max, (long) max * active / slots);
        if (budget < active) budget = active;
        int quota = budget / active;
        int remainder = budget % active;
        int[] original = new int[sources.length];
        int[] take = new int[sources.length];
        int used = 0;
        for (int i = 0; i < sources.length; i++) {
            original[i] = sources[i].length;
            if (original[i] == 0) continue;
            take[i] = Math.min(original[i], quota + (remainder > 0 ? 1 : 0));
            if (remainder > 0) remainder--;
            used += take[i];
        }
        int left = budget - used;
        while (left > 0) {
            boolean progress = false;
            for (int i = 0; i < sources.length && left > 0; i++) {
                if (take[i] < original[i]) {
                    take[i]++;
                    left--;
                    progress = true;
                }
            }
            if (!progress) break;
        }
        for (int i = 0; i < sources.length; i++) {
            if (take[i] < original[i]) {
                sources[i] = Arrays.copyOf(sources[i], take[i]);
            }
        }
        return sources;
    }

    /** Sorts and deduplicates one category array before merging. */
    static long[] clean(long[] values) {
        if (values == null || values.length == 0) return new long[0];
        Arrays.sort(values);
        int unique = 0;
        for (int i = 0; i < values.length; i++) {
            if (i == 0 || values[i] != values[i - 1]) {
                values[unique++] = values[i];
            }
        }
        return unique == values.length ? values : Arrays.copyOf(values, unique);
    }
}
