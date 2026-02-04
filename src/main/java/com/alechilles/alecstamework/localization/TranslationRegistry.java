package com.alechilles.alecstamework.localization;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches translation entries loaded from mods and overrides.
 */
public final class TranslationRegistry {
    private final ConcurrentHashMap<String, String> entries = new ConcurrentHashMap<>();

    public void put(String key, String value) {
        if (key == null || key.isBlank() || value == null) {
            return;
        }
        entries.put(key, value);
    }

    public String get(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return entries.get(key);
    }

    public int size() {
        return entries.size();
    }

    public Map<String, String> snapshot() {
        return Map.copyOf(entries);
    }
}
