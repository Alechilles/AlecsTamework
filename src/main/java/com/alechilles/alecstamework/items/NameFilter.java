package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.settings.NameFilterSettings;
import com.alechilles.alecstamework.settings.NameFilterSettingsStore;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Blocks companion names that contain a blocked word or phrase.
 *
 * <p>Matching ignores case, accents and punctuation, and works on whole words: a name is
 * blocked when one of its words, a run of its words, or the whole name with separators
 * removed equals a blocked entry. Blocked words inside a longer word do not match, so
 * "Cassie" is not caught by a three-letter entry.
 */
public final class NameFilter {
    private static final String STOCK_RESOURCE_DIRECTORY = "/META-INF/tamework/name-filter/";
    private static final List<String> STOCK_LISTS = List.of("en.txt", "de.txt", "es.txt", "fr.txt", "pt.txt");
    private static final String STOCK_EXCEPTIONS = "exceptions.txt";
    private static final NameFilter ALLOW_ALL = new NameFilter(Set.of(), Set.of());

    @Nullable
    private static volatile Set<String> stockEntries;
    @Nullable
    private static volatile Compiled compiled;

    /** Normalized blocked entries: single words, or phrases joined by single spaces. */
    private final Set<String> blocked;
    private final Set<String> allowed;

    private NameFilter(@Nonnull Set<String> blocked, @Nonnull Set<String> allowed) {
        this.blocked = blocked;
        this.allowed = allowed;
    }

    /** The filter for the server's current name filter settings. */
    @Nonnull
    public static NameFilter current() {
        NameFilterSettings settings = NameFilterSettingsStore.current();
        Compiled current = compiled;
        if (current != null && current.settings() == settings) {
            return current.filter();
        }
        NameFilter filter = of(settings, settings.enabled() && settings.useStockList() ? stockEntries() : Set.of());
        compiled = new Compiled(settings, filter);
        return filter;
    }

    @Nonnull
    static NameFilter of(@Nonnull NameFilterSettings settings, @Nonnull Collection<String> stock) {
        if (!settings.enabled()) {
            return ALLOW_ALL;
        }
        Set<String> blocked = new HashSet<>();
        if (settings.useStockList()) {
            addNormalized(blocked, stock);
        }
        addNormalized(blocked, settings.blockedWords());
        Set<String> allowed = new HashSet<>();
        addNormalized(allowed, settings.allowedWords());
        blocked.removeAll(allowed);
        return new NameFilter(blocked, allowed);
    }

    public boolean isBlocked(@Nullable String name) {
        if (blocked.isEmpty() || name == null) {
            return false;
        }
        String normalized = normalize(name);
        if (normalized.isEmpty() || allowed.contains(normalized)) {
            return false;
        }
        if (blocked.contains(normalized.replace(" ", ""))) {
            return true;
        }
        String[] words = normalized.split(" ");
        for (int start = 0; start < words.length; start++) {
            StringBuilder run = new StringBuilder();
            for (int end = start; end < words.length; end++) {
                if (end > start) {
                    run.append(' ');
                }
                run.append(words[end]);
                if (blocked.contains(run.toString())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Lowercases, strips accents, and turns every run of non-letters and non-digits into one space. */
    @Nonnull
    static String normalize(@Nonnull String value) {
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(decomposed.length());
        boolean pendingSpace = false;
        for (int index = 0; index < decomposed.length(); ) {
            int codePoint = decomposed.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.getType(codePoint) == Character.NON_SPACING_MARK) {
                continue;
            }
            if (Character.isLetterOrDigit(codePoint)) {
                if (pendingSpace && out.length() > 0) {
                    out.append(' ');
                }
                pendingSpace = false;
                out.appendCodePoint(codePoint);
            } else {
                pendingSpace = true;
            }
        }
        return out.toString();
    }

    private static void addNormalized(@Nonnull Set<String> target, @Nonnull Collection<String> entries) {
        for (String entry : entries) {
            String normalized = normalize(entry);
            if (!normalized.isEmpty()) {
                target.add(normalized);
            }
        }
    }

    @Nonnull
    private static Set<String> stockEntries() {
        Set<String> entries = stockEntries;
        if (entries != null) {
            return entries;
        }
        Set<String> loaded = new HashSet<>();
        for (String list : STOCK_LISTS) {
            loaded.addAll(readResourceLines(list));
        }
        Set<String> exceptions = new HashSet<>();
        addNormalized(exceptions, readResourceLines(STOCK_EXCEPTIONS));
        loaded.removeIf(entry -> exceptions.contains(normalize(entry)));
        entries = Set.copyOf(loaded);
        stockEntries = entries;
        return entries;
    }

    @Nonnull
    private static Set<String> readResourceLines(@Nonnull String fileName) {
        Set<String> lines = new HashSet<>();
        try (InputStream stream = NameFilter.class.getResourceAsStream(STOCK_RESOURCE_DIRECTORY + fileName)) {
            if (stream == null) {
                return lines;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.isBlank()) {
                    lines.add(line.trim());
                }
            }
        } catch (IOException ignored) {
            // A missing or unreadable stock list only narrows the filter; custom lists still apply.
        }
        return lines;
    }

    private record Compiled(@Nonnull NameFilterSettings settings, @Nonnull NameFilter filter) {
    }
}
