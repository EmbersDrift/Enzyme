package com.embers.packmod;

import java.util.LinkedHashMap;
import java.util.Map;

/** Render-thread cache: keep frequently used entries without retaining every dynamic label. */
final class BoundedCache<K, V> extends LinkedHashMap<K, V> {
    private final int limit;

    BoundedCache(int limit) {
        super(16, 0.75f, true);
        this.limit = limit;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > limit;
    }
}
