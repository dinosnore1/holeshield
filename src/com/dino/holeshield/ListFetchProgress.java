package com.dino.holeshield;

import java.util.LinkedHashMap;
import java.util.Map;

/** In-memory progress for the current gravity update, keyed by configured URL. */
final class ListFetchProgress {
    enum State { QUEUED, FETCHING, DOWNLOADED, FAILED, SKIPPED }

    private final Map<String, State> states = new LinkedHashMap<>();

    synchronized void set(String url, State state) {
        states.put(url, state);
    }

    synchronized Map<String, State> snapshot() {
        return new LinkedHashMap<>(states);
    }

    synchronized void clear() {
        states.clear();
    }
}
