package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureLiveBoundary;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseLiveBoundary;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLiveBoundary;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureLiveBoundary;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseLiveBoundary;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationLiveBoundary;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationLiveBoundary;
import com.alechilles.alecstamework.companion.revival.PaidRevivalBoundaries;
import com.alechilles.alecstamework.npc.actions.BreedingLitterLiveBoundary;
import com.alechilles.alecstamework.npc.actions.BreedingLitterLiveResult;
import javax.annotation.Nonnull;

/** Complete external mutation/resolution boundaries used by normal work and recovery. */
public record PublicPersistenceLiveBoundaries(
        @Nonnull CompanionCaptureLiveBoundary captures,
        @Nonnull CompanionCaptureReleaseLiveBoundary capturedReleases,
        @Nonnull CompanionRestorationLiveBoundary restorations,
        @Nonnull CompanionCoopCaptureLiveBoundary coopCaptures,
        @Nonnull CompanionCoopReleaseLiveBoundary coopReleases,
        @Nonnull TimedSummonLiveBoundary timedSummons,
        @Nonnull ProvisioningActivationLiveBoundary provisioningActivations,
        @Nonnull PaidRevivalBoundaries paidRevivals,
        @Nonnull BreedingLitterLiveBoundary breedingLitters
) {
    public PublicPersistenceLiveBoundaries {
        if (captures == null || capturedReleases == null
                || restorations == null
                || coopCaptures == null || coopReleases == null
                || timedSummons == null
                || provisioningActivations == null
                || paidRevivals == null
                || breedingLitters == null) {
            throw new IllegalArgumentException(
                    "Every public live persistence boundary is required"
            );
        }
    }

    /** Compatibility composition for callers without breeding-litter work. */
    public PublicPersistenceLiveBoundaries(
            CompanionCaptureLiveBoundary captures,
            CompanionCaptureReleaseLiveBoundary capturedReleases,
            CompanionRestorationLiveBoundary restorations,
            CompanionCoopCaptureLiveBoundary coopCaptures,
            CompanionCoopReleaseLiveBoundary coopReleases,
            TimedSummonLiveBoundary timedSummons,
            ProvisioningActivationLiveBoundary provisioningActivations,
            PaidRevivalBoundaries paidRevivals
    ) {
        this(
                captures,
                capturedReleases,
                restorations,
                coopCaptures,
                coopReleases,
                timedSummons,
                provisioningActivations,
                paidRevivals,
                unavailableBreedingLitters()
        );
    }

    /** Compatibility composition for callers without paid revival. */
    public PublicPersistenceLiveBoundaries(
            CompanionCaptureLiveBoundary captures,
            CompanionCaptureReleaseLiveBoundary capturedReleases,
            CompanionRestorationLiveBoundary restorations,
            CompanionCoopCaptureLiveBoundary coopCaptures,
            CompanionCoopReleaseLiveBoundary coopReleases,
            TimedSummonLiveBoundary timedSummons,
            ProvisioningActivationLiveBoundary provisioningActivations
    ) {
        this(
                captures,
                capturedReleases,
                restorations,
                coopCaptures,
                coopReleases,
                timedSummons,
                provisioningActivations,
                PaidRevivalBoundaries.unavailable(),
                unavailableBreedingLitters()
        );
    }

    /** Compatibility composition for callers without provisioning activation. */
    public PublicPersistenceLiveBoundaries(
            CompanionCaptureLiveBoundary captures,
            CompanionCaptureReleaseLiveBoundary capturedReleases,
            CompanionRestorationLiveBoundary restorations,
            CompanionCoopCaptureLiveBoundary coopCaptures,
            CompanionCoopReleaseLiveBoundary coopReleases,
            TimedSummonLiveBoundary timedSummons
    ) {
        this(
                captures,
                capturedReleases,
                restorations,
                coopCaptures,
                coopReleases,
                timedSummons,
                (request, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.retryable(
                                        "provisioning_boundary_unavailable",
                                        null
                                ).completed(),
                PaidRevivalBoundaries.unavailable(),
                unavailableBreedingLitters()
        );
    }

    /**
     * Compatibility composition for callers that do not yet expose timed world work.
     *
     * <p>The missing boundary fails closed and preserves the prepared operation for retry.</p>
     */
    public PublicPersistenceLiveBoundaries(
            CompanionCaptureLiveBoundary captures,
            CompanionCaptureReleaseLiveBoundary capturedReleases,
            CompanionRestorationLiveBoundary restorations,
            CompanionCoopCaptureLiveBoundary coopCaptures,
            CompanionCoopReleaseLiveBoundary coopReleases
    ) {
        this(
                captures,
                capturedReleases,
                restorations,
                coopCaptures,
                coopReleases,
                (request, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.retryable(
                                        "timed_summon_boundary_unavailable",
                                        null
                                ).completed(),
                (request, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.retryable(
                                        "provisioning_boundary_unavailable",
                                        null
                                ).completed(),
                PaidRevivalBoundaries.unavailable(),
                unavailableBreedingLitters()
        );
    }

    private static BreedingLitterLiveBoundary unavailableBreedingLitters() {
        return (litter, operation) -> BreedingLitterLiveResult.retryable(
                "breeding_litter_boundary_unavailable",
                null
        ).completed();
    }
}
