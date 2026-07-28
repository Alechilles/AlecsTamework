package com.alechilles.alecstamework.items;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Orders validation, atomic durability, cleanup, item spend, and feedback. */
public final class BondedCompanionCaptureAuthor {
    private final BondedCompanionCaptureReplayGateway replays;
    private final Policy policy;
    private final Persistence persistence;
    private final Cleanup cleanup;
    private final BondedCompanionCaptureFeedbackDispatcher feedback;
    private final Diagnostics diagnostics;

    public BondedCompanionCaptureAuthor(
            @Nonnull Policy policy,
            @Nonnull Persistence persistence,
            @Nonnull Cleanup cleanup,
            @Nonnull BondedCompanionCaptureFeedbackDispatcher feedback
    ) {
        this(BondedCompanionCaptureReplayGateway.unavailable(), policy,
                persistence, cleanup, feedback, (intent, failure) -> {});
    }

    public BondedCompanionCaptureAuthor(
            @Nonnull Policy policy,
            @Nonnull Persistence persistence,
            @Nonnull Cleanup cleanup,
            @Nonnull BondedCompanionCaptureFeedbackDispatcher feedback,
            @Nonnull Diagnostics diagnostics
    ) {
        this(BondedCompanionCaptureReplayGateway.unavailable(), policy,
                persistence, cleanup, feedback, diagnostics);
    }

    /** Creates the production author with durable pre-policy replay evidence. */
    public BondedCompanionCaptureAuthor(
            @Nonnull BondedCompanionCaptureReplayGateway replays,
            @Nonnull Policy policy,
            @Nonnull Persistence persistence,
            @Nonnull Cleanup cleanup,
            @Nonnull BondedCompanionCaptureFeedbackDispatcher feedback,
            @Nonnull Diagnostics diagnostics
    ) {
        this.replays = Objects.requireNonNull(replays, "replays");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    /** Authors one capture without touching generic persistence or roster APIs. */
    @Nonnull
    public Result capture(@Nullable BondedCompanionCaptureIntent intent) {
        return capture(intent, null);
    }

    /** Authors and completes one attempt synchronously on its current world thread. */
    @Nonnull
    public Result capture(
            @Nullable BondedCompanionCaptureIntent intent,
            @Nullable BondedCompanionCaptureFeedbackDispatcher.CompletionContext
                    completion
    ) {
        BondedCompanionCaptureReplayGateway.ExactResult replay =
                safeReplayProbe(intent);
        if (replay.status()
                == BondedCompanionCaptureReplayGateway.ExactStatus.REPLAYED) {
            return replayed(intent, completion);
        }
        if (replay.status()
                != BondedCompanionCaptureReplayGateway.ExactStatus.ABSENT) {
            return rejected(intent, completion, Status.DATABASE_FAILED);
        }
        Status intrinsic = intrinsicDenial(intent);
        if (intrinsic != null) return rejected(intent, completion, intrinsic);
        PolicyCheck checked = safePolicy(intent);
        PolicyDecision decision = checked.decision();
        if (decision == PolicyDecision.REJECTED) {
            diagnostics.policyUnavailable(intent, checked.failure());
        }
        if (decision != PolicyDecision.ALLOWED) {
            return rejected(intent, completion, switch (decision) {
                case ROLE_REJECTED -> Status.ROLE_DENIED;
                case CAPACITY_REJECTED -> Status.CAPACITY_REJECTED;
                default -> Status.POLICY_UNAVAILABLE;
            });
        }
        PersistenceOutcome stored = safePersist(intent);
        if (stored == PersistenceOutcome.REPLAYED) {
            return replayed(intent, completion);
        }
        if (stored != PersistenceOutcome.APPLIED) {
            return rejected(intent, completion, Status.DATABASE_FAILED);
        }
        CleanupOutcome cleanupOutcome = safeCleanup(intent);
        var completed = feedback.success(intent, completion);
        Status status = switch (completed.status()) {
            case APPLIED -> Status.APPLIED;
            case EFFECT_FAILED -> Status.EFFECT_FAILED;
            case FINALIZATION_FAILED -> Status.FINALIZATION_FAILED;
        };
        return new Result(
                status, true, cleanupOutcome, completed.feedbackDelivered());
    }

    BondedCompanionCaptureReplayGateway.LookupResult lookupReplay(
            BondedCompanionCaptureReplayGateway.Request request
    ) {
        try {
            BondedCompanionCaptureReplayGateway.LookupResult value =
                    replays.lookup(request);
            return value == null
                    ? BondedCompanionCaptureReplayGateway.LookupResult.failed()
                    : value;
        } catch (RuntimeException failure) {
            return BondedCompanionCaptureReplayGateway.LookupResult.failed();
        }
    }

    /** Emits one terminal bonded-route rejection before an intent can be frozen. */
    @Nonnull
    public Result reject(
            @Nonnull Status status,
            @Nullable BondedCompanionCaptureFeedbackDispatcher.CompletionContext
                    completion
    ) {
        Objects.requireNonNull(status, "status");
        if (status == Status.POLICY_UNAVAILABLE) {
            diagnostics.policyUnavailable(null, null);
        }
        return rejected(null, completion, status);
    }

    @Nullable
    private Status intrinsicDenial(@Nullable BondedCompanionCaptureIntent intent) {
        if (intent == null || !intent.targetValid()) return Status.TARGET_INVALID;
        if (!intent.ownerAllowed()) return Status.OWNER_DENIED;
        if (!intent.roleAllowed()) return Status.ROLE_DENIED;
        if (!intent.chanceSuccessful()) return Status.CHANCE_FAILED;
        if (!intent.tranquilized()) return Status.TRANQUILIZED_REQUIRED;
        if (!intent.toolAccess()) return Status.TOOL_ACCESS_REQUIRED;
        return intent.snapshot() == null ? Status.SNAPSHOT_FAILED : null;
    }

    private PolicyCheck safePolicy(BondedCompanionCaptureIntent intent) {
        try {
            PolicyDecision value = policy.validate(intent);
            return new PolicyCheck(
                    value == null ? PolicyDecision.REJECTED : value,
                    null
            );
        } catch (RuntimeException failure) {
            return new PolicyCheck(PolicyDecision.REJECTED, failure);
        }
    }

    private BondedCompanionCaptureReplayGateway.ExactResult safeReplayProbe(
            BondedCompanionCaptureIntent intent
    ) {
        if (intent == null) {
            return BondedCompanionCaptureReplayGateway.ExactResult.absent();
        }
        try {
            BondedCompanionCaptureReplayGateway.ExactResult value =
                    replays.probeExact(intent);
            return value == null
                    ? BondedCompanionCaptureReplayGateway.ExactResult.failed()
                    : value;
        } catch (RuntimeException failure) {
            return BondedCompanionCaptureReplayGateway.ExactResult.failed();
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

    private Result replayed(
            BondedCompanionCaptureIntent intent,
            BondedCompanionCaptureFeedbackDispatcher.CompletionContext completion
    ) {
        CleanupOutcome cleanupOutcome = safeCleanup(intent);
        boolean delivered = feedback.failure(intent, completion, Status.REPLAYED);
        return new Result(
                Status.REPLAYED, true, cleanupOutcome, delivered);
    }

    private Result rejected(
            BondedCompanionCaptureIntent intent,
            BondedCompanionCaptureFeedbackDispatcher.CompletionContext completion,
            Status status
    ) {
        boolean delivered = feedback.failure(intent, completion, status);
        return new Result(status, false, null, delivered);
    }

    public enum Status {
        APPLIED, REPLAYED, TARGET_INVALID, CHANCE_FAILED,
        ADMISSION_DENIED, TRANQUILIZED_REQUIRED, TOOL_ACCESS_REQUIRED, OWNER_DENIED,
        ROLE_DENIED, CAPACITY_REJECTED, POLICY_UNAVAILABLE, SNAPSHOT_FAILED,
        DATABASE_FAILED, EFFECT_FAILED, FINALIZATION_FAILED
    }
    public enum PolicyDecision {
        ALLOWED, ROLE_REJECTED, CAPACITY_REJECTED, REJECTED
    }
    public enum PersistenceOutcome { APPLIED, REPLAYED, FAILED }
    public enum CleanupOutcome { REMOVED, ALREADY_MISSING, RETRY_PENDING }

    public record Result(@Nonnull Status status, boolean durable,
                         @Nullable CleanupOutcome cleanupOutcome,
                         boolean feedbackDelivered) {
        public Result { Objects.requireNonNull(status, "status"); }
    }
    private record PolicyCheck(
            PolicyDecision decision,
            @Nullable RuntimeException failure
    ) {}

    @FunctionalInterface public interface Policy {
        PolicyDecision validate(BondedCompanionCaptureIntent intent);
    }
    @FunctionalInterface public interface Persistence {
        PersistenceOutcome store(BondedCompanionCaptureIntent intent);
    }
    @FunctionalInterface public interface Cleanup {
        CleanupOutcome initiate(BondedCompanionCaptureIntent intent);
    }
    @FunctionalInterface public interface Diagnostics {
        void policyUnavailable(
                @Nullable BondedCompanionCaptureIntent intent,
                @Nullable RuntimeException failure
        );
    }

}
