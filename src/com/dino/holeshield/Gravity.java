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

    /** Hashes of the merged index, ascending. Treat as read-only. */
    long[] hashes() {
        return hashes;
    }

    /** Category bits matching {@link #hashes()}. Treat as read-only. */
    int[] flags() {
        return flags;
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

    /**
     * Counts active entries once each under the first matching bit in
     * {@code priority}. The result follows {@code priority} order and its
     * entries add up to {@link #activeSize(int)} for the same mask, so the
     * per-category numbers on the dashboard match the gravity total.
     */
    int[] primaryCounts(int[] priority, int mask) {
        int[] out = new int[priority.length];
        for (int flag : flags) {
            int active = flag & mask;
            if (active == 0) continue;
            for (int i = 0; i < priority.length; i++) {
                if ((active & priority[i]) != 0) {
                    out[i]++;
                    break;
                }
            }
        }
        return out;
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

    /**
     * Merges the category arrays first and only then applies the memory guard,
     * so a domain that appears in several lists is charged once instead of
     * once per list. Each unique domain is charged to the first matching bit
     * in {@code priority}; the kept entry keeps every category bit it was
     * listed under.
     *
     * <p>The guard is divided between the enabled categories only, so a
     * switched-off category gives its share to the ones that are still on.
     * Space that a smaller list cannot fill likewise flows to the categories
     * first in {@code priority}, which lets the caller favour Ads without
     * letting a large list take the whole guard.</p>
     *
     * <p>{@code sources} must be sorted and free of duplicates, as returned by
     * {@link #clean(long[])}.</p>
     */
    static Gravity buildFair(long[][] sources, int[] bits, int max,
                             int[] priority) {
        if (sources == null || sources.length == 0 || max <= 0) return EMPTY;
        if (priority == null || priority.length == 0) {
            return build(sources, bits, max);
        }
        int total = 0;
        for (long[] source : sources) {
            if (source != null) total += source.length;
        }
        if (total == 0) return EMPTY;

        int[] at = new int[sources.length];
        int[] demand = new int[priority.length];
        int unique = 0;
        while (true) {
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
            }
            unique++;
            int primary = primaryIndex(merged, priority);
            if (primary >= 0) demand[primary]++;
        }
        if (unique == 0) return EMPTY;
        // Nothing to trim: every unique domain fits, so the guard costs nothing.
        if (unique <= max) return build(sources, bits, unique);

        int[] share = budgetShares(demand, max);
        int keep = 0;
        for (int value : share) keep += value;

        long[] outHashes = new long[keep];
        int[] outFlags = new int[keep];
        Arrays.fill(at, 0);
        int written = 0;
        while (written < keep) {
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
            }
            int primary = primaryIndex(merged, priority);
            if (primary < 0 || share[primary] <= 0) continue;
            share[primary]--;
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

    /** Index of the highest-priority bit set in {@code flags}, or -1. */
    private static int primaryIndex(int flags, int[] priority) {
        for (int i = 0; i < priority.length; i++) {
            if ((flags & priority[i]) != 0) return i;
        }
        return -1;
    }

    /**
     * Splits {@code max} equally between the categories that have domains, so
     * a category that is switched off (and therefore empty) hands its share to
     * the others. Space that a small category cannot fill is passed on in
     * priority order, so Ads - the first entry in the caller's priority list -
     * takes the unused capacity before the others.
     */
    private static int[] budgetShares(int[] demand, int max) {
        int[] share = new int[demand.length];
        int unique = 0;
        int groups = 0;
        for (int value : demand) {
            unique += value;
            if (value > 0) groups++;
        }
        if (groups == 0) return share;
        int budget = Math.min(max, unique);
        if (budget < groups) budget = groups;
        int quota = budget / groups;
        int remainder = budget % groups;
        int left = budget;
        for (int i = 0; i < demand.length; i++) {
            if (demand[i] == 0) continue;
            share[i] = Math.min(demand[i], quota + (remainder > 0 ? 1 : 0));
            if (remainder > 0) remainder--;
            left -= share[i];
        }
        while (left > 0) {
            boolean progress = false;
            for (int i = 0; i < demand.length && left > 0; i++) {
                if (share[i] < demand[i]) {
                    int add = Math.min(left, demand[i] - share[i]);
                    share[i] += add;
                    left -= add;
                    progress = true;
                }
            }
            if (!progress) break;
        }
        return share;
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
