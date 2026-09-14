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
