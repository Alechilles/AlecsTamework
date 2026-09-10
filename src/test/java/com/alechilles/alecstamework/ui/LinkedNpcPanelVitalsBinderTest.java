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
    void legacyDeadHealthKeepsAnEmptyBarWithoutInventingAMaximum() {
        LinkedNpcEntry dead = new LinkedNpcEntry(UUID.randomUUID(), "Duck", 0, 0,
                0, 0, "", 0, 0, 0, 0, false, false, true,
                false, false, false, 0L, LinkedNpcTraitIndicator.EMPTY);
        UICommandBuilder commands = bind(dead);
        UICommandBuilder expected = new UICommandBuilder();
        expected.set("#Card #HealthText.Text", "0/?");
        expected.set("#Card #HealthFill.Visible", true);
        for (var command : expected.getCommands()) {
            Assertions.assertEquals(command.data, data(commands, command.selector));
        }
    }

    @Test
    void deadHealthUsesZeroEvenWhenLastSavedHealthWasPositive() {
        LinkedNpcEntry dead = new LinkedNpcEntry(UUID.randomUUID(), "Duck", 25, 25,
                50, 100, "", 60, 100, 70, 100, false, false, true,
                false, false, false, 0L, LinkedNpcTraitIndicator.EMPTY);
        UICommandBuilder commands = bind(dead);
        UICommandBuilder expected = new UICommandBuilder();
        expected.set("#Card #HealthText.Text", "0/25");
        var fill = LinkedNpcPanelAnchorFactory.buildHealthFillAnchor(0.0, 232);
        fill.setHeight(Value.of(20));
        expected.setObject("#Card #HealthFill.Anchor", fill);
        Assertions.assertEquals(data(expected, "#Card #HealthText.Text"), data(commands, "#Card #HealthText.Text"));
        Assertions.assertEquals(data(expected, "#Card #HealthFill.Anchor"), data(commands, "#Card #HealthFill.Anchor"));
    }

    @Test
    void rendersNeedAsHorizontalMeterWithClampedFill() {
        UICommandBuilder commands = bind(entry(true));
        UICommandBuilder expected = new UICommandBuilder();
        expected.set("#Card #NeedHappiness #NeedValueText.Text", "50%");
        Assertions.assertEquals(data(expected, "#Card #NeedHappiness #NeedValueText.Text"),
                data(commands, "#Card #NeedHappiness #NeedValueText.Text"));
        Anchor anchor = new Anchor();
        anchor.setLeft(Value.of(92));
        anchor.setTop(Value.of(8));
        anchor.setWidth(Value.of(46));
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

    @Test
    void keepsKnownVitalsAndCooldownsVisibleForOfflineEntry() {
        LinkedNpcEntry entry = offlineEntry(125_000L, true).withBreedingHappinessRatio(0.7);

        UICommandBuilder commands = bind(entry);

        UICommandBuilder expected = new UICommandBuilder();
        expected.set("#Card #HealthText.Text", "40/100");
        expected.set("#Card #NeedHappiness #NeedValueText.Text", "50%");
        expected.set("#Card #NeedHunger #NeedValueText.Text", "60%");
        expected.set("#Card #NeedThirst #NeedValueText.Text", "70%");
        expected.set("#Card #BreedingCooldown.Visible", true);
        expected.set("#Card #HarvestCooldown.Visible", true);
        expected.set(MARKER + ".Visible", true);
        for (var command : expected.getCommands()) {
            Assertions.assertEquals(command.data, data(commands, command.selector));
        }
        Assertions.assertTrue(data(commands, "#Card #HealthTooltip.TooltipText").contains("Last known"));
        Assertions.assertTrue(data(commands, "#Card #BreedingCooldown #BreedingCooldownTooltip.TooltipText")
                .contains("Last known"));
    }

    @Test
    void doesNotCallAnOfflineCooldownReadyWhenSavedTimerIsUnknown() {
        LinkedNpcEntry entry = offlineEntry(-1L, false);
        UICommandBuilder commands = bind(entry);

        String label = data(commands, "#Card #HarvestCooldown #CooldownText.Text");
        Assertions.assertTrue(label.contains("Unknown"));
        Assertions.assertFalse(label.contains("Harvest ready"));
    }

    private static LinkedNpcEntry offlineEntry(long harvestRemainingMs, boolean harvestActive) {
        return new LinkedNpcEntry(
                UUID.randomUUID(), "Offline Companion", null,
                40, 100, 50, 100, 50, null,
                60, 100, 70, 100, false, false, false, false, false, false,
                0L, null, null, null, LinkedNpcTraitIndicator.EMPTY,
                false, false, false, false, true, true,
                null, null, null, null, null, true, true, true,
                65_000L, 0.5, true, harvestActive, harvestRemainingMs, 0.25, true,
                false, 0L
        );
    }

    // Catches a tick placed on the wrong side of the meter,
    // including threshold data lost while normalizing or copying the panel entry.
    @Test
    void rendersBreedingThresholdAtItsHappinessFillPosition() {
        assertMarker(0.10, 101, 6, 2, 10);
        assertMarker(0.25, 115, 6, 2, 10);
        assertMarker(0.50, 137, 6, 2, 10);
        assertMarker(0.70, 155, 6, 2, 10);
        assertMarker(0.95, 178, 6, 2, 10);
        assertMarker(1.0, 182, 6, 2, 10);
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
