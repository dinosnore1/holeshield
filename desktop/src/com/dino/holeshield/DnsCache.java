package com.dino.holeshield;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small LRU DNS response cache keyed by question name/type. */
final class DnsCache {

    private static final class Item {
        final byte[] packet;
        final long expires;
        Item(byte[] packet, long expires) {
            this.packet = packet;
            this.expires = expires;
        }
    }

    private final LinkedHashMap<String, Item> entries;
    private final int max;

    DnsCache(int max) {
        this.max = Math.max(64, max);
        this.entries = new LinkedHashMap<String, Item>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Item> eldest) {
                return size() > DnsCache.this.max;
            }
        };
    }

    synchronized byte[] get(String key) {
        Item item = entries.get(key);
        if (item == null) return null;
        if (item.expires < System.currentTimeMillis()) {
            entries.remove(key);
            return null;
        }
        return item.packet;
    }

    synchronized void put(String key, byte[] packet, int ttlSeconds) {
        if (packet == null || ttlSeconds <= 0) return;
        long ttl = Math.max(10, Math.min(3600, ttlSeconds));
        entries.put(key, new Item(packet, System.currentTimeMillis() + ttl * 1000L));
    }

    synchronized void clear() {
        entries.clear();
    }

    synchronized int size() {
        return entries.size();
    }

    static String key(String name, int type, int qclass) {
        return name + "|" + type + "|" + qclass;
    }
}
