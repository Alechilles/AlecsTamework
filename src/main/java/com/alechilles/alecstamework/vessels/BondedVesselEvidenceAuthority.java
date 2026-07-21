package com.alechilles.alecstamework.vessels;

import com.alechilles.alecstamework.api.BondedVesselProjectionValidationRequest;
import com.alechilles.alecstamework.api.BondedVesselProjectionValidationView;
import com.alechilles.alecstamework.api.BondedVesselTransitionContext;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselOperationRecord;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Tamework-owned exact inventory/world evidence and source-CAS boundary. */
public interface BondedVesselEvidenceAuthority {
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
