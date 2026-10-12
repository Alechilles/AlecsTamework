package com.alechilles.alecstamework.settings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Server-wide blocked-name filter settings owned by `/tw settings`.
 *
 * @param enabled      whether player-entered companion names are checked at all
 * @param useStockList whether the built-in word lists apply
 * @param blockedWords extra words or phrases the server blocks
 * @param allowedWords words or phrases the server allows even when a list blocks them
 */
public record NameFilterSettings(boolean enabled,
                                 boolean useStockList,
                                 @Nonnull List<String> blockedWords,
                                 @Nonnull List<String> allowedWords) {
    public static final NameFilterSettings DEFAULT = new NameFilterSettings(true, true, List.of(), List.of());

    public NameFilterSettings {
        blockedWords = cleanWords(blockedWords);
        allowedWords = cleanWords(allowedWords);
    }

    /** Parses the comma-separated word list a settings text field holds. */
    @Nonnull
    public static List<String> parseWordList(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return cleanWords(List.of(raw.split(",")));
    }

    /** Formats a word list for a settings text field. */
    @Nonnull
    public static String formatWordList(@Nonnull List<String> words) {
        return String.join(", ", words);
    }

    @Nonnull
    private static List<String> cleanWords(@Nullable List<String> words) {
        if (words == null || words.isEmpty()) {
            return List.of();
        }
        Set<String> cleaned = new LinkedHashSet<>();
        for (String word : words) {
            if (word != null && !word.isBlank()) {
                cleaned.add(word.trim());
            }
        }
        return List.copyOf(new ArrayList<>(cleaned));
    }
}
