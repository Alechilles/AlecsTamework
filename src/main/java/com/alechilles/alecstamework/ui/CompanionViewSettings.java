package com.alechilles.alecstamework.ui;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable presentation settings. Null text/lists resolve to empty values; unknown state/sort
 * choices use the defaults. Choices within a dimension are ORed and dimensions are ANDed.
 * Species and group IDs remain stable across display-name or language changes.
 */
public record CompanionViewSettings(String state,
                                    boolean nearby,
                                    String search,
                                    String sort,
                                    boolean selectedOnly,
                                    List<String> speciesIds,
                                    List<String> groupIds) {
    private static final Set<String> STATES = Set.of("InWorld", "Stored", "LostDead", "All");
    private static final Set<String> SORTS = Set.of(
            "Default", "Name", "Species", "Group", "Happiness", "Hunger", "Thirst");
    private static final int MAX_TEXT_LENGTH = 120;
    private static final int MAX_FILTER_IDS = 128;

    public CompanionViewSettings {
        state = normalizeChoice(state, STATES, "InWorld");
        search = normalizeText(search, MAX_TEXT_LENGTH);
        sort = normalizeChoice(sort, SORTS, "Default");
        speciesIds = normalizeIds(speciesIds);
        groupIds = normalizeIds(groupIds);
    }

    public static CompanionViewSettings defaults() {
        return new CompanionViewSettings("InWorld", false, "", "Default", false, List.of(), List.of());
    }

    public CompanionViewSettings withState(String value) {
        return new CompanionViewSettings(value, nearby, search, sort, selectedOnly, speciesIds, groupIds);
    }

    public CompanionViewSettings withNearby(boolean value) {
        return new CompanionViewSettings(state, value, search, sort, selectedOnly, speciesIds, groupIds);
    }

    public CompanionViewSettings withSearch(String value) {
        return new CompanionViewSettings(state, nearby, value, sort, selectedOnly, speciesIds, groupIds);
    }

    public CompanionViewSettings withSort(String value) {
        return new CompanionViewSettings(state, nearby, search, value, selectedOnly, speciesIds, groupIds);
    }

    public CompanionViewSettings withExtraFilters(boolean selected, List<String> species, List<String> groups) {
        return new CompanionViewSettings(state, nearby, search, sort, selected, species, groups);
    }

    public boolean hasExtraFilters() {
        return selectedOnly || !speciesIds.isEmpty() || !groupIds.isEmpty();
    }

    public LinkedNpcEntry[] filter(LinkedNpcEntry[] entries) {
        LinkedNpcEntry[] base = CompanionPanelChrome.filter(
                entries == null ? new LinkedNpcEntry[0] : entries, state, nearby, search);
        return Arrays.stream(base)
                .filter(entry -> !selectedOnly || entry.active())
                .filter(entry -> speciesIds.isEmpty() || speciesIds.contains(entry.speciesId()))
                .filter(entry -> groupIds.isEmpty()
                        || entry.groupIds().stream().anyMatch(groupIds::contains))
                .toArray(LinkedNpcEntry[]::new);
    }

    private static String normalizeChoice(String raw, Set<String> choices, String fallback) {
        String candidate = normalizeText(raw, MAX_TEXT_LENGTH);
        for (String choice : choices) {
            if (choice.equalsIgnoreCase(candidate)) {
                return choice;
            }
        }
        return fallback;
    }

    private static String normalizeText(String raw, int limit) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.trim();
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit);
    }

    private static List<String> normalizeIds(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : raw) {
            String id = normalizeText(value, MAX_TEXT_LENGTH);
            if (!id.isEmpty()) {
                normalized.add(id);
            }
            if (normalized.size() == MAX_FILTER_IDS) {
                break;
            }
        }
        return List.copyOf(normalized);
    }
}
