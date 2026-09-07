package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LinkedNpcPanelVitalsBinderTest {
    private static final String MARKER = "#Card #NeedHappiness #BreedingThresholdMarker";

    // Catches a tick placed on the wrong side/direction of the segmented meter,
    // including threshold data lost while normalizing or copying the panel entry.
    @Test
    void rendersBreedingThresholdAtItsHappinessFillPosition() {
        assertMarker(0.10, 3, 0, 2, 3);
        assertMarker(0.25, 0, 10, 3, 2);
        assertMarker(0.50, 10, 21, 2, 3);
        assertMarker(0.70, 21, 17, 3, 2);
        assertMarker(0.95, 17, 0, 2, 3);
        assertMarker(1.0, 12, 0, 2, 3);
    }

    private static void assertMarker(
            double ratio, int left, int top, int width, int height) {
        LinkedNpcEntry entry = entry(true).withBreedingHappinessRatio(ratio)
                .withFlightToggle(true, false).withShoulderRide(false, false);
        LinkedNpcEntry snapshot = LinkedNpcEntrySnapshotMapper.build(List.of(entry))[0];
        UICommandBuilder commands = bind(snapshot);
        UICommandBuilder expected = new UICommandBuilder();
        Anchor anchor = new Anchor();
        anchor.setLeft(Value.of(left));
        anchor.setTop(Value.of(top));
        anchor.setWidth(Value.of(width));
        anchor.setHeight(Value.of(height));
        expected.setObject(MARKER + ".Anchor", anchor);
        expected.set(MARKER + ".Visible", true);
        Assertions.assertEquals(data(expected, MARKER + ".Anchor"), data(commands, MARKER + ".Anchor"));
        Assertions.assertEquals(data(expected, MARKER + ".Visible"), data(commands, MARKER + ".Visible"));
        UICommandBuilder tooltip = new UICommandBuilder();
        tooltip.set("#Card #NeedHappiness #NeedTooltip.TooltipText", "Happiness - 50% -> 50%");
        Assertions.assertEquals(data(tooltip, "#Card #NeedHappiness #NeedTooltip.TooltipText"),
                data(commands, "#Card #NeedHappiness #NeedTooltip.TooltipText"));
    }

    // A reused card must remove its old tick when the next NPC has no requirement.
    @Test
    void hidesMarkerWithoutARequirementOnTheMeter() {
        for (double ratio : new double[] {-1.0, 0.0, Double.NaN, 1.5}) {
            assertHidden(entry(true).withBreedingHappinessRatio(ratio));
        }
        assertHidden(entry(false).withBreedingHappinessRatio(0.7));
    }

    @Test
    void breedingToggleShowsRequirementOnNextLineAndClearsItWhenDisabled() {
        LinkedNpcEntry entry = entry(true).withBreedingHappinessRatio(0.7);
        assertBreedingTooltips(entry, "\nRequires 70 happiness");
        assertBreedingTooltips(entry.withBreedingHappinessRatio(-1), "");
    }

    private static void assertBreedingTooltips(LinkedNpcEntry entry, String requirement) {
        UICommandBuilder commands = new UICommandBuilder();
        LinkedNpcPanelCardBinder.bindBreedingTooltips(commands, "#Card", entry, "en-US");
        UICommandBuilder expected = new UICommandBuilder();
        String enabled = "#Card #BreedingToggleEnabledButton.TooltipText";
        String disabled = "#Card #BreedingToggleDisabledButton.TooltipText";
        expected.set(enabled, "Breeding: enabled. Click to disable." + requirement);
        expected.set(disabled, "Breeding: disabled. Click to enable." + requirement);
        Assertions.assertEquals(data(expected, enabled), data(commands, enabled));
        Assertions.assertEquals(data(expected, disabled), data(commands, disabled));
    }

    private static void assertHidden(LinkedNpcEntry entry) {
        UICommandBuilder commands = bind(entry);
        UICommandBuilder expected = new UICommandBuilder();
        expected.set(MARKER + ".Visible", false);
        Assertions.assertEquals(data(expected, MARKER + ".Visible"), data(commands, MARKER + ".Visible"));
        Assertions.assertFalse(data(commands, "#Card #NeedHappiness #NeedTooltip.TooltipText")
                .contains("Breeding requires"));
    }

    private static UICommandBuilder bind(LinkedNpcEntry entry) {
        UICommandBuilder commands = new UICommandBuilder();
        LinkedNpcPanelVitalsBinder.bind(commands, "#Card", entry, "en-US");
        return commands;
    }

    private static String data(UICommandBuilder commands, String selector) {
        return Arrays.stream(commands.getCommands()).filter(command -> selector.equals(command.selector))
                .map(command -> command.data).findFirst().orElseThrow();
    }

    private static LinkedNpcEntry entry(boolean loaded) {
        return new LinkedNpcEntry(UUID.randomUUID(), "Companion", 100, 100, 50, 100,
                null, 100, 100, 100, 100, loaded, false, false, false, false, false,
                0L, LinkedNpcTraitIndicator.EMPTY);
    }
}
