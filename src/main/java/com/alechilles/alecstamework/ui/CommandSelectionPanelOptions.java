package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Builds localized dropdown entries used by the command selection linked panel.
 */
final class CommandSelectionPanelOptions {
    private static final Map<String, List<DropdownEntryInfo>> MODE_ENTRIES = new ConcurrentHashMap<>();
    private static final Map<String, List<DropdownEntryInfo>> SORT_ENTRIES = new ConcurrentHashMap<>();
    private static final Map<String, List<DropdownEntryInfo>> FILTER_ENTRIES = new ConcurrentHashMap<>();
    private CommandSelectionPanelOptions() {
    }

    @Nonnull
    static List<DropdownEntryInfo> resolveModeDropdownEntries(@Nullable String language) {
        return MODE_ENTRIES.computeIfAbsent(language == null ? "" : language, ignored -> List.of(
                new DropdownEntryInfo(
                        LocalizableString.fromString(LocalizedText.resolve(language, "tamework.ui.linkedPanel.mode.linked")),
                        TameworkCommandSelectionPage.PANEL_MODE_LINKED
                ),
                new DropdownEntryInfo(
                        LocalizableString.fromString(LocalizedText.resolve(language, "tamework.ui.linkedPanel.mode.nearby")),
                        TameworkCommandSelectionPage.PANEL_MODE_NEARBY
                )
        ));
    }

    @Nonnull
    static List<DropdownEntryInfo> resolveSortDropdownEntries(@Nullable String language) {
        return SORT_ENTRIES.computeIfAbsent(language == null ? "" : language, ignored -> List.of(
                new DropdownEntryInfo(
                        LocalizableString.fromString(LocalizedText.resolve(language, "tamework.ui.linkedPanel.sort.default")),
                        TameworkCommandSelectionPage.PANEL_SORT_DEFAULT
                ),
                new DropdownEntryInfo(
                        LocalizableString.fromString(LocalizedText.resolve(language, "tamework.ui.linkedPanel.sort.name")),
                        "Name"
                ),
                new DropdownEntryInfo(
                        LocalizableString.fromString(LocalizedText.resolve(language, "tamework.ui.linkedPanel.sort.species")),
                        "Species"
                ),
                new DropdownEntryInfo(
                        LocalizableString.fromString(LocalizedText.resolve(language, "tamework.ui.linkedPanel.sort.group")),
                        "Group"
                )
        ));
    }

    @Nonnull
    static List<DropdownEntryInfo> resolveFilterModeDropdownEntries(@Nullable String language) {
        return FILTER_ENTRIES.computeIfAbsent(language == null ? "" : language, ignored -> List.of(
                new DropdownEntryInfo(
                        LocalizableString.fromString(LocalizedText.resolve(language, "tamework.ui.linkedPanel.filter.none")),
                        TameworkCommandSelectionPage.PANEL_FILTER_NONE
                ),
                new DropdownEntryInfo(
                        LocalizableString.fromString(LocalizedText.resolve(language, "tamework.ui.linkedPanel.filter.name")),
                        "Name"
                ),
                new DropdownEntryInfo(
                        LocalizableString.fromString(LocalizedText.resolve(language, "tamework.ui.linkedPanel.filter.species")),
                        "Species"
                ),
                new DropdownEntryInfo(
                        LocalizableString.fromString(LocalizedText.resolve(language, "tamework.ui.linkedPanel.filter.group")),
                        "Group"
                )
        ));
    }
}
