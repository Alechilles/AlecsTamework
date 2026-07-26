package com.alechilles.alecstamework.items;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Orders validation, atomic durability, cleanup, item spend, and feedback. */
public final class BondedCompanionCaptureAuthor {
    private final Policy policy;
    private final Persistence persistence;
    private final Cleanup cleanup;
    private final BondedCompanionCaptureFeedbackDispatcher feedback;

    public BondedCompanionCaptureAuthor(
            @Nonnull Policy policy,
            @Nonnull Persistence persistence,
            @Nonnull Cleanup cleanup,
            @Nonnull BondedCompanionCaptureFeedbackDispatcher feedback
    ) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
    }

    /** Authors one capture without touching generic persistence or roster APIs. */
    @Nonnull
    public Result capture(@Nullable BondedCompanionCaptureIntent intent) {
        Status intrinsic = intrinsicDenial(intent);
        if (intrinsic != null) return rejected(intent, intrinsic);
        PolicyDecision decision = safePolicy(intent);
        if (decision != PolicyDecision.ALLOWED) {
            return rejected(intent, decision == PolicyDecision.CAPACITY_REJECTED
                    ? Status.CAPACITY_REJECTED : Status.ROLE_DENIED);
        }
        PersistenceOutcome stored = safePersist(intent);
        if (stored == PersistenceOutcome.REPLAYED) {
            return new Result(Status.REPLAYED, true, null);
        }
        if (stored != PersistenceOutcome.APPLIED) {
            return rejected(intent, Status.DATABASE_FAILED);
        }
        CleanupOutcome cleanupOutcome = safeCleanup(intent);
        feedback.success(intent);
        return new Result(Status.APPLIED, true, cleanupOutcome);
    }

    @Nullable
    private Status intrinsicDenial(@Nullable BondedCompanionCaptureIntent intent) {
        if (intent == null || !intent.targetValid()) return Status.TARGET_INVALID;
        if (!intent.chanceSuccessful()) return Status.CHANCE_FAILED;
        if (!intent.tranquilized()) return Status.TRANQUILIZED_REQUIRED;
        if (!intent.toolAccess()) return Status.TOOL_ACCESS_REQUIRED;
        if (!intent.ownerAllowed()) return Status.OWNER_DENIED;
        if (!intent.roleAllowed()) return Status.ROLE_DENIED;
        return intent.snapshot() == null ? Status.SNAPSHOT_FAILED : null;
    }

    private PolicyDecision safePolicy(BondedCompanionCaptureIntent intent) {
        try {
            PolicyDecision value = policy.validate(intent);
            return value == null ? PolicyDecision.REJECTED : value;
        } catch (RuntimeException failure) {
            return PolicyDecision.REJECTED;
        }
    }

    private PersistenceOutcome safePersist(BondedCompanionCaptureIntent intent) {
        try {
            PersistenceOutcome value = persistence.store(intent);
            return value == null ? PersistenceOutcome.FAILED : value;
        } catch (RuntimeException failure) {
            return PersistenceOutcome.FAILED;
        }
    }

    private CleanupOutcome safeCleanup(BondedCompanionCaptureIntent intent) {
        try {
            CleanupOutcome value = cleanup.initiate(intent);
            return value == null ? CleanupOutcome.RETRY_PENDING : value;
        } catch (RuntimeException failure) {
            return CleanupOutcome.RETRY_PENDING;
        }
    }

    private Result rejected(BondedCompanionCaptureIntent intent, Status status) {
        feedback.failure(intent, status);
        return new Result(status, false, null);
    }

    public enum Status {
        APPLIED, REPLAYED, TARGET_INVALID, CHANCE_FAILED,
        TRANQUILIZED_REQUIRED, TOOL_ACCESS_REQUIRED, OWNER_DENIED,
        ROLE_DENIED, CAPACITY_REJECTED, SNAPSHOT_FAILED, DATABASE_FAILED
    }
    public enum PolicyDecision { ALLOWED, CAPACITY_REJECTED, REJECTED }
    public enum PersistenceOutcome { APPLIED, REPLAYED, FAILED }
    public enum CleanupOutcome { REMOVED, ALREADY_MISSING, RETRY_PENDING }

    public record Result(@Nonnull Status status, boolean durable,
                         @Nullable CleanupOutcome cleanupOutcome) {
        public Result { Objects.requireNonNull(status, "status"); }
    }

    @FunctionalInterface public interface Policy {
        PolicyDecision validate(BondedCompanionCaptureIntent intent);
    }
    @FunctionalInterface public interface Persistence {
        PersistenceOutcome store(BondedCompanionCaptureIntent intent);
    }
    @FunctionalInterface public interface Cleanup {
        CleanupOutcome initiate(BondedCompanionCaptureIntent intent);
    }

}
