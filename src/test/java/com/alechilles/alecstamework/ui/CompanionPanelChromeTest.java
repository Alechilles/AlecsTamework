package com.alechilles.alecstamework.ui;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompanionPanelChromeTest {
    // Unloaded owned animals remain in-world, while storage and loss have separate browse tabs.
    @Test void statusTabsKeepUnloadedAnimalsInWorld() {
        var unloaded = entry(false, false, false, false, false);
        var captured = entry(false, false, true, false, false);
        var cooped = entry(false, false, false, true, false);
        var dead = entry(false, true, false, false, false);
        var lost = entry(false, false, false, false, true);
        var all = new LinkedNpcEntry[]{unloaded, captured, cooped, dead, lost};
        assertArrayEquals(new LinkedNpcEntry[]{unloaded}, CompanionPanelChrome.filter(all, "InWorld", false, ""));
        assertArrayEquals(new LinkedNpcEntry[]{captured, cooped}, CompanionPanelChrome.filter(all, "Stored", false, ""));
        assertArrayEquals(new LinkedNpcEntry[]{dead, lost}, CompanionPanelChrome.filter(all, "LostDead", false, ""));
        assertArrayEquals(all, CompanionPanelChrome.filter(all, "All", false, ""));
    }

    // Search must match any assigned group literally, including punctuation; filters never mutate selection.
    @Test void searchFindsSecondaryGroupAndNearbyIsIndependentOfSelection() {
        var animal = entry(true, false, false, false, false).withCompanionGroups("profile", List.of(
                new LinkedNpcEntry.GroupMembership("a", "Barn", "#445566"),
                new LinkedNpcEntry.GroupMembership("b", "Travel [2]", "#445566")), true).withNearby(true);
        var far = entry(false, false, false, false, false);
        var all = new LinkedNpcEntry[]{animal, far};
        assertArrayEquals(new LinkedNpcEntry[]{animal}, CompanionPanelChrome.filter(all, "All", false, "TRAVEL [2]"));
        assertArrayEquals(new LinkedNpcEntry[]{animal}, CompanionPanelChrome.filter(all, "InWorld", true, ""));
        assertEquals(0, CompanionPanelChrome.filter(all, "All", false, ".*").length);
        assertTrue(animal.active());
        assertTrue(far.active());
    }

    private LinkedNpcEntry entry(boolean loaded, boolean dead, boolean captured, boolean cooped, boolean lost) {
        return new LinkedNpcEntry(UUID.randomUUID(), "Companion", 10, 10, 0, 0, null,
                0, 0, 0, 0, loaded, false, dead, captured, cooped, lost,
                0L, new LinkedNpcTraitIndicator[0]);
    }
}
