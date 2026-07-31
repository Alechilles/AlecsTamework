package com.alechilles.alecstamework.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.BondedCompanionStateView;
import java.util.UUID;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Regression coverage for lightweight bonded-card updates. */
class BondedCompanionCardDynamicStateTest {
    @Test
    void liveHealthChangesUseTheIncrementalCardPath() {
        BondedCompanionPanelPresentation previous = presentation("400", "400");
        BondedCompanionPanelPresentation current = presentation("125", "400");

        assertTrue(BondedCompanionCardDynamicState.changedOnlyByLiveFields(
                previous, current));
    }

    @Test
    void flightModeChangesUseTheIncrementalCardPath() {
        BondedCompanionPanelPresentation previous = presentationWithFlightMode(
                "false");
        BondedCompanionPanelPresentation current = presentationWithFlightMode(
                "true");

        assertTrue(BondedCompanionCardDynamicState.changedOnlyByLiveFields(
                previous, current));
    }

    @Test
    void liveHealthChangesDoNotFallBackToAFullCardBindWhenLegacyRowVitalsChange() {
        UUID cardUuid = UUID.fromString("71000000-0000-0000-0000-000000000009");
        BondedCompanionPanelPresentation previous = presentation("400", "400");
        BondedCompanionPanelPresentation current = presentation("125", "400");
        LinkedNpcPanelCardRenderState state = new LinkedNpcPanelCardRenderState();
        LinkedNpcEntry previousEntry = entry(cardUuid, 400);
        LinkedNpcEntry currentEntry = entry(cardUuid, 125);
        state.markRendered(new LinkedNpcEntry[] {previousEntry}, null,
                Map.of(cardUuid, CommandPanelFeaturePresentation.bonded(previous)));

        assertEquals(LinkedNpcPanelCardRenderState.Update.DYNAMIC,
                state.updateAt(0, new LinkedNpcEntry[] {currentEntry}, null,
                        Map.of(cardUuid,
                                CommandPanelFeaturePresentation.bonded(current))));
    }

    private static BondedCompanionPanelPresentation presentation(
            String currentHealth, String maximumHealth
    ) {
        return new BondedCompanionPanelPresentation(
                "profile-1", "roster", "NordicDrake", 1L, "Wyatt",
                "Nordic Drake", "Female", null,
                Map.of("currentHealth", currentHealth,
                        "maxHealth", maximumHealth,
                        "healthPercent", "100"),
                Map.of(), new BondedCompanionStatusPresentation(
                        BondedCompanionStateView.ACTIVE,
                        BondedCompanionStatusPresentation.Action.DISMISS,
                        true, null, null, 0L), null);
    }

    private static LinkedNpcEntry entry(UUID cardUuid, int currentHealth) {
        return new LinkedNpcEntry(cardUuid, "Wyatt", currentHealth, 400,
                0, 0, "", 0, 0, 0, 0, true, false, false, false, false,
                false, 0L, LinkedNpcTraitIndicator.EMPTY);
    }

    private static BondedCompanionPanelPresentation presentationWithFlightMode(
            String airborne
    ) {
        return new BondedCompanionPanelPresentation(
                "profile-1", "roster", "NordicDrake", 1L, "Wyatt",
                "Nordic Drake", "Female", null,
                Map.of("bonded.flightToggle.available", "true",
                        "bonded.flightToggle.airborne", airborne),
                Map.of(), new BondedCompanionStatusPresentation(
                        BondedCompanionStateView.ACTIVE,
                        BondedCompanionStatusPresentation.Action.DISMISS,
                        true, null, null, 0L), null);
    }
}
