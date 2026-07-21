package com.alechilles.alecstamework.vessels;

import com.alechilles.alecstamework.api.BondedVesselProjectionStatus;
import com.alechilles.alecstamework.api.BondedVesselState;
import com.alechilles.alecstamework.api.BondedVesselTransitionRequest;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Resolves and freezes the config/policy-dependent half of a transition before journaling. */
@FunctionalInterface
public interface BondedVesselTransitionPlanner {
    @Nonnull
    Plan plan(@Nonnull BondedVesselBindingRecord binding,
              @Nonnull BondedVesselTransitionRequest request,
              long nowMs);

    record Plan(@Nonnull BondedVesselState targetState,
                @Nonnull BondedVesselProjectionStatus targetProjectionStatus,
                @Nonnull String candidateItemId,
                @Nonnull String candidateItemFingerprint,
                long targetCooldownUntilMs,
                @Nonnull String policySnapshotJson) {
        public Plan {
            targetState = Objects.requireNonNull(targetState, "targetState");
            targetProjectionStatus = Objects.requireNonNull(
                    targetProjectionStatus, "targetProjectionStatus");
            candidateItemId = requireText(candidateItemId, "candidateItemId");
            candidateItemFingerprint = requireText(
                    candidateItemFingerprint, "candidateItemFingerprint");
            policySnapshotJson = requireText(policySnapshotJson, "policySnapshotJson");
        }

        private static String requireText(String value, String field) {
            String normalized = Objects.requireNonNull(value, field).trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(field + " is required.");
            }
            return normalized;
        }
    }
}
