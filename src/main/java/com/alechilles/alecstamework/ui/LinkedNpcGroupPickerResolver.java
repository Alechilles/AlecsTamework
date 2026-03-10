package com.alechilles.alecstamework.ui;

import java.util.List;
import java.util.function.Supplier;

/**
 * Resolves group picker options, selected values, and paging bounds for linked NPC cards.
 */
final class LinkedNpcGroupPickerResolver {
    private LinkedNpcGroupPickerResolver() {
    }

    static List<LinkedNpcGroupPickerOption> resolveOptions(Supplier<List<LinkedNpcGroupPickerOption>> optionsSupplier,
                                                           String noneValue,
                                                           String noneColor) {
        List<LinkedNpcGroupPickerOption> provided = optionsSupplier != null ? optionsSupplier.get() : List.of();
        if (provided == null || provided.isEmpty()) {
            return List.of(new LinkedNpcGroupPickerOption(noneValue, noneValue, noneColor));
        }
        return provided;
    }

    static String resolveSelectedGroupValue(LinkedNpcEntry entry, String noneValue) {
        String selectedGroupId = entry != null ? normalizeSelectedGroupId(entry.groupId(), noneValue) : null;
        if (isBlank(selectedGroupId)) {
            return noneValue;
        }
        return selectedGroupId;
    }

    static String normalizeSelectedGroupId(String rawValue, String noneValue) {
        if (isBlank(rawValue)) {
            return null;
        }
        String trimmed = rawValue.trim();
        if (trimmed.isBlank() || noneValue.equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed;
    }

    static int resolveMaxPageIndex(List<LinkedNpcGroupPickerOption> groupPickerOptions, int pageSize) {
        if (pageSize <= 0 || groupPickerOptions == null || groupPickerOptions.isEmpty()) {
            return 0;
        }
        int validOptionCount = 0;
        for (LinkedNpcGroupPickerOption option : groupPickerOptions) {
            if (option == null || isBlank(option.value())) {
                continue;
            }
            validOptionCount++;
        }
        if (validOptionCount <= 0) {
            return 0;
        }
        return (validOptionCount - 1) / pageSize;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
