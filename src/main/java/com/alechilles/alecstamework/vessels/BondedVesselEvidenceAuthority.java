package com.alechilles.alecstamework.vessels;

import com.alechilles.alecstamework.api.BondedVesselProjectionValidationRequest;
import com.alechilles.alecstamework.api.BondedVesselProjectionValidationView;
import com.alechilles.alecstamework.api.BondedVesselSourceItemEvidence;
import com.alechilles.alecstamework.api.BondedVesselHeldItemLocatorRequest;
import com.alechilles.alecstamework.api.BondedVesselTransitionContext;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselOperationRecord;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Tamework-owned exact inventory/world evidence and source-CAS boundary. */
public interface BondedVesselEvidenceAuthority {
    /**
     * Re-reads one exact actor-held slot and extracts Tamework's bonded-vessel item metadata.
     * Implementations must not infer identity from location or caller-supplied persistence data.
     */
    @Nonnull
    default CompletionStage<HeldItemObservation> resolveHeldItem(
            @Nonnull UUID actorUuid,
            @Nonnull BondedVesselSourceItemEvidence expected
    ) {
        Objects.requireNonNull(actorUuid, "actorUuid");
        return CompletableFuture.completedFuture(HeldItemObservation.unavailable(expected));
    }

    /** Tamework-owned locator path that creates exact revision/fingerprint evidence. */
    @Nonnull
    default CompletionStage<HeldItemObservation> locateHeldItem(
            @Nonnull BondedVesselHeldItemLocatorRequest locator) {
        Objects.requireNonNull(locator, "locator");
        BondedVesselSourceItemEvidence placeholder = new BondedVesselSourceItemEvidence(
                locator.expectedItemId() == null ? "unknown" : locator.expectedItemId(),
                locator.holderEvidenceId(), locator.containerPath(), locator.inventorySlot(),
                0L, "unavailable");
        return CompletableFuture.completedFuture(HeldItemObservation.unavailable(placeholder));
    }

    @Nonnull
    CompletionStage<SourceObservation> observe(@Nonnull BondedVesselTransitionContext expected);

    @Nonnull
    CompletionStage<SourceFinalization> finalizeSource(
            @Nonnull BondedVesselOperationRecord operation,
            @Nonnull BondedVesselTransitionContext expected
    );

    @Nonnull
    BondedVesselProjectionValidationView validateProjection(
            @Nonnull BondedVesselBindingRecord binding,
            @Nonnull BondedVesselProjectionValidationRequest request
    );

    record SourceObservation(@Nonnull Status status,
                             @Nonnull String reason,
                             @Nonnull String holderEvidenceId,
                             @Nonnull String containerPath,
                             int inventorySlot,
                             long inventoryRevision,
                             @Nonnull String itemId,
                             @Nonnull String itemFingerprint) {
        public SourceObservation {
            status = Objects.requireNonNull(status, "status");
            reason = requireText(reason, "reason");
            holderEvidenceId = requireText(holderEvidenceId, "holderEvidenceId");
            containerPath = requireText(containerPath, "containerPath");
            itemId = requireText(itemId, "itemId");
            itemFingerprint = requireText(itemFingerprint, "itemFingerprint");
            if (inventorySlot < 0 || inventoryRevision < 0L) {
                throw new IllegalArgumentException("Observed slot and revision cannot be negative.");
            }
        }

        public boolean exactlyMatches(BondedVesselTransitionContext expected) {
            return status == Status.EXACT
                    && holderEvidenceId.equals(expected.sourceHolderEvidenceId())
                    && containerPath.equals(expected.sourceContainerPath())
                    && inventorySlot == expected.sourceInventorySlot()
                    && inventoryRevision == expected.sourceInventoryRevision()
                    && itemId.equals(expected.sourceItemId())
                    && itemFingerprint.equals(expected.sourceItemFingerprint());
        }
    }

    enum Status {
        EXACT,
        CHANGED,
        INCOMPLETE,
        UNAVAILABLE
    }

    record HeldItemObservation(@Nonnull HeldItemStatus status,
                               @Nonnull String reason,
                               @Nonnull BondedVesselSourceItemEvidence observedEvidence,
                               @Nullable UUID bindingId,
                               @Nullable String profileId,
                               long generation) {
        public static final long UNKNOWN_GENERATION = -1L;

        public HeldItemObservation {
            status = Objects.requireNonNull(status, "status");
            reason = requireText(reason, "reason");
            observedEvidence = Objects.requireNonNull(observedEvidence, "observedEvidence");
            if (profileId != null) {
                profileId = requireText(profileId, "profileId");
            }
            if (generation < UNKNOWN_GENERATION) {
                throw new IllegalArgumentException("generation cannot be less than -1.");
            }
            if (status == HeldItemStatus.EXACT
                    && (bindingId == null || profileId == null || generation < 0L)) {
                throw new IllegalArgumentException(
                        "EXACT held-item evidence requires binding, profile, and generation metadata.");
            }
        }

        public boolean exactlyMatches(BondedVesselSourceItemEvidence expected) {
            return status == HeldItemStatus.EXACT
                    && observedEvidence.equals(Objects.requireNonNull(expected, "expected"));
        }

        public static HeldItemObservation unavailable(BondedVesselSourceItemEvidence expected) {
            return new HeldItemObservation(
                    HeldItemStatus.UNAVAILABLE,
                    "held-item-evidence-unavailable",
                    Objects.requireNonNull(expected, "expected"),
                    null,
                    null,
                    UNKNOWN_GENERATION);
        }
    }

    enum HeldItemStatus {
        EXACT,
        NOT_FOUND,
        NOT_BONDED,
        CHANGED,
        AMBIGUOUS,
        UNAVAILABLE
    }

    record SourceFinalization(@Nonnull FinalizationStatus status,
                              @Nonnull String reason,
                              @Nonnull String replacementFingerprint,
                              @Nonnull String itemEvidenceJson) {
        public SourceFinalization {
            status = Objects.requireNonNull(status, "status");
            reason = requireText(reason, "reason");
            replacementFingerprint = requireText(
                    replacementFingerprint, "replacementFingerprint");
            itemEvidenceJson = requireText(itemEvidenceJson, "itemEvidenceJson");
        }
    }

    enum FinalizationStatus {
        FINALIZED,
        ALREADY_FINALIZED,
        SOURCE_CHANGED,
        INDETERMINATE
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }
}
