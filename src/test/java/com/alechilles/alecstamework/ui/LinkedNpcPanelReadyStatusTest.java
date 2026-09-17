package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LinkedNpcPanelReadyStatusTest {
    @Test
    void completedCooldownsStayVisibleAndUnhappyBreedingExplainsItsBlock() {
        LinkedNpcEntry unhappy = entry(39, false, true);
        UICommandBuilder commands = new UICommandBuilder();
        LinkedNpcPanelVitalsBinder.bind(commands, "#Card", unhappy, "en-US");
        assertTrue(value(commands, "#Card #BreedingCooldown.Visible").contains("true"));
        assertTrue(value(commands, "#Card #HarvestCooldown.Visible").contains("true"));
        assertTrue(value(commands, "#Card #BreedingCooldown #CooldownText.Text").contains("Breeding ready"));
        assertTrue(value(commands, "#Card #HarvestCooldown #CooldownText.Text").contains("Harvest ready"));
        assertTrue(value(commands, "#Card #BreedingCooldown #BreedingCooldownTooltip.TooltipText").contains("Too unhappy"));
        assertTrue(value(commands, "#Card #BreedingCooldown #MeterFill.Background").contains("#727772"));

        commands = new UICommandBuilder();
        LinkedNpcPanelVitalsBinder.bind(commands, "#Card", entry(80, false, true), "en-US");
        assertTrue(value(commands, "#Card #BreedingCooldown #BreedingCooldownTooltip.TooltipText").contains("Breeding ready"));
        assertTrue(value(commands, "#Card #BreedingCooldown #MeterFill.Background").contains("#bb959e"));
    }

    @Test
    void activeCooldownKeepsCountdownAndUnsupportedStatusesStayHidden() {
        UICommandBuilder commands = new UICommandBuilder();
        LinkedNpcPanelVitalsBinder.bind(commands, "#Card", entry(30, true, true), "en-US");
        assertTrue(value(commands, "#Card #BreedingCooldown #CooldownText.Text").contains("1m"));
        assertFalse(LinkedNpcPanelStatusTextService.breedingBlockedByHappiness(entry(30, true, true)));
        commands = new UICommandBuilder();
        LinkedNpcPanelVitalsBinder.bind(commands, "#Card", entry(100, false, false), "en-US");
        assertTrue(value(commands, "#Card #BreedingCooldown.Visible").contains("false"));
        assertTrue(value(commands, "#Card #HarvestCooldown.Visible").contains("false"));
    }

    private static String value(UICommandBuilder commands, String selector) {
        return Arrays.stream(commands.getCommands()).filter(c -> selector.equals(c.selector))
                .reduce((a, b) -> b).orElseThrow().data;
    }

    @Test
    void breedingOffIsMutedAndExplainedBeforeHappiness() {
        UICommandBuilder commands = new UICommandBuilder();
        LinkedNpcPanelVitalsBinder.bind(commands, "#Card", entry(10, false, true, false), "en-US");
        assertTrue(value(commands, "#Card #BreedingCooldown #CooldownText.Text").contains("Breeding Off"));
        assertTrue(value(commands, "#Card #BreedingCooldown #BreedingCooldownTooltip.TooltipText").contains("Breeding is off"));
        assertTrue(value(commands, "#Card #BreedingCooldown #MeterFill.Background").contains("#727772"));
    }

    private static LinkedNpcEntry entry(int happiness, boolean cooldown, boolean known) {
        return entry(happiness, cooldown, known, true);
    }

    private static LinkedNpcEntry entry(int happiness, boolean cooldown, boolean known, boolean enabled) {
        return new LinkedNpcEntry(UUID.randomUUID(), "Sheep", 25, 25, happiness, 100, 80,
                null, 0, 0, 0, 0, true, false, false, false, false, false, 0L,
                null, null, null, LinkedNpcTraitIndicator.EMPTY,
                false, false, false, false, true, true,
                null, null, null, null, null, enabled, cooldown, cooldown ? 60_000L : 0L,
                cooldown ? 0.5 : 1.0, known, false, 0L, 1.0, known)
                .withBreedingHappinessRatio(0.8);
    }
}
