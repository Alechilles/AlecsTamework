package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LinkedNpcPanelCardRenderStateTest {
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
        LinkedNpcPanelCardBinder.bindCardLayout(commands, "#Card", unavailable, false, false);
        assertVisible(commands, "#Card #NeedRingRow.Visible", false);
        assertVisible(commands, "#Card #TraitStrip.Visible", false);
        LinkedNpcPanelCardBinder.bindCardLayout(commands, "#Card", live, false, true);
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

        LinkedNpcPanelCardBinder.bindCardLayout(commands, "#Card", offline, false, false);

        assertVisible(commands, "#Card #NeedRingRow.Visible", true);
        assertVisible(commands, "#Card #TraitStrip.Visible", true);
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
