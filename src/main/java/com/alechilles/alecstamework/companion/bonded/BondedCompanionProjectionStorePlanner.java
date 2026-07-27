package com.alechilles.alecstamework.companion.bonded;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Plans one revision-fenced durable mutation from disposable projection
 * evidence.
 *
 * <p>The planner is the sole authority that combines durable profile state
 * with optional live evidence. Projection orchestration and SQLite durability
 * consume its immutable result without independently reinterpreting policy or
 * snapshots.</p>
 */
public interface BondedCompanionProjectionStorePlanner {
    /** Resolves one complete snapshot, optional cooldown, and revision fence. */
    @Nonnull PlanningResult plan(@Nonnull PlanningRequest request);

    /** Inputs shared by store recovery and confirmed-death projection capture. */
    record PlanningRequest(
            @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease,
            @Nullable Long expectedRevision,
            long nowMs,
            @Nullable BondedCompanionSnapshot capturedSnapshot,
            @Nonnull Cause cause
    ) {
        public PlanningRequest {
            lease = Objects.requireNonNull(lease, "lease");
            cause = Objects.requireNonNull(cause, "cause");
            if (expectedRevision != null && expectedRevision < 0L) {
                throw new IllegalArgumentException("negative expectedRevision");
            }
            if (cause == Cause.EXPLICIT && expectedRevision == null) {
                throw new IllegalArgumentException(
                        "explicit store requires expectedRevision"
                );
            }
        }
    }

    /** Exact durable values that one atomic projection mutation must write. */
    record StorePlan(
            long expectedRevision,
            @Nonnull BondedCompanionSnapshot snapshot,
            long summonCooldownUntilMs
    ) {
        public StorePlan {
            if (expectedRevision < 0L) {
                throw new IllegalArgumentException("negative expectedRevision");
            }
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /** Explicit result prevents partial interpretation by orchestration code. */
    record PlanningResult(
            @Nonnull Status status,
            @Nullable StorePlan plan
    ) {
        public PlanningResult {
            status = Objects.requireNonNull(status, "status");
            if ((status == Status.PLANNED) != (plan != null)) {
                throw new IllegalArgumentException(
                        "only a planned result carries a store plan"
                );
            }
        }

        @Nonnull
        public static PlanningResult planned(@Nonnull StorePlan plan) {
            return new PlanningResult(Status.PLANNED,
                    Objects.requireNonNull(plan, "plan"));
        }

        @Nonnull
        public static PlanningResult rejected(@Nonnull Status status) {
            if (status == Status.PLANNED) {
                throw new IllegalArgumentException("planned status needs a plan");
            }
            return new PlanningResult(status, null);
        }
    }

    enum Cause { EXPLICIT, RECONCILIATION, CONFIRMED_DEATH }

    enum Status {
        PLANNED,
        PROFILE_NOT_FOUND,
        LEASE_MISMATCH,
        REVISION_CONFLICT,
        INVALID_STATE,
        POLICY_DENIED,
        SNAPSHOT_INVALID,
        SNAPSHOT_IDENTITY_MISMATCH,
        TIME_INVALID
    }
}
