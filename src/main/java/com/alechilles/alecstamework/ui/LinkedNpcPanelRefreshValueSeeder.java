package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import java.util.List;
import java.util.function.Supplier;

/** Seeds refresh deduplication from the values emitted in the initial page packet. */
final class LinkedNpcPanelRefreshValueSeeder {
    private LinkedNpcPanelRefreshValueSeeder() { }
    static void seed(LinkedNpcPanelRefreshValues values, String language, LinkedNpcEntry[] entries,
                     String pendingInput, Supplier<String> empty, Supplier<String> mode,
                     Supplier<Boolean> autoLink, Supplier<String> radius, Supplier<String> sort,
                     Supplier<String> filter, Supplier<String> input,
                     Supplier<List<DropdownEntryInfo>> groupEntries, Supplier<String> groupValue) {
        values.remember("#TameworkLinkedPanelTitle.Text", LinkedNpcPanelPresentationSupport.title(mode, entries, language)); values.remember("#TameworkLinkedPanelGroupSelectorDropdown.Entries", LinkedNpcPanelPresentationSupport.entries(groupEntries));
        values.remember("#TameworkLinkedPanelGroupSelectorDropdown.Value", LinkedNpcPanelPresentationSupport.value(groupValue, "")); values.remember("#TameworkLinkedPanelModeDropdown.Entries", CommandSelectionPanelOptions.resolveModeDropdownEntries(language));
        values.remember("#TameworkLinkedPanelModeDropdown.Value", LinkedNpcPanelPresentationSupport.mode(mode)); values.remember("#TameworkLinkedPanelAutoLinkCheck.Value", LinkedNpcPanelPresentationSupport.autoLink(autoLink));
        values.remember("#TameworkLinkedPanelSubtitleRadiusControls.Visible", LinkedNpcPanelPresentationSupport.nearby(mode)); values.remember("#TameworkLinkedPanelRadiusValue.Text", LinkedNpcPanelPresentationSupport.radius(radius, language));
        values.remember("#TameworkLinkedPanelSortDropdown.Entries", CommandSelectionPanelOptions.resolveSortDropdownEntries(language)); values.remember("#TameworkLinkedPanelSortDropdown.Value", LinkedNpcPanelPresentationSupport.sort(sort));
        values.remember("#TameworkLinkedPanelFilterDropdown.Entries", CommandSelectionPanelOptions.resolveFilterModeDropdownEntries(language)); values.remember("#TameworkLinkedPanelFilterDropdown.Value", LinkedNpcPanelPresentationSupport.filterMode(filter));
        values.remember("#TameworkLinkedPanelInlineFilterTextControls.Visible", LinkedNpcPanelPresentationSupport.showFilter(filter)); values.remember("#TameworkLinkedPanelFilterInput.Value", pendingInput != null ? pendingInput : LinkedNpcPanelPresentationSupport.input(input));
        values.remember("#TameworkLinkedPanelEmptyState.Text", LinkedNpcPanelPresentationSupport.empty(empty, language)); boolean hasEntries = entries.length > 0; values.remember("#TameworkLinkedPanelEmptyState.Visible", !hasEntries); values.remember("#TameworkLinkedPanelListViewport.Visible", hasEntries);
    }
}
