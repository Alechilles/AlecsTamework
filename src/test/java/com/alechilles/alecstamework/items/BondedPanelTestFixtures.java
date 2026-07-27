package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.*;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class BondedPanelTestFixtures {
    static final UUID OWNER = UUID.fromString(
            "71000000-0000-0000-0000-000000000001");

    private BondedPanelTestFixtures() {}

    static BondedCompanionProfileView profile(
            String id, long revision, BondedCompanionStateView state,
            UUID liveUuid, Map<String, String> data) {
        return new BondedCompanionProfileView(
                id, OWNER, "hydragon:dragons", "hydragon:dragon",
                "Bonded_Miniwyvern_Storm", "Nimbus", "Miniwyvern", "Male",
                revision, state, state == BondedCompanionStateView.STORED,
                state == BondedCompanionStateView.ACTIVE,
                state == BondedCompanionStateView.DEAD, data,
                state == BondedCompanionStateView.ACTIVE
                        ? new BondedCompanionLeaseView(
                                "lease-1", liveUuid, "world-a", 10L, 0L)
                        : null,
                0L,
                state == BondedCompanionStateView.DEAD
                        ? new BondedCompanionReviveQuote(
                                id, true, "Ingredient_Life_Essence", 2,
                                true, 0L, 9L)
                        : null);
    }

    static BondedCompanionApi api(List<BondedCompanionProfileView> profiles) {
        return new StubApi(profiles);
    }

    static class StubApi implements BondedCompanionApi {
        final List<BondedCompanionProfileView> profiles;
        BondedCompanionActionRequest lastAction;
        long lastQuoteRevision = -1L;

        StubApi(List<BondedCompanionProfileView> profiles) {
            this.profiles = List.copyOf(profiles);
        }

        public BondedCompanionAvailability availability() {
            return BondedCompanionAvailability.availableNow();
        }
        public CompletableFuture<BondedCompanionResult<List<BondedCompanionProfileView>>>
        list(UUID owner, String roster) { return success(profiles); }
        public CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>>
        provision(BondedCompanionProvisionRequest request) { return success(null); }
        public CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>>
        summon(BondedCompanionActionRequest request) {
            lastAction = request; return success(profiles.getFirst());
        }
        public CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>>
        store(BondedCompanionActionRequest request) {
            lastAction = request; return success(profiles.getFirst());
        }
        public CompletableFuture<BondedCompanionResult<BondedCompanionReviveQuote>>
        quoteRevive(BondedCompanionActionRequest request) {
            lastAction = request;
            return success(profiles.getFirst().reviveQuote());
        }
        public CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>>
        revive(BondedCompanionReviveRequest request) {
            lastAction = request.action();
            lastQuoteRevision = request.quoteRevision();
            return success(profiles.getFirst());
        }
        public CompletableFuture<BondedCompanionResult<BondedCompanionExtensionData>>
        getExtensionData(BondedCompanionExtensionDataKey key) {
            return CompletableFuture.completedFuture(
                    BondedCompanionResult.unavailable("test"));
        }
        public CompletableFuture<BondedCompanionResult<BondedCompanionExtensionData>>
        compareAndSetExtensionData(BondedCompanionExtensionDataUpdate update) {
            return CompletableFuture.completedFuture(
                    BondedCompanionResult.unavailable("test"));
        }
        public AutoCloseable subscribe(
                java.util.function.Consumer<BondedCompanionChangedEvent> listener) {
            return () -> {};
        }
        private static <T> CompletableFuture<BondedCompanionResult<T>> success(T value) {
            return CompletableFuture.completedFuture(new BondedCompanionResult<>(
                    BondedCompanionResultCode.SUCCESS, value, null));
        }
    }
}
