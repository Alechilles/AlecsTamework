package com.alechilles.alecstamework.vessels;

import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselOperationRecord;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Tamework-owned profile, population, and world mutation boundary. */
public interface BondedVesselMutationAuthority {
    @Nonnull
    CompletionStage<ApplyOutcome> apply(
            @Nonnull BondedVesselOperationRecord operation,
            @Nonnull BondedVesselBindingRecord binding,
            boolean recovery
    );

    record ApplyOutcome(@Nonnull Status status,
                        @Nonnull String reason,
                        long committedProfileRevision,
                        @Nullable UUID activeNpcUuid,
                        @Nullable BondedVesselBindingRecord.PhysicalLocation activeLocation,
                        @Nonnull String itemEvidenceJson) {
        public ApplyOutcome {
            status = Objects.requireNonNull(status, "status");
            reason = requireText(reason, "reason");
            itemEvidenceJson = requireText(itemEvidenceJson, "itemEvidenceJson");
            if (committedProfileRevision < 0L) {
                throw new IllegalArgumentException("committedProfileRevision cannot be negative.");
            }
        }
    }

    enum Status {
        APPLIED,
        ALREADY_APPLIED,
        TERMINAL_DENIED,
        INDETERMINATE,
        QUARANTINED
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }
}
