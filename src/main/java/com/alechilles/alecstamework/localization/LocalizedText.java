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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves localized UI text from language keys with translation-registry fallback.
 */
public final class LocalizedText {
    private static final String SERVER_PREFIX = "server.";
    private static final String DEFAULT_LANGUAGE = "en-US";
    private static final String LANGUAGE_RESOURCE_ROOT = "/Server/Languages/";
    private static final String LANGUAGE_RESOURCE_FILE = "/server.lang";
    private static final ConcurrentHashMap<String, Map<String, String>> bundledEntriesByLanguage =
            new ConcurrentHashMap<>();

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
        String normalizedLanguage = normalizeLanguage(language);
        String i18nValue = resolveFromI18n(normalizedLanguage, normalizedKey);
        if (i18nValue != null && !i18nValue.isBlank()) {
            return i18nValue;
        }
        String registryValue = resolveFromRegistry(normalizedLanguage, normalizedKey);
        if (registryValue != null && !registryValue.isBlank()) {
            return registryValue;
        }
        String bundledValue = resolveFromBundledResource(normalizedLanguage, normalizedKey);
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
    public static String resolveConfigValue(@Nullable String language,
                                            @Nullable String configuredValue,
                                            @Nullable String fallbackValue) {
        String trimmed = configuredValue == null ? "" : configuredValue.trim();
        if (trimmed.isEmpty()) {
            return fallbackValue == null ? "" : fallbackValue;
        }
        String resolved = resolve(language, trimmed);
        if (resolved == null || resolved.isBlank() || resolved.equals(trimmed)) {
            return looksLikeTranslationKey(trimmed) ? fallbackOrKey(fallbackValue, trimmed) : trimmed;
        }
        return resolved;
    }

    @Nonnull
    public static String formatConfigValue(@Nullable String language,
                                           @Nullable String configuredValue,
                                           @Nullable String fallbackTemplate,
                                           Object... args) {
        return formatTemplate(resolveConfigValue(language, configuredValue, fallbackTemplate), args);
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
    private static String resolveFromRegistry(@Nonnull String language, @Nonnull String key) {
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
                translated = registry.get(language, candidate);
            } catch (LinkageError | RuntimeException ex) {
                return null;
            }
            if ((translated == null || translated.isBlank()) && candidate.startsWith(SERVER_PREFIX)) {
                try {
                    translated = registry.get(language, candidate.substring(SERVER_PREFIX.length()));
                } catch (LinkageError | RuntimeException ex) {
                    return null;
                }
            }
            if ((translated == null || translated.isBlank()) && !DEFAULT_LANGUAGE.equals(language)) {
                try {
                    translated = registry.get(DEFAULT_LANGUAGE, candidate);
                    if ((translated == null || translated.isBlank()) && candidate.startsWith(SERVER_PREFIX)) {
                        translated = registry.get(DEFAULT_LANGUAGE, candidate.substring(SERVER_PREFIX.length()));
                    }
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
    private static String resolveFromBundledResource(@Nonnull String language, @Nonnull String key) {
        String translated = resolveFromBundledResourceLanguage(language, key);
        if (translated != null && !translated.isBlank()) {
            return translated;
        }
        if (!DEFAULT_LANGUAGE.equals(language)) {
            return resolveFromBundledResourceLanguage(DEFAULT_LANGUAGE, key);
        }
        return null;
    }

    @Nullable
    private static String resolveFromBundledResourceLanguage(@Nonnull String language, @Nonnull String key) {
        Map<String, String> entries = bundledEntriesByLanguage.computeIfAbsent(
                language,
                LocalizedText::loadBundledEntries
        );
        for (String candidate : lookupCandidates(key)) {
            String value = entries.get(candidate);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @Nonnull
    private static Map<String, String> loadBundledEntries(@Nonnull String language) {
        HashMap<String, String> entries = new HashMap<>();
        String resourcePath = LANGUAGE_RESOURCE_ROOT + language + LANGUAGE_RESOURCE_FILE;
        try (InputStream stream = LocalizedText.class.getResourceAsStream(resourcePath)) {
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

    private static boolean isResolvedTranslation(@Nullable String translated, @Nonnull String candidate) {
        if (translated == null || translated.isBlank()) {
            return false;
        }
        return !translated.equals(candidate);
    }

    private static boolean looksLikeTranslationKey(@Nonnull String value) {
        return value.indexOf('.') > 0 && value.indexOf(' ') < 0;
    }

    @Nonnull
    private static String fallbackOrKey(@Nullable String fallbackValue, @Nonnull String key) {
        return fallbackValue == null || fallbackValue.isBlank() ? key : fallbackValue;
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
