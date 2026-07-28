package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.BondedCompanionResult;
import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import com.alechilles.alecstamework.api.BondedCompanionReviveRequest;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.ui.BondedCompanionPanelPresentation;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Regression coverage for profile/revision-fenced bonded panel mutations. */
class BondedCompanionPanelActionServiceTest {
    @Test
    void staleRenderedRevisionIsSentAsTheMutationFence() {
        var profile = BondedPanelTestFixtures.profile(
                "profile-7", 11L, BondedCompanionStateView.STORED, null, Map.of());
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
                "profile-7", 12L, BondedCompanionStateView.DEAD, null, Map.of());
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

    @Test
    void asyncReviveDoesNotBlockWhileDurablePaymentIsPending() {
        var profile = BondedPanelTestFixtures.profile(
                "profile-7", 12L, BondedCompanionStateView.DEAD, null, Map.of());
        CompletableFuture<BondedCompanionResult<
                com.alechilles.alecstamework.api.BondedCompanionProfileView>>
                durablePayment = new CompletableFuture<>();
        var api = new BondedPanelTestFixtures.StubApi(List.of(profile)) {
            @Override
            public CompletableFuture<BondedCompanionResult<
                    com.alechilles.alecstamework.api.BondedCompanionProfileView>>
                    revive(BondedCompanionReviveRequest request) {
                lastAction = request.action();
                lastQuoteRevision = request.quoteRevision();
                return durablePayment;
            }
        };
        var service = new BondedCompanionPanelActionService(() -> api);
        BondedCompanionPanelPresentation row =
                BondedCompanionPanelFeaturePresentationSource.presentation(
                        profile, 1_000L, profile.reviveQuote());

        var outcome = service.performAsync(
                BondedCompanionPanelActionService.Action.REVIVE,
                BondedPanelTestFixtures.OWNER, "world-a", null, row);

        assertFalse(outcome.toCompletableFuture().isDone());
        durablePayment.complete(new BondedCompanionResult<>(
                BondedCompanionResultCode.SUCCESS, profile, null));
        assertTrue(outcome.toCompletableFuture().join().applied());
    }
}
