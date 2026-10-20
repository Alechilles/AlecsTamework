package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LinkedNpcPanelCardRenderStateTest {
    @Test
    void recoveredCompanionIdentityRebuildsCardActionBindings() {
        LinkedNpcEntry lost = entryForIdentity(UUID.randomUUID());
        LinkedNpcEntry recovered = entryForIdentity(UUID.randomUUID());
        LinkedNpcPanelCardRenderState state = new LinkedNpcPanelCardRenderState();
        state.markRendered(new LinkedNpcEntry[] {lost}, null, Map.of());

        assertEquals(false, state.requiresRebuild(new LinkedNpcEntry[] {lost}, Map.of()));
        assertEquals(true, state.requiresRebuild(new LinkedNpcEntry[] {recovered}, Map.of()));
    }

    private static LinkedNpcEntry entryForIdentity(UUID id) {
        return new LinkedNpcEntry(id, "Raven", 25, 25,
                0, 0, "", 0, 0, 0, 0, true, false, false, false, false,
                false, 0L, LinkedNpcTraitIndicator.EMPTY);
    }

    @Test
    void flightModeChangesRefreshTheCaptionWithoutReopeningThePanel() {
        UUID id = UUID.randomUUID();
        LinkedNpcEntry entry = new LinkedNpcEntry(id, "Drake", 100, 100,
                0, 0, "", 0, 0, 0, 0, true, false, false, false, false,
                false, 0L, LinkedNpcTraitIndicator.EMPTY);
        for (boolean airborne : new boolean[] {true, false}) {
            UICommandBuilder commands = new UICommandBuilder();
            LinkedNpcPanelCardDynamicPresenter.refresh(commands,
                    new UIEventBuilder(),
                    "#Card", id, entry.withFlightToggle(true, !airborne),
                    entry.withFlightToggle(true, airborne), null, null, false, null, "en-US");

            UICommandBuilder expected = new UICommandBuilder();
            String selector = "#Card #FlightToggleButtonCaption.Text";
            expected.set(selector, airborne ? "Flying" : "Grounded");
            assertEquals(expected.getCommands()[0].data,
                    Arrays.stream(commands.getCommands())
                            .filter(command -> selector.equals(command.selector))
                            .reduce((first, last) -> last).orElseThrow().data);
        }
    }

    @Test
    void currentXpOnlyChangeUsesDynamicUpdateWhileActionChangeRebuildsCard() {
        UUID id = UUID.randomUUID();
        LinkedNpcEntry[] entries = {new LinkedNpcEntry(id, "Wyatt", 400, 400,
                0, 0, "", 0, 0, 0, 0, true, false, false, false, false,
                false, 0L, LinkedNpcTraitIndicator.EMPTY)};
        LinkedNpcPanelCardRenderState state = new LinkedNpcPanelCardRenderState();
        state.markRendered(entries, null, Map.of(id,
                CommandPanelFeaturePresentation.bonded(presentation("40", true))));

        assertEquals(LinkedNpcPanelCardRenderState.Update.DYNAMIC, state.updateAt(0,
                entries, null, Map.of(id, CommandPanelFeaturePresentation.bonded(
                        presentation("45", true)))));
        assertEquals(LinkedNpcPanelCardRenderState.Update.FULL, state.updateAt(0,
                entries, null, Map.of(id, CommandPanelFeaturePresentation.bonded(
                        presentation("45", false)))));
    }

    // A row reused after recovery must restore the meters hidden while it was unavailable.
    @Test
    void liveRowRestoresItsInformationAfterAnUnavailablePresentation() {
        UUID id = UUID.randomUUID();
        LinkedNpcEntry unavailable = new LinkedNpcEntry(id, "Duck", 0, 0,
                0, 0, "", 0, 0, 0, 0, false, false, false, false, false,
                false, 0L, LinkedNpcTraitIndicator.EMPTY);
        LinkedNpcEntry live = new LinkedNpcEntry(id, "Duck", 25, 25,
                50, 100, "", 100, 100, 100, 100, true, false, false, false, false,
                false, 0L, LinkedNpcTraitIndicator.EMPTY);
        UICommandBuilder commands = new UICommandBuilder();
        LinkedNpcPanelCardBinder.bindCardLayout(commands, "#Card", unavailable, false, false, false);
        assertVisible(commands, "#Card #NeedRingRow.Visible", false);
        assertVisible(commands, "#Card #TraitStrip.Visible", false);
        LinkedNpcPanelCardBinder.bindCardLayout(commands, "#Card", live, false, true, false);
        assertVisible(commands, "#Card #NeedRingRow.Visible", true);
        assertVisible(commands, "#Card #TraitStrip.Visible", true);
    }

    @Test
    void offlineNeedsKeepTheCardExpandedWhenHealthIsUnknown() {
        UUID id = UUID.randomUUID();
        LinkedNpcEntry offline = new LinkedNpcEntry(id, "Duck", 0, 0,
                50, 100, "", 60, 100, 70, 100, false, false,
                false, false, false, false, 0L, LinkedNpcTraitIndicator.EMPTY);
        UICommandBuilder commands = new UICommandBuilder();

        LinkedNpcPanelCardBinder.bindCardLayout(commands, "#Card", offline, false, false, false);

        assertVisible(commands, "#Card #NeedRingRow.Visible", true);
        assertVisible(commands, "#Card #TraitStrip.Visible", true);
    }

    @Test
    void changedLocationRebindsTheVisibleCard() {
        var entry = new LinkedNpcEntry(UUID.randomUUID(), "Duck", 0, 0,
                0, 0, "", 0, 0, 0, 0, false, false, false, true, false,
                false, 0L, LinkedNpcTraitIndicator.EMPTY);
        var state = new LinkedNpcPanelCardRenderState();
        state.markRendered(LinkedNpcEntrySnapshotMapper.build(java.util.List.of(entry.withLocation(
                new LinkedNpcEntry.Location("Carried by Alec", "", "")))), null, Map.of());
        assertEquals(LinkedNpcPanelCardRenderState.Update.FULL, state.updateAt(0,
                LinkedNpcEntrySnapshotMapper.build(java.util.List.of(entry.withLocation(new LinkedNpcEntry.Location(
                        "In Wooden Chest", "world", "1, 2, 3")))), null, Map.of()));
    }

    @Test
    void ageOnlyCardsKeepTheMeterInsideTheCardWithOrWithoutLocation() {
        var animal = new LinkedNpcEntry(UUID.randomUUID(), "Sheep", 0, 0,
                0, 0, "", 0, 0, 0, 0, false, false, false, false, false,
                false, 0L, LinkedNpcTraitIndicator.EMPTY).withAnimalLifecycle(
                new com.alechilles.alecstamework.npc.progression.AnimalProgressionService.Presentation(
                        "Adult", false, false, false, 60_000, 0.5, 0.5));
        for (boolean location : new boolean[] {false, true}) {
            UICommandBuilder commands = new UICommandBuilder();
            LinkedNpcPanelCardBinder.bindCardLayout(commands, "#Card", animal, false, false, location);
            var card = anchor(commands, "#Card.Anchor");
            var meter = anchor(commands, "#Card #CooldownRow.Anchor");
            org.junit.jupiter.api.Assertions.assertTrue(card.getNumber("Height").intValue()
                    > meter.getNumber("Top").intValue() + meter.getNumber("Height").intValue());
            assertEquals(176, card.getNumber("Height").intValue());
            assertEquals(109, meter.getNumber("Top").intValue());
        }
    }

    @Test
    void capturedContainerDetailsFitAbovePausedMetersWithoutAnotherLocationPage() {
        var animal = new LinkedNpcEntry(UUID.randomUUID(), "Sheep", 81, 81,
                96, 100, "", 86, 100, 99, 100, false, false, false, true, false,
                false, 0L, LinkedNpcTraitIndicator.EMPTY)
                .withAnimalLifecycle(new com.alechilles.alecstamework.npc.progression.AnimalProgressionService.Presentation(
                        "Adult", false, true, false, 1_200_000, 0.5, 0.5))
                .withLocation(new LinkedNpcEntry.Location("Capture Crate in Wooden Chest.", "default",
                        "1054.1, 122.0, 120.4", "220m south, 153m east"));
        UICommandBuilder commands = new UICommandBuilder();
        LinkedNpcPanelCardBinder.bind(commands, new UIEventBuilder(), 0, animal, false, false,
                LinkedNpcPanelCardBindingFactory.create(true, false), "en-US");
        String card = "#TameworkLinkedPanelList[0]";
        assertEquals(176, anchor(commands, card + ".Anchor").getNumber("Height").intValue());
        var location = anchor(commands, card + " #InlineLocation.Anchor");
        var directions = anchor(commands, card + " #InlineLocation #RelativeDistance.Anchor");
        var meters = anchor(commands, card + " #CooldownRow.Anchor");
        org.junit.jupiter.api.Assertions.assertTrue(location.getNumber("Top").intValue()
                + directions.getNumber("Top").intValue() + directions.getNumber("Height").intValue()
                < meters.getNumber("Top").intValue());
        assertVisible(commands, card + " #InlineLocation #CopyButton.Visible", true);
        assertVisible(commands, card + " #LocateButton.Visible", false);

        // A status long enough to wrap keeps its second line clear of the rows and meters below it.
        UICommandBuilder wrapped = new UICommandBuilder();
        LinkedNpcPanelCardBinder.bind(wrapped, new UIEventBuilder(), 0, animal.withLocation(
                new LinkedNpcEntry.Location("Contained in a Soul Lantern in PlayerName's inventory.", "default",
                        "1054.1, 122.0, 120.4", "220m south, 153m east")).withOwnedActions(), false, false,
                LinkedNpcPanelCardBindingFactory.create(true, false), "en-US");
        var wrappedStatus = anchor(wrapped, card + " #InlineLocation #Status.Anchor");
        var wrappedWorld = anchor(wrapped, card + " #InlineLocation #World.Anchor");
        var wrappedDirections = anchor(wrapped, card + " #InlineLocation #RelativeDistance.Anchor");
        var wrappedMeters = anchor(wrapped, card + " #CooldownRow.Anchor");
        assertEquals(wrappedStatus.getNumber("Top").intValue() + 28, wrappedWorld.getNumber("Top").intValue());
        org.junit.jupiter.api.Assertions.assertTrue(anchor(wrapped, card + " #InlineLocation.Anchor")
                .getNumber("Top").intValue() + wrappedDirections.getNumber("Top").intValue()
                + wrappedDirections.getNumber("Height").intValue() < wrappedMeters.getNumber("Top").intValue());
        org.junit.jupiter.api.Assertions.assertTrue(wrappedMeters.getNumber("Top").intValue()
                + wrappedMeters.getNumber("Height").intValue() <= 160);
        assertVisible(commands, card + " #LifecycleProgress #Paused.Visible", true);

        // Capture may clear ownership; restricting actions must not replace the ordinary card layout.
        UICommandBuilder readOnly = new UICommandBuilder();
        LinkedNpcPanelCardBinder.bind(readOnly, new UIEventBuilder(), 0, animal.withOwnedActions(), false, false,
                LinkedNpcPanelCardBindingFactory.create(true, false), "en-US",
                CommandPanelFeaturePresentation.readOnlyManaged());
        assertEquals(anchor(commands, card + ".Anchor"), anchor(readOnly, card + ".Anchor"));
        assertVisible(readOnly, card + " #InlineLocation.Visible", true);
        assertVisible(readOnly, card + " #InlineLocation #CopyButton.Visible", true);
        assertVisible(readOnly, card + " #RemoveButton.Visible", false);
        assertVisible(readOnly, card + " #ActiveToggleInactiveButton.Visible", false);
    }

    // Catches a dead card without its countdown or progress, a countdown that stops at the first
    // render, a Revive/Recover button left outside the strip or shown twice, and a stale strip on
    // a reused live row.
    @Test
    void statusStripShowsReviveProgressThenMovesTheRestoreButtonInside() {
        UUID id = UUID.randomUUID();
        LinkedNpcEntry dead = new LinkedNpcEntry(id, "Duck", 0, 25,
                0, 0, "", 0, 0, 0, 0, false, false, true, false, false,
                false, 1_800_000L, LinkedNpcTraitIndicator.EMPTY).withDeadRespawnTotalMs(3_600_000L);
        String card = "#TameworkLinkedPanelList[0]";
        String strip = card + " #StatusStrip";
        UICommandBuilder counting = bindCard(dead);
        assertVisible(counting, strip + ".Visible", true);
        assertText(counting, strip + " #Text #Primary.Text", "Revives in "
                + LinkedNpcPanelStatusTextService.formatRemainingTime(1_800_000L, "en-US"));
        assertEquals(140, anchor(counting, strip + " #Bar #BarFill.Anchor").getNumber("Width").intValue());
        assertVisible(counting, card + " #RespawnButton.Visible", false);

        UICommandBuilder tick = new UICommandBuilder();
        LinkedNpcPanelCountdownPresenter.refresh(tick, new UIEventBuilder(),
                new LinkedNpcEntry[] {dead}, Map.of(), ignored -> false, 900_000L, "en-US");
        assertText(tick, strip + " #Text #Primary.Text", "Revives in "
                + LinkedNpcPanelStatusTextService.formatRemainingTime(900_000L, "en-US"));
        assertEquals(210, anchor(tick, strip + " #Bar #BarFill.Anchor").getNumber("Width").intValue());

        LinkedNpcEntry ready = new LinkedNpcEntry(id, "Duck", 0, 25,
                0, 0, "", 0, 0, 0, 0, false, false, true, false, false,
                false, 0L, LinkedNpcTraitIndicator.EMPTY).withDeadRespawnTotalMs(3_600_000L);
        UICommandBuilder readyCard = bindCard(ready);
        assertText(readyCard, strip + " #Text #Primary.Text", "Ready to revive");
        assertRestoreButtonInsideStrip(readyCard, card);

        LinkedNpcEntry lost = new LinkedNpcEntry(id, "Duck", 0, 0,
                0, 0, "", 0, 0, 0, 0, false, false, false, false, false,
                true, 0L, LinkedNpcTraitIndicator.EMPTY);
        UICommandBuilder lostCard = bindCard(lost);
        assertText(lostCard, strip + " #Text #State.Text", "LOST");
        assertText(lostCard, strip + " #Text #Primary.Text", "Ready to recover");
        assertRestoreButtonInsideStrip(lostCard, card);

        assertVisible(bindCard(entryForIdentity(id)), strip + ".Visible", false);
    }

    private static UICommandBuilder bindCard(LinkedNpcEntry entry) {
        UICommandBuilder commands = new UICommandBuilder();
        LinkedNpcPanelCardBinder.bind(commands, new UIEventBuilder(), 0, entry, false, false,
                LinkedNpcPanelCardBindingFactory.create(true, false), "en-US");
        return commands;
    }

    private static void assertRestoreButtonInsideStrip(UICommandBuilder commands, String card) {
        assertVisible(commands, card + " #RespawnButton.Visible", true);
        assertVisible(commands, card + " #RespawnButtonCaption.Visible", false);
        var button = anchor(commands, card + " #RespawnButton.Anchor");
        int left = button.getNumber("Left").intValue();
        int top = button.getNumber("Top").intValue();
        // Strip spans x 432..846 and y 36..100; its text ends at x 774.
        org.junit.jupiter.api.Assertions.assertTrue(left >= 774
                && left + button.getNumber("Width").intValue() <= 846);
        org.junit.jupiter.api.Assertions.assertTrue(top >= 36
                && top + button.getNumber("Height").intValue() <= 100);
    }

    private static void assertText(UICommandBuilder commands, String selector, String text) {
        UICommandBuilder expected = new UICommandBuilder();
        expected.set(selector, text);
        assertEquals(expected.getCommands()[0].data, Arrays.stream(commands.getCommands())
                .filter(command -> selector.equals(command.selector))
                .reduce((first, last) -> last).orElseThrow().data);
    }

    private static org.bson.BsonDocument anchor(UICommandBuilder commands, String selector) {
        return org.bson.BsonDocument.parse(Arrays.stream(commands.getCommands())
                .filter(command -> selector.equals(command.selector))
                .reduce((first, last) -> last).orElseThrow().data).getDocument("0");
    }

    private static void assertVisible(UICommandBuilder commands, String selector, boolean visible) {
        UICommandBuilder expected = new UICommandBuilder();
        expected.set(selector, visible);
        String latest = Arrays.stream(commands.getCommands())
                .filter(command -> selector.equals(command.selector))
                .reduce((first, last) -> last).orElseThrow().data;
        assertEquals(expected.getCommands()[0].data, latest);
    }

    private static BondedCompanionPanelPresentation presentation(String xp, boolean actionEnabled) {
        return new BondedCompanionPanelPresentation("profile", "roster", "role", 1L,
                "Wyatt", "Drake", null, null, Map.of("currentXp", xp, "level", "4",
                "levelingConfigId", "levels", "talentConfigId", "talents",
                "talentSpentPoints", "2"), Map.of(), new BondedCompanionStatusPresentation(
                BondedCompanionStateView.ACTIVE, BondedCompanionStatusPresentation.Action.DISMISS,
                actionEnabled, null, null, 0L), null);
    }
}
