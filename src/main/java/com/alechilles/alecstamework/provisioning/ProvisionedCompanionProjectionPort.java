package com.alechilles.alecstamework.provisioning;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Role-to-NPC world projection seam for provisioning. A production implementation must use the
 * normal planned-spawn owner/group/claim admission path and must not publish ACTIVE before the
 * world projection and canonical population commit have both succeeded.
 */
public interface ProvisionedCompanionProjectionPort {
    boolean available();

    @Nonnull
    CompletionStage<ProvisioningPopulationBackend.AdmissionPreparation> prepare(
            @Nonnull ProvisioningPopulationBackend.ActiveRequest request);

    /**
     * Reconstructs a process-local projection capability from the durable provisioning request.
     *
     * <p>The returned operation id must equal {@code previousPopulationOperationId}. A production
     * implementation may reacquire a new lower-level population reservation after startup
     * reconciliation, but it must keep that replacement hidden beneath the original durable
     * provisioning identity. If the deterministic projection is already live, recovery must
     * expose an idempotent capability for that exact identity instead of spawning again.</p>
     */
    @Nonnull
    default CompletionStage<ProvisioningPopulationBackend.AdmissionPreparation> resume(
            @Nonnull ProvisioningPopulationBackend.ActiveRequest request,
            @Nonnull UUID previousPopulationOperationId) {
        java.util.Objects.requireNonNull(previousPopulationOperationId,
                "previousPopulationOperationId");
        return prepare(java.util.Objects.requireNonNull(request, "request"));
    }

    @Nonnull
    ProvisioningPopulationBackend.ClaimResult claim(@Nonnull UUID populationOperationId);

    @Nonnull
    CompletionStage<ProvisioningPopulationBackend.ProfileSnapshot> commit(
            @Nonnull UUID populationOperationId);

    @Nonnull
    CompletionStage<Void> cancel(@Nonnull UUID populationOperationId, @Nonnull String reason);

    @Nonnull
    CompletionStage<ProvisioningPopulationBackend.TransitionOutcome> transition(
            @Nonnull ProvisioningPopulationBackend.TransitionRequest request);

    @Nonnull
    static ProvisionedCompanionProjectionPort unavailable() {
        return Unavailable.INSTANCE;
    }

    enum Unavailable implements ProvisionedCompanionProjectionPort {
        INSTANCE;

        @Override public boolean available() { return false; }

        @Override
        public CompletionStage<ProvisioningPopulationBackend.AdmissionPreparation> prepare(
                ProvisioningPopulationBackend.ActiveRequest request) {
            return CompletableFuture.completedFuture(new ProvisioningPopulationBackend.AdmissionPreparation(
                    ProvisioningPopulationBackend.AdmissionPreparation.Status.UNAVAILABLE,
                    "provisioned-companion-projection-unavailable", null, null));
        }

        @Override
        public CompletionStage<ProvisioningPopulationBackend.AdmissionPreparation> resume(
                ProvisioningPopulationBackend.ActiveRequest request,
                UUID previousPopulationOperationId) {
            return CompletableFuture.completedFuture(new ProvisioningPopulationBackend.AdmissionPreparation(
                    ProvisioningPopulationBackend.AdmissionPreparation.Status.UNAVAILABLE,
                    "provisioned-companion-projection-unavailable", null, null));
        }

        @Override
        public ProvisioningPopulationBackend.ClaimResult claim(UUID populationOperationId) {
            return new ProvisioningPopulationBackend.ClaimResult(
                    false, "provisioned-companion-projection-unavailable", null);
        }

        @Override
        public CompletionStage<ProvisioningPopulationBackend.ProfileSnapshot> commit(
                UUID populationOperationId) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "provisioned-companion-projection-unavailable"));
        }

        @Override
        public CompletionStage<Void> cancel(UUID populationOperationId, String reason) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<ProvisioningPopulationBackend.TransitionOutcome> transition(
                ProvisioningPopulationBackend.TransitionRequest request) {
            return CompletableFuture.completedFuture(new ProvisioningPopulationBackend.TransitionOutcome(
                    ProvisioningPopulationBackend.TransitionOutcome.Status.UNAVAILABLE,
                    "provisioned-companion-projection-unavailable", null, null));
        }
    }
}
