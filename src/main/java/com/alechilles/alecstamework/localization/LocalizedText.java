package com.alechilles.alecstamework.localization;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves localized UI text from language keys with translation-registry fallback.
 */
public final class LocalizedText {
    private static final String SERVER_PREFIX = "server.";
    private static final String DEFAULT_LANGUAGE = "en-US";
    private static final String FALLBACK_LANGUAGE_RESOURCE = "/Server/Languages/en-US/server.lang";
    private static volatile Map<String, String> bundledFallbackEntries;

    private LocalizedText() {
    }

    @Nonnull
    public static String resolve(@Nullable Player player, @Nonnull String key) {
        String language = DEFAULT_LANGUAGE;
        if (player != null && player.getPlayerRef() != null) {
            language = player.getPlayerRef().getLanguage();
        }
        return resolve(language, key);
    }

    @Nonnull
    public static String resolve(@Nullable PlayerRef playerRef, @Nonnull String key) {
        String language = playerRef != null ? playerRef.getLanguage() : DEFAULT_LANGUAGE;
        return resolve(language, key);
    }

    @Nonnull
    public static String resolve(@Nullable String language, @Nonnull String key) {
        String normalizedKey = key == null ? "" : key.trim();
        if (normalizedKey.isEmpty()) {
            return "";
        }
        String normalizedLanguage = language == null || language.isBlank() ? DEFAULT_LANGUAGE : language;
        String i18nValue = resolveFromI18n(normalizedLanguage, normalizedKey);
        if (i18nValue != null && !i18nValue.isBlank()) {
            return i18nValue;
        }
        String registryValue = resolveFromRegistry(normalizedKey);
        if (registryValue != null && !registryValue.isBlank()) {
            return registryValue;
        }
        String bundledValue = resolveFromBundledResource(normalizedKey);
        if (bundledValue != null && !bundledValue.isBlank()) {
            return bundledValue;
        }
        return normalizedKey;
    }

    @Nonnull
    public static String format(@Nullable Player player, @Nonnull String key, Object... args) {
        return formatTemplate(resolve(player, key), args);
    }

    @Nonnull
    public static String format(@Nullable PlayerRef playerRef, @Nonnull String key, Object... args) {
        return formatTemplate(resolve(playerRef, key), args);
    }

    @Nonnull
    public static String format(@Nullable String language, @Nonnull String key, Object... args) {
        return formatTemplate(resolve(language, key), args);
    }

    @Nonnull
    public static String formatTemplate(@Nullable String template, Object... args) {
        String out = template == null ? "" : template;
        if (args == null || args.length == 0 || out.isEmpty()) {
            return out;
        }
        for (int i = 0; i < args.length; i++) {
            String replacement = args[i] == null ? "" : String.valueOf(args[i]);
            out = out.replace("{" + i + "}", replacement);
        }
        return out;
    }

    @Nullable
    private static String resolveFromI18n(@Nonnull String language, @Nonnull String key) {
        I18nModule i18n;
        try {
            i18n = I18nModule.get();
        } catch (LinkageError | RuntimeException ex) {
            return null;
        }
        if (i18n == null) {
            return null;
        }
        for (String candidate : lookupCandidates(key)) {
            String translated;
            try {
                translated = i18n.getMessage(language, candidate);
            } catch (LinkageError | RuntimeException ex) {
                return null;
            }
            if (isResolvedTranslation(translated, candidate)) {
                return translated;
            }
        }
        return null;
    }

    @Nullable
    private static String resolveFromRegistry(@Nonnull String key) {
        Tamework instance;
        try {
            instance = Tamework.getInstance();
        } catch (LinkageError | RuntimeException ex) {
            return null;
        }
        TranslationRegistry registry = instance != null ? instance.getTranslationRegistry() : null;
        if (registry == null) {
            return null;
        }
        for (String candidate : lookupCandidates(key)) {
            String translated;
            try {
                translated = registry.get(candidate);
            } catch (LinkageError | RuntimeException ex) {
                return null;
            }
            if ((translated == null || translated.isBlank()) && candidate.startsWith(SERVER_PREFIX)) {
                try {
                    translated = registry.get(candidate.substring(SERVER_PREFIX.length()));
                } catch (LinkageError | RuntimeException ex) {
                    return null;
                }
            }
            if (translated != null && !translated.isBlank()) {
                return translated;
            }
        }
        return null;
    }

    @Nullable
    private static String resolveFromBundledResource(@Nonnull String key) {
        Map<String, String> entries = bundledFallbackEntries;
        if (entries == null) {
            synchronized (LocalizedText.class) {
                entries = bundledFallbackEntries;
                if (entries == null) {
                    entries = loadBundledFallbackEntries();
                    bundledFallbackEntries = entries;
                }
            }
        }
        for (String candidate : lookupCandidates(key)) {
            String translated = entries.get(candidate);
            if (translated != null && !translated.isBlank()) {
                return translated;
            }
        }
        return null;
    }

    @Nonnull
    private static Map<String, String> loadBundledFallbackEntries() {
        HashMap<String, String> entries = new HashMap<>();
        try (InputStream stream = LocalizedText.class.getResourceAsStream(FALLBACK_LANGUAGE_RESOURCE)) {
            if (stream == null) {
                return entries;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                        continue;
                    }
                    int split = trimmed.indexOf('=');
                    if (split <= 0) {
                        continue;
                    }
                    String key = trimmed.substring(0, split).trim();
                    String value = trimmed.substring(split + 1).trim();
                    if (key.isEmpty() || value.isEmpty()) {
                        continue;
                    }
                    entries.put(key, value);
                }
            }
        } catch (Exception ignored) {
            return entries;
        }
        return entries;
    }

    private static boolean isResolvedTranslation(@Nullable String translated, @Nonnull String candidate) {
        if (translated == null || translated.isBlank()) {
            return false;
        }
        return !translated.equals(candidate);
    }

    @Nonnull
    private static List<String> lookupCandidates(@Nonnull String key) {
        ArrayList<String> candidates = new ArrayList<>(2);
        candidates.add(key);
        if (key.startsWith(SERVER_PREFIX)) {
            String withoutPrefix = key.substring(SERVER_PREFIX.length());
            if (!withoutPrefix.isBlank()) {
                candidates.add(withoutPrefix);
            }
        } else {
            candidates.add(SERVER_PREFIX + key);
        }
        return candidates;
    }
}
