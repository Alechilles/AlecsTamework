package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.settings.NameFilterSettings;
import com.alechilles.alecstamework.settings.NameFilterSettingsStore;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Blocks companion names that contain a blocked word or phrase.
 *
 * <p>Names and entries are compared after {@link #normalize}: case, accents, punctuation and
 * common lookalike characters ("sh1t", "a55") are ignored, letters spelled out one at a time
 * ("b a d") are joined, and stretched letters ("baaad") match the entry they stretch.
 *
 * <p>There are two kinds of entry. A whole-word entry blocks a name when one of its words, a
 * run of its words, or the whole name with separators removed equals the entry, so an entry
 * inside a longer word does not match and "Cassie" is not caught by a three-letter entry. A
 * severe entry blocks a name when it appears anywhere inside one of its words; that list is
 * kept to words that do not occur inside ordinary names.
 */
public final class NameFilter {
    private static final String STOCK_RESOURCE_DIRECTORY = "/META-INF/tamework/name-filter/";
    /** Third-party whole-word lists, shipped unmodified; {@link #STOCK_EXCEPTIONS} is subtracted. */
    private static final List<String> STOCK_LISTS = List.of("en.txt", "de.txt", "es.txt", "fr.txt", "pt.txt");
    private static final String STOCK_EXCEPTIONS = "exceptions.txt";
    /** Tamework's own whole-word additions. */
    private static final String STOCK_EXTRA = "extra.txt";
    /** Tamework's own severe entries, matched anywhere inside a word. */
    private static final String STOCK_SEVERE = "severe.txt";
    private static final NameFilter ALLOW_ALL = new NameFilter(Map.of(), null, Set.of());

    @Nullable
    private static volatile StockLists stockLists;
    @Nullable
    private static volatile Compiled compiled;

    /**
     * Whole-word entries by their letters with repeats removed, each as the run lengths it needs:
     * "boob" is stored under "bob" as [1, 2, 1], so "booob" matches it and "Bob" does not.
     */
    private final Map<String, List<int[]>> wholeEntries;
    @Nullable
    private final Pattern severe;
    private final Set<String> allowed;

    private NameFilter(@Nonnull Map<String, List<int[]>> wholeEntries, @Nullable Pattern severe,
                       @Nonnull Set<String> allowed) {
        this.wholeEntries = wholeEntries;
        this.severe = severe;
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
        boolean stock = settings.enabled() && settings.useStockList();
        StockLists lists = stock ? stockLists() : StockLists.EMPTY;
        NameFilter filter = of(settings, lists.whole(), lists.severe());
        compiled = new Compiled(settings, filter);
        return filter;
    }

    /**
     * @param stockWhole  built-in whole-word entries, used when the settings enable the stock list
     * @param stockSevere built-in severe entries, used when the settings enable the stock list
     */
    @Nonnull
    static NameFilter of(@Nonnull NameFilterSettings settings, @Nonnull Collection<String> stockWhole,
                         @Nonnull Collection<String> stockSevere) {
        if (!settings.enabled()) {
            return ALLOW_ALL;
        }
        Set<String> whole = new HashSet<>();
        Set<String> severe = new HashSet<>();
        if (settings.useStockList()) {
            addNormalized(whole, stockWhole);
            addNormalized(severe, stockSevere);
        }
        for (String entry : settings.blockedWords()) {
            // A custom entry written as *word* is severe: it matches anywhere inside a word.
            boolean anywhere = entry.length() > 2 && entry.startsWith("*") && entry.endsWith("*");
            addNormalized(anywhere ? severe : whole, List.of(entry));
        }
        Set<String> allowed = new HashSet<>();
        addNormalized(allowed, settings.allowedWords());
        whole.removeAll(allowed);
        severe.removeAll(allowed);

        Map<String, List<int[]>> wholeEntries = new HashMap<>();
        for (String entry : whole) {
            wholeEntries.computeIfAbsent(skeleton(entry), key -> new ArrayList<>()).add(runLengths(entry));
        }
        return new NameFilter(wholeEntries, severePattern(severe), allowed);
    }

    public boolean isBlocked(@Nullable String name) {
        if ((wholeEntries.isEmpty() && severe == null) || name == null) {
            return false;
        }
        String normalized = normalize(name);
        if (normalized.isEmpty() || allowed.contains(normalized)) {
            return false;
        }
        if (matchesWhole(normalized.replace(" ", ""))) {
            return true;
        }
        List<String> words = joinSpelledOutLetters(normalized.split(" "));
        for (int start = 0; start < words.size(); start++) {
            String word = words.get(start);
            if (allowed.contains(word)) {
                continue;
            }
            if (severe != null && severe.matcher(word).find()) {
                return true;
            }
            StringBuilder run = new StringBuilder();
            for (int end = start; end < words.size(); end++) {
                if (end > start) {
                    run.append(' ');
                }
                run.append(words.get(end));
                if (matchesWhole(run.toString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesWhole(@Nonnull String candidate) {
        List<int[]> entries = wholeEntries.get(skeleton(candidate));
        if (entries == null) {
            return false;
        }
        int[] runs = runLengths(candidate);
        for (int[] entry : entries) {
            boolean stretched = true;
            for (int index = 0; index < entry.length && stretched; index++) {
                stretched = runs[index] >= entry[index];
            }
            if (stretched) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lowercases, strips accents, folds lookalike digits and symbols into the letters they stand
     * for, and turns every run of other characters into one space.
     */
    @Nonnull
    static String normalize(@Nonnull String value) {
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(decomposed.length());
        boolean pendingSpace = false;
        for (int index = 0; index < decomposed.length(); ) {
            int codePoint = foldLookalike(decomposed.codePointAt(index));
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

    private static int foldLookalike(int codePoint) {
        return switch (codePoint) {
            case '0' -> 'o';
            case '1' -> 'i';
            case '3' -> 'e';
            case '4', '@' -> 'a';
            case '5', '$' -> 's';
            case '7' -> 't';
            default -> codePoint;
        };
    }

    /** Joins neighbouring one-letter words, so "b a d name" is read as "bad name". */
    @Nonnull
    private static List<String> joinSpelledOutLetters(@Nonnull String[] words) {
        List<String> joined = new ArrayList<>(words.length);
        StringBuilder letters = new StringBuilder();
        for (String word : words) {
            if (word.codePointCount(0, word.length()) == 1) {
                letters.append(word);
                continue;
            }
            if (letters.length() > 0) {
                joined.add(letters.toString());
                letters.setLength(0);
            }
            joined.add(word);
        }
        if (letters.length() > 0) {
            joined.add(letters.toString());
        }
        return joined;
    }

    /** The value with each run of a repeated character reduced to one. */
    @Nonnull
    private static String skeleton(@Nonnull String value) {
        StringBuilder out = new StringBuilder(value.length());
        int previous = -1;
        for (int codePoint : value.codePoints().toArray()) {
            if (codePoint != previous) {
                out.appendCodePoint(codePoint);
            }
            previous = codePoint;
        }
        return out.toString();
    }

    /** The length of each run of a repeated character, in order. */
    @Nonnull
    private static int[] runLengths(@Nonnull String value) {
        int[] codePoints = value.codePoints().toArray();
        int[] runs = new int[codePoints.length];
        int count = 0;
        for (int index = 0; index < codePoints.length; index++) {
            if (index > 0 && codePoints[index] == codePoints[index - 1]) {
                runs[count - 1]++;
            } else {
                runs[count++] = 1;
            }
        }
        return java.util.Arrays.copyOf(runs, count);
    }

    /** One pattern that finds any severe entry, or a stretched spelling of it, inside a word. */
    @Nullable
    private static Pattern severePattern(@Nonnull Set<String> entries) {
        StringBuilder pattern = new StringBuilder();
        for (String entry : entries) {
            String joined = entry.replace(" ", "");
            if (joined.isEmpty()) {
                continue;
            }
            if (pattern.length() > 0) {
                pattern.append('|');
            }
            int[] codePoints = skeleton(joined).codePoints().toArray();
            int[] runs = runLengths(joined);
            for (int index = 0; index < codePoints.length; index++) {
                pattern.append(Pattern.quote(new String(Character.toChars(codePoints[index]))))
                        .append('{').append(runs[index]).append(",}");
            }
        }
        return pattern.length() == 0 ? null : Pattern.compile(pattern.toString());
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
    private static StockLists stockLists() {
        StockLists lists = stockLists;
        if (lists != null) {
            return lists;
        }
        Set<String> whole = new HashSet<>();
        for (String list : STOCK_LISTS) {
            whole.addAll(readResourceLines(list));
        }
        Set<String> exceptions = new HashSet<>();
        addNormalized(exceptions, readResourceLines(STOCK_EXCEPTIONS));
        whole.removeIf(entry -> exceptions.contains(normalize(entry)));
        whole.addAll(readResourceLines(STOCK_EXTRA));
        lists = new StockLists(Set.copyOf(whole), Set.copyOf(readResourceLines(STOCK_SEVERE)));
        stockLists = lists;
        return lists;
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

    private record StockLists(@Nonnull Set<String> whole, @Nonnull Set<String> severe) {
        private static final StockLists EMPTY = new StockLists(Set.of(), Set.of());
    }

    private record Compiled(@Nonnull NameFilterSettings settings, @Nonnull NameFilter filter) {
    }
}
