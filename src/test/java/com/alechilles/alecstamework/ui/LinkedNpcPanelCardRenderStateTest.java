package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.BondedCompanionStateView;
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

    private static BondedCompanionPanelPresentation presentation(String xp, boolean actionEnabled) {
        return new BondedCompanionPanelPresentation("profile", "roster", "role", 1L,
                "Wyatt", "Drake", null, null, Map.of("currentXp", xp, "level", "4",
                "levelingConfigId", "levels", "talentConfigId", "talents",
                "talentSpentPoints", "2"), Map.of(), new BondedCompanionStatusPresentation(
                BondedCompanionStateView.ACTIVE, BondedCompanionStatusPresentation.Action.DISMISS,
                actionEnabled, null, null, 0L), null);
    }
}
