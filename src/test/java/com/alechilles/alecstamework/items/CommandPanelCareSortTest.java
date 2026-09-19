package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.alechilles.alecstamework.ui.LinkedNpcTraitIndicator;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandPanelCareSortTest {
    @Test
    void selectedAnimalsComeFirstThenLowestPercentageWithinEachSelectionState() {
        for (var sort : List.of(CommandPanelPreferenceService.PanelSort.Happiness,
                CommandPanelPreferenceService.PanelSort.Hunger, CommandPanelPreferenceService.PanelSort.Thirst)) {
            assertCareOrder(sort);
        }
    }

    private void assertCareOrder(CommandPanelPreferenceService.PanelSort sort) {
        var rows = new ArrayList<>(List.of(
                entry("Unknown", 0, 0, true, sort),
                entry("Content", 30, 40, true, sort),
                entry("Needs care", 40, 200, false, sort),
                entry("Empty", 0, 100, true, sort)));
        rows.sort(CommandPanelEntrySourceService.buildComparator(sort));
        assertEquals(List.of("Empty", "Content", "Unknown", "Needs care"),
                rows.stream().map(LinkedNpcEntry::displayName).toList());
    }

    private static LinkedNpcEntry entry(String name, int current, int maximum, boolean active,
                                        CommandPanelPreferenceService.PanelSort sort) {
        return new LinkedNpcEntry(UUID.randomUUID(), name, 10, 10,
                sort == CommandPanelPreferenceService.PanelSort.Happiness ? current : 100,
                sort == CommandPanelPreferenceService.PanelSort.Happiness ? maximum : 100,
                100, null,
                sort == CommandPanelPreferenceService.PanelSort.Hunger ? current : 100,
                sort == CommandPanelPreferenceService.PanelSort.Hunger ? maximum : 100,
                sort == CommandPanelPreferenceService.PanelSort.Thirst ? current : 100,
                sort == CommandPanelPreferenceService.PanelSort.Thirst ? maximum : 100,
                true, false, false, false, false, false, 0L, null, null, null,
                new LinkedNpcTraitIndicator[0], false, false, false, false, true, active,
                null, null, null, null, null, false, false, 0L, 0.0, false);
    }
}
