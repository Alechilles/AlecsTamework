package com.alechilles.alecstamework.ownership;

import com.hypixel.hytale.logger.HytaleLogger;
import java.util.logging.Level;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Keeps owner-mutation cancellation, degradation, and callbacks terminal under failures. */
final class OwnerMutationTerminality {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final CompanionPopulationAdmissionCoordinator coordinator;

    OwnerMutationTerminality(@Nonnull CompanionPopulationAdmissionCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Nonnull
    CompletableFuture<Boolean> cancel(@Nonnull PreparedCompanionPopulationAdmission prepared,
                                      @Nonnull String reason) {
        try {
            CompletableFuture<Boolean> cancellation = coordinator.cancelAsync(prepared, reason);
            if (cancellation == null) {
                degrade("owner_mutation_cancel_stage_missing");
                return CompletableFuture.completedFuture(false);
            }
            cancellation.whenComplete((canceled, failure) -> {
                if (failure != null || !Boolean.TRUE.equals(canceled)) {
                    degrade("owner_mutation_cancel_failed");
                }
            });
            return cancellation;
        } catch (RuntimeException | LinkageError failure) {
            degrade("owner_mutation_cancel_failed");
            return CompletableFuture.completedFuture(false);
        }
    }

    void degrade(@Nonnull String reason) {
        try {
            coordinator.markReadinessDegraded(reason);
        } catch (RuntimeException | LinkageError ignored) {
            // An unresolved reservation remains conservative if diagnostics also fail.
        }
    }

    void degradeCapability(@Nonnull String reason) {
        try {
            coordinator.markCapabilityReadinessDegraded(reason);
        } catch (RuntimeException | LinkageError ignored) {
            // The APPLYING journal remains the conservative source of truth.
        }
    }

    void appliedContinuationFailed(@Nonnull Throwable failure) {
        LOGGER.at(Level.WARNING).withCause(failure).log(
                "Owner-mutation applied continuation failed; retaining recovery quarantine."
        );
        degradeCapability("owner_mutation_applied_continuation_failed");
    }

    void denied(@Nonnull OwnerMutationScheduler.MutationCallbacks callbacks,
                @Nonnull String reason,
                @Nullable OwnerPopulationDecision decision) {
        try {
            callbacks.onDenied(reason, decision);
        } catch (RuntimeException | LinkageError ignored) {
            // Caller feedback cannot change admission terminality.
        }
    }

    void durabilityDegraded(@Nonnull OwnerMutationScheduler.MutationCallbacks callbacks,
                            @Nonnull String reason) {
        try {
            callbacks.onDurabilityDegraded(reason);
        } catch (RuntimeException | LinkageError ignored) {
            // Accounting is already conservative; caller feedback is best-effort.
        }
    }
}
