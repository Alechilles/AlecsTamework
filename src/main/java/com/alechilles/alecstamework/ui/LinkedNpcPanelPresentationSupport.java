package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Resolves pure linked-panel presentation values without owning page lifecycle state. */
final class LinkedNpcPanelPresentationSupport {
    private LinkedNpcPanelPresentationSupport() { }

    static LinkedNpcEntry[] filter(LinkedNpcEntry[] source, String mode, String text) {
        if (source == null || source.length == 0 || mode == null || mode.isBlank()
                || TameworkCommandSelectionPage.PANEL_FILTER_NONE.equalsIgnoreCase(mode)
                || text == null || text.isBlank()) return source == null ? new LinkedNpcEntry[0] : source;
        String needle = text.trim().toLowerCase(Locale.ROOT);
        ArrayList<LinkedNpcEntry> filtered = new ArrayList<>(source.length);
        for (LinkedNpcEntry entry : source) {
            if (entry != null && matches(entry, mode, needle)) filtered.add(entry);
        }
        return filtered.toArray(new LinkedNpcEntry[0]);
    }

    static String mode(Supplier<String> supplier) { return value(supplier, TameworkCommandSelectionPage.PANEL_MODE_LINKED); }
    static String sort(Supplier<String> supplier) { return value(supplier, TameworkCommandSelectionPage.PANEL_SORT_DEFAULT); }
    static String filterMode(Supplier<String> supplier) { return value(supplier, TameworkCommandSelectionPage.PANEL_FILTER_NONE); }
    static boolean autoLink(Supplier<Boolean> supplier) { return supplier == null || !Boolean.FALSE.equals(supplier.get()); }
    static boolean activeHighlight(Supplier<Boolean> supplier) { return supplier != null && Boolean.TRUE.equals(supplier.get()); }
    static boolean nearby(Supplier<String> supplier) { return TameworkCommandSelectionPage.PANEL_MODE_NEARBY.equalsIgnoreCase(mode(supplier)); }
    static boolean showFilter(Supplier<String> supplier) { return !TameworkCommandSelectionPage.PANEL_FILTER_NONE.equalsIgnoreCase(filterMode(supplier)); }
    static String input(Supplier<String> supplier) { return supplier == null || supplier.get() == null ? "" : supplier.get(); }
    static String radius(Supplier<String> supplier, String language) {
        String value = supplier == null ? null : supplier.get();
        return value == null || value.isBlank() ? LocalizedText.format(language, "tamework.ui.linkedPanel.radius.value", 24) : value;
    }
    static String title(Supplier<String> mode, LinkedNpcEntry[] entries, String language) {
        String key = nearby(mode) ? "tamework.ui.linkedPanel.title.nearby" : "tamework.ui.linkedPanel.title.linked";
        return LocalizedText.resolve(language, key) + " (" + (entries == null ? 0 : entries.length) + ")";
    }
    static String empty(Supplier<String> supplier, String language) {
        String key = supplier == null ? null : supplier.get();
        return LocalizedText.resolve(language, key == null || key.isBlank() ? "tamework.ui.linkedPanel.emptyState" : key);
    }
    static List<DropdownEntryInfo> entries(Supplier<List<DropdownEntryInfo>> supplier) {
        List<DropdownEntryInfo> value = supplier == null ? null : supplier.get(); return value == null ? List.of() : value;
    }
    static String value(Supplier<String> supplier, String fallback) {
        String value = supplier == null ? null : supplier.get(); return value == null || value.isBlank() ? fallback : value;
    }
    private static boolean matches(LinkedNpcEntry entry, String mode, String needle) {
        String candidate = switch (mode.trim().toLowerCase(Locale.ROOT)) {
            case "name" -> entry.displayName(); case "species" -> first(entry.speciesLabel(), entry.speciesId());
            case "group" -> first(entry.groupName(), entry.groupId()); default -> null;
        };
        return candidate != null && !candidate.isBlank() && candidate.toLowerCase(Locale.ROOT).contains(needle);
    }
    private static String first(String one, String two) { return one != null && !one.isBlank() ? one : two == null ? "" : two; }
}
