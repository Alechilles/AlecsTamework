package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Tracks the inline linked-panel group assignment modal state between UI refreshes.
 */
final class LinkedNpcPanelGroupAssignOverlayState {
    static final String NONE_VALUE = "None";

    private boolean visible;
    private UUID npcUuid;
    private String npcName;
    private String selectedValue;
    private List<DropdownEntryInfo> entries;

    LinkedNpcPanelGroupAssignOverlayState(@Nullable String language) {
        clear(language);
    }

    boolean isVisible() {
        return visible;
    }

    void updateSelectedValue(@Nullable String value) {
        selectedValue = normalizeDropdownValue(value);
    }

    void open(@Nonnull UUID npcUuid,
              @Nonnull LinkedNpcEntry entry,
              @Nullable List<DropdownEntryInfo> dropdownEntries,
              @Nullable String language) {
        this.visible = true;
        this.npcUuid = npcUuid;
        this.npcName = entry.displayName();
        this.entries = normalizeEntries(dropdownEntries, language);
        this.selectedValue = normalizeDropdownValue(entry.groupId());
    }

    void clear(@Nullable String language) {
        visible = false;
        npcUuid = null;
        npcName = LocalizedText.resolve(language, "tamework.ui.linkedPanel.subtitle.defaultNpcName");
        selectedValue = NONE_VALUE;
        entries = fallbackEntries(language);
    }

    AppliedSelection consumeSelection(@Nullable String language) {
        UUID selectedNpcUuid = npcUuid;
        String groupId = normalizeGroupIdForAssignment(selectedValue);
        clear(language);
        return new AppliedSelection(selectedNpcUuid, groupId);
    }

    void applyTo(@Nonnull UICommandBuilder commandBuilder, @Nullable String language) {
        commandBuilder.set("#TameworkLinkedPanelGroupAssignOverlay.Visible", visible);
        if (!visible) {
            return;
        }
        commandBuilder.set(
                "#TameworkLinkedPanelGroupAssignSubtitle.Text",
                LocalizedText.format(
                        language,
                        "tamework.ui.linkedPanel.groupAssign.subtitle",
                        resolveNpcName(language)
                )
        );
        commandBuilder.set("#TameworkLinkedPanelGroupAssignDropdown.Entries", entries);
        commandBuilder.set("#TameworkLinkedPanelGroupAssignDropdown.Value", selectedValue);
    }

    private String resolveNpcName(@Nullable String language) {
        if (npcName == null || npcName.isBlank()) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.subtitle.defaultNpcName");
        }
        return npcName;
    }

    private List<DropdownEntryInfo> normalizeEntries(@Nullable List<DropdownEntryInfo> dropdownEntries,
                                                     @Nullable String language) {
        if (dropdownEntries == null || dropdownEntries.isEmpty()) {
            return fallbackEntries(language);
        }
        return dropdownEntries;
    }

    private static List<DropdownEntryInfo> fallbackEntries(@Nullable String language) {
        return List.of(new DropdownEntryInfo(
                LocalizableString.fromString(LocalizedText.resolve(language, "tamework.ui.linkedPanel.groupAssign.none")),
                NONE_VALUE
        ));
    }

    private static String normalizeDropdownValue(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return NONE_VALUE;
        }
        String trimmed = value.trim();
        if (trimmed.isBlank() || trimmed.equalsIgnoreCase(NONE_VALUE)) {
            return NONE_VALUE;
        }
        return trimmed;
    }

    private static String normalizeGroupIdForAssignment(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isBlank() || trimmed.equalsIgnoreCase(NONE_VALUE)) {
            return null;
        }
        return trimmed;
    }

    record AppliedSelection(@Nullable UUID npcUuid, @Nullable String groupId) { }
}
