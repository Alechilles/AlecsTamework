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

    @Test
    void rendersNeedAsHorizontalMeterWithClampedFill() {
        UICommandBuilder commands = bind(entry(true));
        UICommandBuilder expected = new UICommandBuilder();
        expected.set("#Card #NeedHappiness #NeedValueText.Text", "50%");
        Assertions.assertEquals(data(expected, "#Card #NeedHappiness #NeedValueText.Text"),
                data(commands, "#Card #NeedHappiness #NeedValueText.Text"));
        Anchor anchor = new Anchor();
        anchor.setLeft(Value.of(34));
        anchor.setTop(Value.of(14));
        anchor.setWidth(Value.of(65));
        anchor.setHeight(Value.of(6));
        expected.setObject("#Card #NeedHappiness #MeterFill.Anchor", anchor);
        Assertions.assertEquals(
                data(expected, "#Card #NeedHappiness #MeterFill.Anchor"),
                data(commands, "#Card #NeedHappiness #MeterFill.Anchor")
        );
    }

    @Test
    void formatsCooldownClockAsMinutesAndSeconds() {
        Assertions.assertEquals("0:01", LinkedNpcPanelStatusMeter.formatRemainingClock(1L));
        Assertions.assertEquals("1:05", LinkedNpcPanelStatusMeter.formatRemainingClock(65_000L));
        Assertions.assertEquals("60:00", LinkedNpcPanelStatusMeter.formatRemainingClock(3_600_000L));
        Assertions.assertEquals("0:00", LinkedNpcPanelStatusMeter.formatRemainingClock(-1L));
    }

    // Catches a tick placed on the wrong side/direction of the segmented meter,
    // including threshold data lost while normalizing or copying the panel entry.
    @Test
    void rendersBreedingThresholdAtItsHappinessFillPosition() {
        assertMarker(0.10, 47, 12, 2, 10);
        assertMarker(0.25, 66, 12, 2, 10);
        assertMarker(0.50, 98, 12, 2, 10);
        assertMarker(0.70, 124, 12, 2, 10);
        assertMarker(0.95, 156, 12, 2, 10);
        assertMarker(1.0, 162, 12, 2, 10);
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
