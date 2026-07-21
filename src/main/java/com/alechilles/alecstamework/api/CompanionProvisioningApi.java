package com.alechilles.alecstamework.api;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Generic idempotent creation and lifecycle authority for provisioned companions. */
public interface CompanionProvisioningApi {
    @Nonnull
    Optional<ProvisionedCompanionView> getByProfileId(@Nonnull String profileId);

    @Nonnull
    Optional<ProvisionedCompanionView> getByOrigin(@Nonnull String callerNamespace,
                                                   @Nonnull String idempotencyKey);

    @Nonnull
    CompletionStage<CompanionProvisioningResult> provision(@Nonnull CompanionProvisioningRequest request);

    @Nonnull
    CompletionStage<CompanionProvisioningResult> transition(
            @Nonnull ProvisionedCompanionTransitionRequest request
    );

    @Nonnull
    CompletionStage<Optional<CompanionProvisioningOperationView>> findOperation(
            @Nonnull String callerNamespace,
            @Nonnull String idempotencyKey
    );

    /** Compatibility fallback for implementations without provisioning authority. */
    static CompanionProvisioningApi unavailable() {
        return UnavailableHolder.INSTANCE;
    }

    final class UnavailableHolder {
        private static final String REASON = "companion-provisioning-authority-unavailable";
        private static final CompanionProvisioningApi INSTANCE = new CompanionProvisioningApi() {
            @Override
            public Optional<ProvisionedCompanionView> getByProfileId(String profileId) {
                if (profileId == null) throw new NullPointerException("profileId");
                return Optional.empty();
            }

            @Override
            public Optional<ProvisionedCompanionView> getByOrigin(String callerNamespace, String idempotencyKey) {
                if (callerNamespace == null) throw new NullPointerException("callerNamespace");
                if (idempotencyKey == null) throw new NullPointerException("idempotencyKey");
                return Optional.empty();
            }

            @Override
            public CompletionStage<CompanionProvisioningResult> provision(CompanionProvisioningRequest request) {
                if (request == null) throw new NullPointerException("request");
                return unavailableResult();
            }

            @Override
            public CompletionStage<CompanionProvisioningResult> transition(
                    ProvisionedCompanionTransitionRequest request
            ) {
                if (request == null) throw new NullPointerException("request");
                return unavailableResult();
            }

            @Override
            public CompletionStage<Optional<CompanionProvisioningOperationView>> findOperation(
                    String callerNamespace,
                    String idempotencyKey
            ) {
                if (callerNamespace == null) throw new NullPointerException("callerNamespace");
                if (idempotencyKey == null) throw new NullPointerException("idempotencyKey");
                return CompletableFuture.completedFuture(Optional.empty());
            }

            private CompletionStage<CompanionProvisioningResult> unavailableResult() {
                return CompletableFuture.completedFuture(CompanionProvisioningResult.unavailable(REASON));
            }
        };

        private UnavailableHolder() {
        }
    }
}
