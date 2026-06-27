package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.progression.CompanionHappinessModifierService;
import com.alechilles.alecstamework.npc.progression.CompanionHappinessService;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLinkedPanelEntryServiceHappinessTooltipTest {

    @Test
    void breakdownIncludesOnlyActiveFeedImpulses() {
        CommandLoadedNpcStatusSnapshotService service = newService();
        CompanionHappinessService.HappinessSnapshot snapshot = new CompanionHappinessService.HappinessSnapshot(
                60.0,
                0.0,
                100.0,
                50.0,
                55.0,
                List.of(new CompanionHappinessModifierService.ModifierEntry("owner_nearby", "Owner Nearby", 5.0)),
                List.of(
                        new CompanionHappinessService.ActiveImpulseSnapshot(
                                "feed:param:foodgeneric",
                                "Ate",
                                -10.0,
                                System.currentTimeMillis() + 60_000L,
                                "Tw_Feed_Herbivore"
                        )
                )
        );

        String breakdown = service.buildHappinessModifierBreakdown(snapshot);

        assertTrue(breakdown.contains("Owner nearby: +5.00"));
        assertTrue(breakdown.contains("Ate Herbivore Feed: -10.00"));
        assertFalse(breakdown.contains("Feed (default):"));
        assertFalse(breakdown.contains("Base:"));
        assertFalse(breakdown.contains("Target:"));
    }

    @Test
    void breakdownOmitsFeedLinesWhenNoActiveImpulsesExist() {
        CommandLoadedNpcStatusSnapshotService service = newService();
        CompanionHappinessService.HappinessSnapshot snapshot = new CompanionHappinessService.HappinessSnapshot(
                50.0,
                0.0,
                100.0,
                50.0,
                50.0,
                List.of()
        );

        String breakdown = service.buildHappinessModifierBreakdown(snapshot);

        assertFalse(breakdown != null && breakdown.contains("Ate "));
        assertTrue(breakdown == null || breakdown.isBlank());
    }

    private static CommandLoadedNpcStatusSnapshotService newService() {
        return new CommandLoadedNpcStatusSnapshotService(null, null, null, null);
    }
}
