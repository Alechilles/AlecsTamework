package com.alechilles.alecstamework.ui;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandGroupQuickSelectionTest {
    @Test void highlightsEveryFullySelectedGroupIncludingOverlappingMemberships() {
        var barn = entry(true, "barn");
        var overlap = entry(true, "barn", "travel");
        var travel = entry(false, "travel");
        var entries = new LinkedNpcEntry[]{barn, overlap};
        assertTrue(CommandGroupQuickSelectBinder.groupSelected(entries, "barn"));
        assertTrue(CommandGroupQuickSelectBinder.groupSelected(entries, "travel"));
        assertFalse(CommandGroupQuickSelectBinder.groupSelected(entries, "empty"));
        assertFalse(CommandGroupQuickSelectBinder.groupSelected(new LinkedNpcEntry[]{barn, overlap, travel}, "travel"));
        assertFalse(CommandGroupQuickSelectBinder.groupSelected(new LinkedNpcEntry[]{barn, overlap, travel}, "__all__"));
    }

    private LinkedNpcEntry entry(boolean active, String... groups) {
        UUID id = UUID.randomUUID();
        return new LinkedNpcEntry(id, "Sheep", 10, 10, 0, 0, 0, null,
                0, 0, 0, 0, true, false, false, false, false, false, 0L, null, null, null,
                new LinkedNpcTraitIndicator[0], false, false, false, false, true, active,
                "Sheep", "Sheep", null, null, null, false, false, 0L, 0.0, false)
                .withCompanionGroups(id.toString(), java.util.Arrays.stream(groups)
                        .map(g -> new LinkedNpcEntry.GroupMembership(g, g, "#445566")).toList(), true);
    }
}
