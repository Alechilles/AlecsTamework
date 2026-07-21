package com.alechilles.alecstamework.api;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Mutation-bound public authority for durable companion vessel bindings. */
public interface BondedVesselsApi {
    @Nonnull
    Optional<BondedVesselView> getByBindingId(@Nonnull UUID bindingId);

    @Nonnull
    Optional<BondedVesselView> getByProfileId(@Nonnull String profileId);

    @Nonnull
    BondedVesselReadinessView readiness();

    @Nonnull
    BondedVesselProjectionValidationView validateProjection(
            @Nonnull BondedVesselProjectionValidationRequest request
    );

    /**
     * Resolves one exact actor-held item to canonical vessel identity without exposing item or
     * persistence internals. Implementations must re-read the supplied holder/container/slot,
     * revision, and fingerprint and return authority only for one owner- and state-matching row.
     */
    @Nonnull
    default CompletionStage<BondedVesselHeldItemProjectionView> resolveHeldItemProjection(
            @Nonnull BondedVesselHeldItemProjectionRequest request
    ) {
        return CompletableFuture.completedFuture(BondedVesselHeldItemProjectionView.unavailable(
                java.util.Objects.requireNonNull(request, "request")));
    }

    @Nonnull
    CompletionStage<BondedVesselOperationResult> prepareTransition(
            @Nonnull BondedVesselTransitionRequest request
    );

    /**
     * Revalidates caller and exact source evidence, then issues a fresh process-local token for an
     * existing nonterminal operation after restart. It never rolls generation forward again.
     */
    @Nonnull
    CompletionStage<BondedVesselOperationResult> resumeTransition(
            @Nonnull BondedVesselTransitionRequest request
    );

    @Nonnull
    BondedVesselOperationResult claimForApply(@Nonnull BondedVesselTransitionToken token);

    /**
     * Revalidates the exact holder/container/slot/fingerprint, performs Tamework's authoritative
     * item/profile/world mutation, records APPLIED, and closes the journal. Callers do not attest
     * or directly mutate Tamework vessel state.
     */
    @Nonnull
    CompletionStage<BondedVesselOperationResult> commit(@Nonnull BondedVesselTransitionToken token);

    @Nonnull
    CompletionStage<BondedVesselOperationResult> cancel(@Nonnull BondedVesselTransitionToken token);

    @Nonnull
    CompletionStage<Optional<BondedVesselOperationView>> findOperation(
            @Nonnull String callerNamespace,
            @Nonnull String idempotencyKey
    );

    /** Compatibility fallback for implementations that do not advertise bonded vessels. */
    static BondedVesselsApi unavailable() {
        return UnavailableHolder.INSTANCE;
    }

    final class UnavailableHolder {
        private static final String REASON = "bonded-vessel-authority-unavailable";
        private static final BondedVesselsApi INSTANCE = new BondedVesselsApi() {
            @Override
            public Optional<BondedVesselView> getByBindingId(UUID bindingId) {
                if (bindingId == null) throw new NullPointerException("bindingId");
                return Optional.empty();
            }

            @Override
            public Optional<BondedVesselView> getByProfileId(String profileId) {
                if (profileId == null) throw new NullPointerException("profileId");
                return Optional.empty();
            }

            @Override
            public BondedVesselReadinessView readiness() {
                return BondedVesselReadinessView.unavailable();
            }

            @Override
            public BondedVesselProjectionValidationView validateProjection(
                    BondedVesselProjectionValidationRequest request
            ) {
                if (request == null) throw new NullPointerException("request");
                return BondedVesselProjectionValidationView.unavailable(request.bindingId());
            }

            @Override
            public CompletionStage<BondedVesselOperationResult> prepareTransition(
                    BondedVesselTransitionRequest request
            ) {
                if (request == null) throw new NullPointerException("request");
                return completedUnavailable();
            }

            @Override
            public CompletionStage<BondedVesselOperationResult> resumeTransition(
                    BondedVesselTransitionRequest request
            ) {
                if (request == null) throw new NullPointerException("request");
                return completedUnavailable();
            }

            @Override
            public BondedVesselOperationResult claimForApply(BondedVesselTransitionToken token) {
                if (token == null) throw new NullPointerException("token");
                return BondedVesselOperationResult.unavailable(REASON);
            }

            @Override
            public CompletionStage<BondedVesselOperationResult> commit(BondedVesselTransitionToken token) {
                if (token == null) throw new NullPointerException("token");
                return completedUnavailable();
            }

            @Override
            public CompletionStage<BondedVesselOperationResult> cancel(BondedVesselTransitionToken token) {
                if (token == null) throw new NullPointerException("token");
                return completedUnavailable();
            }

            @Override
            public CompletionStage<Optional<BondedVesselOperationView>> findOperation(
                    String callerNamespace,
                    String idempotencyKey
            ) {
                if (callerNamespace == null) throw new NullPointerException("callerNamespace");
                if (idempotencyKey == null) throw new NullPointerException("idempotencyKey");
                return CompletableFuture.completedFuture(Optional.empty());
            }

            private CompletionStage<BondedVesselOperationResult> completedUnavailable() {
                return CompletableFuture.completedFuture(BondedVesselOperationResult.unavailable(REASON));
            }
        };

        private UnavailableHolder() {
        }
    }
}
