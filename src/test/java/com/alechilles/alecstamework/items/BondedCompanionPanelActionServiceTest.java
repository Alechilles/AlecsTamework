package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.ui.BondedCompanionPanelPresentation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Regression coverage for profile/revision-fenced bonded panel mutations. */
class BondedCompanionPanelActionServiceTest {
    @Test
    void staleRenderedRevisionIsSentAsTheMutationFence() {
        var profile = BondedPanelTestFixtures.profile(
                "profile-7", 11L, BondedCompanionState.STORED, null, Map.of());
        var api = new BondedPanelTestFixtures.StubApi(List.of(profile));
        var service = new BondedCompanionPanelActionService(() -> api);
        BondedCompanionPanelPresentation row =
                BondedCompanionPanelFeaturePresentationSource.presentation(
                        profile, 1_000L, null);

        service.perform(BondedCompanionPanelActionService.Action.SUMMON,
                BondedPanelTestFixtures.OWNER, "world-a", row);

        assertEquals("profile-7", api.lastAction.profileId());
        assertEquals(11L, api.lastAction.expectedRevision());
        assertEquals("hydragon:dragons", api.lastAction.rosterId());
    }

    @Test
    void reviveCommitsTheExactRenderedQuoteRevision() {
        var profile = BondedPanelTestFixtures.profile(
                "profile-7", 12L, BondedCompanionState.DEAD, null, Map.of());
        var api = new BondedPanelTestFixtures.StubApi(List.of(profile));
        var service = new BondedCompanionPanelActionService(() -> api);
        BondedCompanionPanelPresentation row =
                BondedCompanionPanelFeaturePresentationSource.presentation(
                        profile, 1_000L, profile.reviveQuote());

        service.perform(BondedCompanionPanelActionService.Action.REVIVE,
                BondedPanelTestFixtures.OWNER, "world-a", row);

        assertEquals(12L, api.lastAction.expectedRevision());
        assertEquals("profile-7", api.lastAction.profileId());
        assertEquals(9L, api.lastQuoteRevision);
    }
}
