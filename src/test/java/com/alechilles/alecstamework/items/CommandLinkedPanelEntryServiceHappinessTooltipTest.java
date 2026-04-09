package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.progression.CompanionHappinessModifierService;
import com.alechilles.alecstamework.npc.progression.CompanionHappinessService;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLinkedPanelEntryServiceHappinessTooltipTest {

    @Test
    void breakdownIncludesOnlyActiveFeedImpulses() throws Exception {
        CommandLinkedPanelEntryService service = newService();
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

        String breakdown = invokeBuildHappinessModifierBreakdown(service, snapshot);

        assertTrue(breakdown.contains("Owner nearby: +5.00"));
        assertTrue(breakdown.contains("Ate Herbivore Feed: -10.00"));
        assertFalse(breakdown.contains("Feed (default):"));
        assertFalse(breakdown.contains("Base:"));
        assertFalse(breakdown.contains("Target:"));
    }

    @Test
    void breakdownOmitsFeedLinesWhenNoActiveImpulsesExist() throws Exception {
        CommandLinkedPanelEntryService service = newService();
        CompanionHappinessService.HappinessSnapshot snapshot = new CompanionHappinessService.HappinessSnapshot(
                50.0,
                0.0,
                100.0,
                50.0,
                50.0,
                List.of()
        );

        String breakdown = invokeBuildHappinessModifierBreakdown(service, snapshot);

        assertFalse(breakdown != null && breakdown.contains("Ate "));
        assertTrue(breakdown == null || breakdown.isBlank());
    }

    private static CommandLinkedPanelEntryService newService() {
        return new CommandLinkedPanelEntryService(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static String invokeBuildHappinessModifierBreakdown(CommandLinkedPanelEntryService service,
                                                                CompanionHappinessService.HappinessSnapshot snapshot)
            throws Exception {
        Method method = CommandLinkedPanelEntryService.class.getDeclaredMethod(
                "buildHappinessModifierBreakdown",
                CompanionHappinessService.HappinessSnapshot.class
        );
        method.setAccessible(true);
        return (String) method.invoke(service, snapshot);
    }
}
