package com.alechilles.alecstamework.localization;

import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Caches translation entries loaded from mods and overrides.
 */
public final class TranslationRegistry {
    private static final String DEFAULT_LANGUAGE = "en-US";
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> entriesByLanguage = new ConcurrentHashMap<>();

    public void put(String key, String value) {
        put(DEFAULT_LANGUAGE, key, value);
    }

    public void put(@Nullable String language, String key, String value) {
        if (key == null || key.isBlank() || value == null) {
            return;
        }
        entriesByLanguage
                .computeIfAbsent(normalizeLanguage(language), ignored -> new ConcurrentHashMap<>())
                .put(key, value);
    }

    public String get(String key) {
        return get(DEFAULT_LANGUAGE, key);
    }

    // Simple in-memory lookup for merged language entries.
    public String get(@Nullable String language, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        ConcurrentHashMap<String, String> languageEntries = entriesByLanguage.get(normalizeLanguage(language));
        if (languageEntries == null) {
            return null;
        }
        return languageEntries.get(key);
    }

    public int size() {
        int size = 0;
        for (ConcurrentHashMap<String, String> entries : entriesByLanguage.values()) {
            size += entries.size();
        }
        return size;
    }

    public Map<String, String> snapshot() {
        return snapshot(DEFAULT_LANGUAGE);
    }

    public Map<String, String> snapshot(@Nullable String language) {
        ConcurrentHashMap<String, String> entries = entriesByLanguage.get(normalizeLanguage(language));
        return entries == null ? Map.of() : Map.copyOf(entries);
    }

    @Nonnull
    private static String normalizeLanguage(@Nullable String language) {
        if (language == null || language.isBlank()) {
            return DEFAULT_LANGUAGE;
        }
        String normalized = language.trim().replace('_', '-');
        String[] parts = normalized.split("-", -1);
        if (parts.length == 2 && parts[0].length() == 2 && parts[1].length() == 2) {
            return parts[0].toLowerCase(Locale.ROOT) + "-" + parts[1].toUpperCase(Locale.ROOT);
        }
        return normalized;
    }
}
