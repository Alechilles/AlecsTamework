package com.alechilles.alecstamework.vessels;

import com.alechilles.alecstamework.api.BondedVesselDurableOperationStatus;
import com.alechilles.alecstamework.api.BondedVesselOperationResult;
import com.alechilles.alecstamework.api.BondedVesselOperationView;
import com.alechilles.alecstamework.api.BondedVesselProjectionStatus;
import com.alechilles.alecstamework.api.BondedVesselProjectionValidationRequest;
import com.alechilles.alecstamework.api.BondedVesselProjectionValidationStatus;
import com.alechilles.alecstamework.api.BondedVesselProjectionValidationView;
import com.alechilles.alecstamework.api.BondedVesselReadinessView;
import com.alechilles.alecstamework.api.BondedVesselState;
import com.alechilles.alecstamework.api.BondedVesselStateChangedEvent;
import com.alechilles.alecstamework.api.BondedVesselTransition;
import com.alechilles.alecstamework.api.BondedVesselTransitionContext;
import com.alechilles.alecstamework.api.BondedVesselTransitionRequest;
import com.alechilles.alecstamework.api.BondedVesselTransitionToken;
import com.alechilles.alecstamework.api.BondedVesselView;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselRepository;
import java.security.SecureRandom;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Restart-safe bonded-vessel state machine. Durable identity is caller namespace plus idempotency
 * key; process-local tokens authorize only the current runtime's apply continuation.
 */
public final class BondedVesselCoordinator {
    private static final String UNAVAILABLE = "bonded-vessel-authority-unavailable";
    private static final int DEFAULT_RECOVERY_LIMIT = 128;
    private static final long DEFAULT_TOKEN_LIFETIME_MS = 30_000L;

    private final BondedVesselJournal journal;
    private final BondedVesselTransitionPlanner planner;
    private final BondedVesselEvidenceAuthority evidenceAuthority;
    private final BondedVesselMutationAuthority mutationAuthority;
    private final BondedVesselEventSink eventSink;
    private final Executor executor;
    private final LongSupplier wallClockMs;
    private final BondedVesselTokenVault tokenVault;
    private final BondedVesselSourceContextCodec sourceContextCodec = new BondedVesselSourceContextCodec();
    private final int recoveryLimit;
    private final AtomicReference<BondedVesselReadinessView> readiness = new AtomicReference<>(
            new BondedVesselReadinessView(
                    BondedVesselReadinessView.Readiness.RECOVERING,
                    "bonded-vessel-recovery-not-started",
                    0L,
                    0L,
                    0L,
                    0L));

    public BondedVesselCoordinator(@Nonnull BondedVesselJournal journal,
                                   @Nonnull BondedVesselTransitionPlanner planner,
                                   @Nonnull BondedVesselEvidenceAuthority evidenceAuthority,
                                   @Nonnull BondedVesselMutationAuthority mutationAuthority,
                                   @Nullable BondedVesselEventSink eventSink,
                                   @Nonnull Executor executor,
                                   @Nonnull LongSupplier wallClockMs,
                                   @Nonnull LongSupplier monotonicNanos,
                                   long tokenLifetimeMs,
                                   int recoveryLimit) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.evidenceAuthority = Objects.requireNonNull(evidenceAuthority, "evidenceAuthority");
        this.mutationAuthority = Objects.requireNonNull(mutationAuthority, "mutationAuthority");
        this.eventSink = eventSink == null ? BondedVesselEventSink.NO_OP : eventSink;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.wallClockMs = Objects.requireNonNull(wallClockMs, "wallClockMs");
        if (tokenLifetimeMs <= 0L) {
            throw new IllegalArgumentException("tokenLifetimeMs must be positive.");
        }
        if (recoveryLimit <= 0) {
            throw new IllegalArgumentException("recoveryLimit must be positive.");
        }
        this.recoveryLimit = recoveryLimit;
        this.tokenVault = new BondedVesselTokenVault(
                new SecureRandom(),
                Objects.requireNonNull(monotonicNanos, "monotonicNanos"),
                TimeUnit.MILLISECONDS.toNanos(tokenLifetimeMs));
    }

    public BondedVesselCoordinator(@Nonnull BondedVesselJournal journal,
                                   @Nonnull BondedVesselTransitionPlanner planner,
                                   @Nonnull BondedVesselEvidenceAuthority evidenceAuthority,
                                   @Nonnull BondedVesselMutationAuthority mutationAuthority,
                                   @Nullable BondedVesselEventSink eventSink,
                                   @Nonnull Executor executor) {
        this(journal, planner, evidenceAuthority, mutationAuthority, eventSink, executor,
                System::currentTimeMillis, System::nanoTime,
                DEFAULT_TOKEN_LIFETIME_MS, DEFAULT_RECOVERY_LIMIT);
    }

    @Nonnull
    public Optional<BondedVesselView> getByBindingId(@Nonnull UUID bindingId) {
        Objects.requireNonNull(bindingId, "bindingId");
        try {
            return Optional.ofNullable(journal.findBinding(bindingId.toString())).map(this::toView);
        } catch (Exception exception) {
            markUnavailable("bonded-vessel-binding-read-failed");
            return Optional.empty();
        }
    }

    @Nonnull
    public Optional<BondedVesselView> getByProfileId(@Nonnull String profileId) {
        String normalized = requireText(profileId, "profileId");
        try {
            return Optional.ofNullable(journal.findBindingByProfile(normalized)).map(this::toView);
        } catch (Exception exception) {
            markUnavailable("bonded-vessel-profile-read-failed");
            return Optional.empty();
        }
    }

    @Nonnull
    public BondedVesselReadinessView readiness() {
        return readiness.get();
    }

    @Nonnull
    public BondedVesselProjectionValidationView validateProjection(
            @Nonnull BondedVesselProjectionValidationRequest request
    ) {
        Objects.requireNonNull(request, "request");
        try {
            BondedVesselBindingRecord binding = journal.findBinding(request.bindingId().toString());
            if (binding == null) {
                return new BondedVesselProjectionValidationView(
                        request.bindingId(), BondedVesselProjectionValidationStatus.UNKNOWN,
                        "binding-not-found", BondedVesselProjectionValidationView.UNKNOWN_GENERATION,
                        false);
            }
            if (request.generation() != binding.generation()) {
                return new BondedVesselProjectionValidationView(
                        request.bindingId(), BondedVesselProjectionValidationStatus.STALE_GENERATION,
                        "stale-generation", binding.generation(), true);
            }
            return evidenceAuthority.validateProjection(binding, request);
        } catch (Exception exception) {
            markUnavailable("bonded-vessel-projection-read-failed");
            return BondedVesselProjectionValidationView.unavailable(request.bindingId());
        }
    }

    @Nonnull
    public CompletionStage<BondedVesselOperationResult> prepareTransition(
            @Nonnull BondedVesselTransitionRequest request
    ) {
        Objects.requireNonNull(request, "request");
        return supplyAsync(() -> journal.findOperationByOrigin(
                        request.callerNamespace(), request.idempotencyKey()))
                .thenCompose(existing -> existing == null
                        ? prepareNew(request)
                        : resumeExisting(request, existing))
                .exceptionally(this::unavailableResult);
    }

    @Nonnull
    public CompletionStage<BondedVesselOperationResult> resumeTransition(
            @Nonnull BondedVesselTransitionRequest request
    ) {
        Objects.requireNonNull(request, "request");
        return supplyAsync(() -> journal.findOperationByOrigin(
                        request.callerNamespace(), request.idempotencyKey()))
                .thenCompose(existing -> existing == null
                        ? completed(BondedVesselOperationResult.unavailable(
                                "bonded-vessel-operation-not-found-indeterminate"))
                        : resumeExisting(request, existing))
                .exceptionally(this::unavailableResult);
    }

    @Nonnull
    public BondedVesselOperationResult claimForApply(@Nonnull BondedVesselTransitionToken token) {
        Objects.requireNonNull(token, "token");
        BondedVesselTokenVault.Entry entry = tokenVault.claim(token);
        if (entry == null) {
            return denied("invalid-or-expired-process-local-token");
        }
        return openResult(
                BondedVesselOperationResult.Status.APPLYING,
                "process-local-apply-capability-claimed",
                null,
                entry.token());
    }

    @Nonnull
    public CompletionStage<BondedVesselOperationResult> commit(
            @Nonnull BondedVesselTransitionToken token
    ) {
        Objects.requireNonNull(token, "token");
        BondedVesselTokenVault.Entry entry = tokenVault.authenticate(token);
        if (entry == null || !entry.claimed()) {
            return completed(denied(entry == null
                    ? "invalid-or-expired-process-local-token"
                    : "token-not-claimed-for-apply"));
        }
        return supplyAsync(() -> journal.findOperation(token.operationId().toString()))
                .thenCompose(operation -> continueCommit(entry, operation, false))
                .exceptionally(this::unavailableResult);
    }

    @Nonnull
    public CompletionStage<BondedVesselOperationResult> cancel(
            @Nonnull BondedVesselTransitionToken token
    ) {
        Objects.requireNonNull(token, "token");
        BondedVesselTokenVault.Entry entry = tokenVault.authenticate(token);
        if (entry == null) {
            return completed(denied("invalid-or-expired-process-local-token"));
        }
        return supplyAsync(() -> journal.findOperation(token.operationId().toString()))
                .thenCompose(operation -> {
                    if (operation == null) {
                        return completed(BondedVesselOperationResult.unavailable(
                                "bonded-vessel-operation-not-found-indeterminate"));
                    }
                    if (operation.state() == BondedVesselOperationRecord.State.CANCELED) {
                        tokenVault.revoke(token);
                        return completed(closedResult(
                                BondedVesselOperationResult.Status.CANCELED,
                                reason(operation, "operation-canceled"), operation));
                    }
                    if (operation.state() != BondedVesselOperationRecord.State.PREPARED) {
                        return completed(openOrClosedResult(operation, token));
                    }
                    return journal.cancel(operation.operationId(), "caller-canceled", wallClockMs.getAsLong())
                            .thenApply(result -> {
                                if (result.status() == BondedVesselRepository.Status.CANCELED
                                        || result.status() == BondedVesselRepository.Status.IDEMPOTENT) {
                                    tokenVault.revokeOperation(token.operationId());
                                    return closedResult(
                                            BondedVesselOperationResult.Status.CANCELED,
                                            result.reason() == null ? "caller-canceled" : result.reason(),
                                            requireOperation(result, operation));
                                }
                                return fromMutation(result, token);
                            });
                })
                .exceptionally(this::unavailableResult);
    }

    @Nonnull
    public CompletionStage<Optional<BondedVesselOperationView>> findOperation(
            @Nonnull String callerNamespace,
            @Nonnull String idempotencyKey
    ) {
        String namespace = requireText(callerNamespace, "callerNamespace");
        String key = requireText(idempotencyKey, "idempotencyKey");
        return supplyAsync(() -> Optional.ofNullable(
                        journal.findOperationByOrigin(namespace, key)).map(this::toOperationView))
                .exceptionally(failure -> {
                    markUnavailable("bonded-vessel-operation-read-failed");
                    return Optional.empty();
                });
    }

    /**
     * Runs one bounded recovery pass. PREPARED rows remain resumable; APPLYING/APPLIED rows are
     * continued only from their frozen journal evidence.
     */
    @Nonnull
    public CompletionStage<RecoveryReport> recoverPending() {
        long startedAt = wallClockMs.getAsLong();
        readiness.set(new BondedVesselReadinessView(
                BondedVesselReadinessView.Readiness.RECOVERING,
                "bonded-vessel-recovery-running", 0L, 0L, 0L, startedAt));
        return supplyAsync(() -> journal.loadRecoverable(recoveryLimit))
                .thenCompose(operations -> recoverSequentially(
                        operations, 0, new RecoveryAccumulator(operations.size())))
                .thenApply(accumulator -> {
                    long now = wallClockMs.getAsLong();
                    BondedVesselReadinessView.Readiness state = accumulator.quarantined > 0
                            ? BondedVesselReadinessView.Readiness.DEGRADED
                            : BondedVesselReadinessView.Readiness.READY;
                    String reason = accumulator.quarantined > 0
                            ? "bonded-vessel-recovery-quarantined"
                            : "bonded-vessel-recovery-complete";
                    readiness.set(new BondedVesselReadinessView(
                            state, reason, 0L, accumulator.pending, accumulator.quarantined, now));
                    return accumulator.report();
                })
                .exceptionally(failure -> {
                    markUnavailable("bonded-vessel-recovery-failed");
                    return new RecoveryReport(0, 0, 0, 0, 1);
                });
    }

    @Nonnull
    private CompletionStage<BondedVesselOperationResult> prepareNew(
            BondedVesselTransitionRequest request
    ) {
        return supplyAsync(() -> {
            BondedVesselBindingRecord binding = journal.findBinding(request.bindingId().toString());
            String denial = validateRequest(binding, request);
            if (denial != null) {
                return Preparation.denied(denial);
            }
            BondedVesselTransitionPlanner.Plan plan = planner.plan(binding, request, wallClockMs.getAsLong());
            denial = validatePlan(binding, request, plan);
            if (denial != null) {
                return Preparation.denied(denial);
            }
            return Preparation.accepted(binding, plan);
        }).thenCompose(preparation -> {
            if (preparation.denial != null) {
                return completed(denied(preparation.denial));
            }
            return evidenceAuthority.observe(request.context()).thenCompose(observation -> {
                if (!observation.exactlyMatches(request.context())) {
                    return completed(denied(evidenceReason(observation)));
                }
                long now = wallClockMs.getAsLong();
                BondedVesselOperationRecord operation = createOperation(
                        preparation.binding, request, preparation.plan, now);
                return journal.prepare(operation).thenApply(result -> {
                    BondedVesselOperationRecord durable = requireOperation(result, operation);
                    if (result.status() == BondedVesselRepository.Status.PREPARED
                            || result.status() == BondedVesselRepository.Status.IDEMPOTENT) {
                        BondedVesselTransitionToken token = tokenVault.issue(durable, request.context());
                        return openResult(BondedVesselOperationResult.Status.RESERVED,
                                result.reason() == null ? "transition-prepared" : result.reason(),
                                result.binding(), token);
                    }
                    return fromMutation(result, null);
                });
            });
        });
    }

    @Nonnull
    private CompletionStage<BondedVesselOperationResult> resumeExisting(
            BondedVesselTransitionRequest request,
            BondedVesselOperationRecord operation
    ) {
        if (!requestMatchesOperation(request, operation)) {
            return completed(denied("idempotency-origin-request-mismatch"));
        }
        if (operation.state().isTerminal()
                || operation.state() == BondedVesselOperationRecord.State.QUARANTINED) {
            return completed(openOrClosedResult(operation, null));
        }
        return supplyAsync(() -> journal.findBinding(operation.bindingId())).thenCompose(binding -> {
            if (binding == null || !binding.ownerUuid().equals(request.actorUuid())) {
                return completed(denied("owner-or-binding-changed"));
            }
            return evidenceAuthority.observe(request.context()).thenApply(observation -> {
                if (!observation.exactlyMatches(request.context())) {
                    return observation.status() == BondedVesselEvidenceAuthority.Status.INCOMPLETE
                            || observation.status() == BondedVesselEvidenceAuthority.Status.UNAVAILABLE
                            ? BondedVesselOperationResult.unavailable(evidenceReason(observation))
                            : denied(evidenceReason(observation));
                }
                BondedVesselTransitionToken token = tokenVault.issue(operation, request.context());
                BondedVesselOperationResult.Status status = switch (operation.state()) {
                    case PREPARED -> BondedVesselOperationResult.Status.RESERVED;
                    case APPLYING -> BondedVesselOperationResult.Status.APPLYING;
                    case APPLIED -> BondedVesselOperationResult.Status.APPLIED;
                    default -> throw new IllegalStateException(
                            "Unsupported resumable operation state: " + operation.state());
                };
                return openResult(status, "transition-resumed", binding, token);
            });
        });
    }

    @Nonnull
    private CompletionStage<BondedVesselOperationResult> continueCommit(
            BondedVesselTokenVault.Entry entry,
            @Nullable BondedVesselOperationRecord operation,
            boolean recovery
    ) {
        if (operation == null) {
            return completed(BondedVesselOperationResult.unavailable(
                    "bonded-vessel-operation-not-found-indeterminate"));
        }
        if (!tokenMatchesOperation(entry.token(), operation)) {
            return completed(denied("token-operation-mismatch"));
        }
        if (operation.state() == BondedVesselOperationRecord.State.COMMITTED
                || operation.state() == BondedVesselOperationRecord.State.CANCELED
                || operation.state() == BondedVesselOperationRecord.State.TERMINAL_DENIED
                || operation.state() == BondedVesselOperationRecord.State.QUARANTINED) {
            tokenVault.revokeOperation(entry.token().operationId());
            return completed(openOrClosedResult(operation, null));
        }
        CompletionStage<BondedVesselOperationRecord> claimed;
        if (operation.state() == BondedVesselOperationRecord.State.PREPARED) {
            claimed = evidenceAuthority.observe(entry.context()).thenCompose(observation -> {
                if (!observation.exactlyMatches(entry.context())) {
                    if (observation.status() == BondedVesselEvidenceAuthority.Status.INCOMPLETE
                            || observation.status() == BondedVesselEvidenceAuthority.Status.UNAVAILABLE) {
                        return CompletableFuture.failedFuture(new IndeterminateEvidence(
                                evidenceReason(observation)));
                    }
                    return CompletableFuture.failedFuture(
                            new TerminalPreApplyDenial(evidenceReason(observation), operation));
                }
                return journal.claim(operation.operationId(), wallClockMs.getAsLong())
                        .thenApply(result -> requireOperation(result, operation));
            });
        } else {
            claimed = completed(operation);
        }
        return claimed.thenCompose(current -> {
            if (current.state() == BondedVesselOperationRecord.State.APPLIED) {
                return finalizeApplied(current, entry.context(), entry.token(), recovery);
            }
            if (current.state() != BondedVesselOperationRecord.State.APPLYING) {
                return completed(openOrClosedResult(current, entry.token()));
            }
            return supplyAsync(() -> journal.findBinding(current.bindingId())).thenCompose(binding -> {
                if (binding == null) {
                    return quarantine(current, "binding-missing-during-apply", entry.token());
                }
                return evidenceAuthority.observe(entry.context()).thenCompose(observation -> {
                    if (!observation.exactlyMatches(entry.context())) {
                        if (observation.status() == BondedVesselEvidenceAuthority.Status.INCOMPLETE
                                || observation.status() == BondedVesselEvidenceAuthority.Status.UNAVAILABLE) {
                            return completed(openResult(
                                    BondedVesselOperationResult.Status.APPLYING,
                                    evidenceReason(observation), null, entry.token()));
                        }
                        return denyBeforeApply(current, evidenceReason(observation), entry.token());
                    }
                    return mutationAuthority.apply(current, binding, recovery)
                            .thenCompose(outcome -> handleApplyOutcome(
                                    current, entry.context(), entry.token(), outcome, recovery));
                });
            });
        }).exceptionallyCompose(failure -> {
            Throwable cause = unwrap(failure);
            if (cause instanceof TerminalPreApplyDenial denial) {
                return denyBeforeApply(denial.operation, denial.getMessage(), entry.token());
            }
            if (cause instanceof IndeterminateEvidence indeterminate) {
                return completed(BondedVesselOperationResult.unavailable(indeterminate.getMessage()));
            }
            return completed(unavailableResult(cause));
        });
    }

    @Nonnull
    private CompletionStage<BondedVesselOperationResult> handleApplyOutcome(
            BondedVesselOperationRecord operation,
            BondedVesselTransitionContext context,
            BondedVesselTransitionToken token,
            BondedVesselMutationAuthority.ApplyOutcome outcome,
            boolean recovery
    ) {
        return switch (outcome.status()) {
            case TERMINAL_DENIED -> denyBeforeApply(operation, outcome.reason(), token);
            case INDETERMINATE -> completed(openResult(
                    BondedVesselOperationResult.Status.APPLYING,
                    outcome.reason(), null, token));
            case QUARANTINED -> quarantine(operation, outcome.reason(), token);
            case APPLIED, ALREADY_APPLIED -> {
                BondedVesselRepository.AppliedTransition applied =
                        new BondedVesselRepository.AppliedTransition(
                                operation.operationId(),
                                outcome.committedProfileRevision(),
                                outcome.activeNpcUuid(),
                                outcome.activeLocation(),
                                outcome.itemEvidenceJson(),
                                outcome.reason(),
                                wallClockMs.getAsLong());
                yield journal.apply(applied).thenCompose(result -> {
                    BondedVesselOperationRecord durable = requireOperation(result, operation);
                    if (result.status() != BondedVesselRepository.Status.APPLIED
                            && result.status() != BondedVesselRepository.Status.IDEMPOTENT) {
                        return completed(fromMutation(result, token));
                    }
                    return finalizeApplied(durable, context, token, recovery);
                });
            }
        };
    }

    @Nonnull
    private CompletionStage<BondedVesselOperationResult> finalizeApplied(
            BondedVesselOperationRecord operation,
            BondedVesselTransitionContext context,
            @Nullable BondedVesselTransitionToken token,
            boolean recovery
    ) {
        return evidenceAuthority.finalizeSource(operation, context).thenCompose(finalization -> {
            String expectedFingerprint = operation.replacementFingerprint();
            if (expectedFingerprint == null
                    || !expectedFingerprint.equals(finalization.replacementFingerprint())) {
                return quarantine(operation, "replacement-fingerprint-mismatch", token);
            }
            if (finalization.status() == BondedVesselEvidenceAuthority.FinalizationStatus.SOURCE_CHANGED) {
                return quarantine(operation, finalization.reason(), token);
            }
            if (finalization.status() == BondedVesselEvidenceAuthority.FinalizationStatus.INDETERMINATE) {
                return completed(openResult(
                        BondedVesselOperationResult.Status.APPLIED,
                        finalization.reason(), null,
                        token != null ? token : tokenVault.issue(operation, context)));
            }
            return journal.commit(operation.operationId(), wallClockMs.getAsLong()).thenApply(result -> {
                BondedVesselOperationRecord durable = requireOperation(result, operation);
                if (result.status() == BondedVesselRepository.Status.COMMITTED) {
                    tokenVault.revokeOperation(UUID.fromString(operation.operationId()));
                    emitCommitted(durable, recovery);
                    return closedResult(BondedVesselOperationResult.Status.COMMITTED,
                            "transition-committed", durable);
                }
                if (result.status() == BondedVesselRepository.Status.IDEMPOTENT) {
                    tokenVault.revokeOperation(UUID.fromString(operation.operationId()));
                    return closedResult(BondedVesselOperationResult.Status.COMMITTED,
                            "transition-already-committed", durable);
                }
                return fromMutation(result, token);
            });
        });
    }

    @Nonnull
    private CompletionStage<BondedVesselOperationResult> denyBeforeApply(
            BondedVesselOperationRecord operation,
            String reason,
            @Nullable BondedVesselTransitionToken token
    ) {
        BondedVesselRepository.ApplyAbsenceProof proof = switch (operation.state()) {
            case PREPARED -> BondedVesselRepository.ApplyAbsenceProof.PREPARED_NOT_CLAIMED;
            case APPLYING -> BondedVesselRepository.ApplyAbsenceProof
                    .APPLYING_SOURCE_REVALIDATION_FAILED_BEFORE_MUTATION;
            default -> throw new IllegalStateException(
                    "Cannot prove apply absence from state " + operation.state());
        };
        return journal.denyBeforeApply(
                        operation.operationId(), reason, proof, wallClockMs.getAsLong())
                .thenApply(result -> {
                    BondedVesselOperationRecord durable = requireOperation(result, operation);
                    if (result.status() == BondedVesselRepository.Status.TERMINAL_DENIED
                            || durable.state() == BondedVesselOperationRecord.State.TERMINAL_DENIED) {
                        tokenVault.revokeOperation(UUID.fromString(operation.operationId()));
                        return closedResult(BondedVesselOperationResult.Status.DENIED, reason, durable);
                    }
                    return fromMutation(result, token);
                });
    }

    @Nonnull
    private CompletionStage<BondedVesselOperationResult> quarantine(
            BondedVesselOperationRecord operation,
            String reason,
            @Nullable BondedVesselTransitionToken token
    ) {
        return journal.quarantine(operation.operationId(), reason, wallClockMs.getAsLong())
                .thenApply(result -> {
                    tokenVault.revokeOperation(UUID.fromString(operation.operationId()));
                    BondedVesselOperationRecord durable = requireOperation(result, operation);
                    return closedResult(BondedVesselOperationResult.Status.QUARANTINED,
                            reason, durable);
                });
    }

    @Nonnull
    private CompletionStage<RecoveryAccumulator> recoverSequentially(
            List<BondedVesselOperationRecord> operations,
            int index,
            RecoveryAccumulator accumulator
    ) {
        if (index >= operations.size()) {
            return completed(accumulator);
        }
        BondedVesselOperationRecord operation = operations.get(index);
        if (operation.state() == BondedVesselOperationRecord.State.PREPARED) {
            accumulator.pending++;
            return recoverSequentially(operations, index + 1, accumulator);
        }
        if (operation.state() == BondedVesselOperationRecord.State.QUARANTINED) {
            accumulator.quarantined++;
            return recoverSequentially(operations, index + 1, accumulator);
        }
        BondedVesselTransitionContext context;
        try {
            context = sourceContextCodec.decode(requireValue(
                    operation.sourceContextJson(), "sourceContextJson"));
        } catch (Exception invalid) {
            return journal.quarantine(operation.operationId(),
                            "invalid-recovery-source-context", wallClockMs.getAsLong())
                    .handle((ignored, failure) -> {
                        accumulator.quarantined++;
                        return accumulator;
                    }).thenCompose(next -> recoverSequentially(operations, index + 1, next));
        }
        BondedVesselTransitionToken token = tokenVault.issue(operation, context);
        BondedVesselTokenVault.Entry entry = tokenVault.claim(token);
        return continueCommit(entry, operation, true).handle((result, failure) -> {
            if (failure != null) {
                accumulator.failed++;
            } else if (result.status() == BondedVesselOperationResult.Status.COMMITTED) {
                accumulator.committed++;
            } else if (result.status() == BondedVesselOperationResult.Status.QUARANTINED) {
                accumulator.quarantined++;
            } else {
                accumulator.pending++;
            }
            return accumulator;
        }).thenCompose(next -> recoverSequentially(operations, index + 1, next));
    }

    private String validateRequest(@Nullable BondedVesselBindingRecord binding,
                                   BondedVesselTransitionRequest request) {
        if (binding == null) return "binding-not-found";
        if (!binding.ownerUuid().equals(request.actorUuid())) return "owner-required";
        if (binding.generation() != request.expectedGeneration()) return "stale-generation";
        if (binding.expectedProfileRevision() != request.expectedProfileRevision()) {
            return "profile-revision-changed";
        }
        if (binding.activeOperationId() != null) return "operation-in-flight";
        if (binding.itemProjectionStatus() != BondedVesselBindingRecord.ItemProjectionStatus.PRESENT) {
            return "item-projection-not-present";
        }
        if (binding.lastItemId() != null
                && !binding.lastItemId().equals(request.context().sourceItemId())) {
            return "source-item-id-mismatch";
        }
        if (binding.cooldownUntilMs() != 0L
                && wallClockMs.getAsLong() < binding.cooldownUntilMs()) {
            return "transition-cooldown-active";
        }
        BondedVesselState source = toState(binding.lifecycleState());
        return switch (request.transition()) {
            case SUMMON -> source == BondedVesselState.STORED ? null : "summon-requires-stored";
            case STORE -> source != BondedVesselState.ACTIVE
                    ? "store-requires-active"
                    : Objects.equals(binding.activeNpcUuid(), request.context().expectedNpcUuid())
                            ? null : "active-npc-mismatch";
            case REPAIR_DEAD_TO_STORED -> source == BondedVesselState.DEAD
                    ? null : "repair-requires-dead";
            case RELEASE -> source == BondedVesselState.RELEASED
                    ? "binding-already-released" : null;
        };
    }

    private String validatePlan(BondedVesselBindingRecord binding,
                                BondedVesselTransitionRequest request,
                                BondedVesselTransitionPlanner.Plan plan) {
        BondedVesselState expected = switch (request.transition()) {
            case SUMMON -> BondedVesselState.ACTIVE;
            case STORE, REPAIR_DEAD_TO_STORED -> BondedVesselState.STORED;
            case RELEASE -> BondedVesselState.RELEASED;
        };
        if (plan.targetState() != expected) return "planner-target-state-mismatch";
        if (plan.targetCooldownUntilMs() != 0L
                && plan.targetCooldownUntilMs() < binding.cooldownUntilMs()) {
            return "planner-cooldown-regressed";
        }
        return null;
    }

    private BondedVesselOperationRecord createOperation(
            BondedVesselBindingRecord binding,
            BondedVesselTransitionRequest request,
            BondedVesselTransitionPlanner.Plan plan,
            long nowMs
    ) {
        BondedVesselBindingRecord.LifecycleState source = binding.lifecycleState();
        BondedVesselBindingRecord.LifecycleState applying = switch (request.transition()) {
            case SUMMON -> BondedVesselBindingRecord.LifecycleState.SUMMONING;
            case STORE -> BondedVesselBindingRecord.LifecycleState.STORING;
            case REPAIR_DEAD_TO_STORED -> BondedVesselBindingRecord.LifecycleState.DEAD;
            case RELEASE -> BondedVesselBindingRecord.LifecycleState.RELEASING;
        };
        return new BondedVesselOperationRecord(
                UUID.randomUUID().toString(), request.callerNamespace(), request.idempotencyKey(), null,
                binding.bindingId(), binding.profileId(), toAction(request.transition()),
                BondedVesselOperationRecord.State.PREPARED,
                binding.generation(), binding.generation() + 1L,
                binding.expectedProfileRevision(), binding.configId(), binding.configRevision(),
                source, applying, toLifecycle(plan.targetState()), binding.itemProjectionStatus(),
                toProjection(plan.targetProjectionStatus()), binding.cooldownUntilMs(),
                plan.targetCooldownUntilMs(), request.context().sourceItemId(),
                plan.candidateItemId(), request.context().sourceItemFingerprint(),
                plan.candidateItemFingerprint(), sourceContextCodec.encode(request.context()),
                plan.policySnapshotJson(), null, null, null, "PREPARED",
                saturatedAdd(nowMs, DEFAULT_TOKEN_LIFETIME_MS), nowMs, nowMs, 0L, 0L);
    }

    private boolean requestMatchesOperation(BondedVesselTransitionRequest request,
                                            BondedVesselOperationRecord operation) {
        return operation.callerNamespace().equals(request.callerNamespace())
                && operation.idempotencyKey().equals(request.idempotencyKey())
                && operation.bindingId().equals(request.bindingId().toString())
                && operation.action() == toAction(request.transition())
                && operation.priorGeneration() == request.expectedGeneration()
                && operation.expectedProfileRevision() == request.expectedProfileRevision()
                && Objects.equals(operation.sourceContextJson(), sourceContextCodec.encode(request.context()));
    }

    private boolean tokenMatchesOperation(BondedVesselTransitionToken token,
                                          BondedVesselOperationRecord operation) {
        return token.operationId().toString().equals(operation.operationId())
                && token.bindingId().toString().equals(operation.bindingId())
                && token.transition() == toTransition(operation.action())
                && token.expectedGeneration() == operation.priorGeneration()
                && token.candidateGeneration() == operation.candidateGeneration()
                && token.expectedProfileRevision() == operation.expectedProfileRevision()
                && token.sourceItemFingerprint().equals(operation.sourceFingerprint())
                && token.candidateItemId().equals(operation.targetItemId())
                && token.candidateItemFingerprint().equals(operation.replacementFingerprint());
    }

    private BondedVesselOperationResult openOrClosedResult(
            BondedVesselOperationRecord operation,
            @Nullable BondedVesselTransitionToken existingToken
    ) {
        return switch (operation.state()) {
            case PREPARED, APPLYING, APPLIED -> {
                BondedVesselTransitionToken token = existingToken;
                if (token == null) {
                    try {
                        token = tokenVault.issue(operation, sourceContextCodec.decode(
                                requireValue(operation.sourceContextJson(), "sourceContextJson")));
                    } catch (Exception invalid) {
                        yield BondedVesselOperationResult.unavailable(
                                "bonded-vessel-operation-source-context-invalid");
                    }
                }
                BondedVesselOperationResult.Status status = switch (operation.state()) {
                    case PREPARED -> BondedVesselOperationResult.Status.RESERVED;
                    case APPLYING -> BondedVesselOperationResult.Status.APPLYING;
                    case APPLIED -> BondedVesselOperationResult.Status.APPLIED;
                    default -> throw new IllegalStateException();
                };
                yield openResult(status, reason(operation, "operation-open"), null, token);
            }
            case COMMITTED -> closedResult(BondedVesselOperationResult.Status.COMMITTED,
                    reason(operation, "operation-committed"), operation);
            case CANCELED -> closedResult(BondedVesselOperationResult.Status.CANCELED,
                    reason(operation, "operation-canceled"), operation);
            case TERMINAL_DENIED -> closedResult(BondedVesselOperationResult.Status.DENIED,
                    reason(operation, "operation-terminal-denied"), operation);
            case QUARANTINED, COMPENSATING -> closedResult(
                    BondedVesselOperationResult.Status.QUARANTINED,
                    reason(operation, "operation-quarantined"), operation);
        };
    }

    private BondedVesselOperationResult fromMutation(
            BondedVesselRepository.MutationResult result,
            @Nullable BondedVesselTransitionToken token
    ) {
        if (result.operation() != null) {
            return openOrClosedResult(result.operation(), token);
        }
        return switch (result.status()) {
            case DENIED, CONFLICT, INVALID_STATE, NOT_FOUND -> denied(
                    result.reason() == null ? "vessel-transition-denied" : result.reason());
            case QUARANTINED -> new BondedVesselOperationResult(
                    BondedVesselOperationResult.Status.QUARANTINED,
                    result.reason() == null ? "vessel-transition-quarantined" : result.reason(),
                    null, null, null, null,
                    BondedVesselOperationResult.UNKNOWN, BondedVesselOperationResult.UNKNOWN,
                    null, null, null, null);
            default -> BondedVesselOperationResult.unavailable(UNAVAILABLE);
        };
    }

    private BondedVesselOperationResult openResult(
            BondedVesselOperationResult.Status status,
            String reason,
            @Nullable BondedVesselBindingRecord binding,
            BondedVesselTransitionToken token
    ) {
        return new BondedVesselOperationResult(
                status, requireText(reason, "reason"), token.operationId(), token,
                token.bindingId(), binding == null ? null : binding.profileId(),
                token.candidateGeneration(),
                binding == null ? token.expectedProfileRevision() : binding.expectedProfileRevision(),
                binding == null ? null : nullableCooldown(binding.cooldownUntilMs()),
                token.candidateState(), token.candidateItemId(), token.candidateItemFingerprint());
    }

    private BondedVesselOperationResult closedResult(
            BondedVesselOperationResult.Status status,
            String reason,
            BondedVesselOperationRecord operation
    ) {
        return new BondedVesselOperationResult(
                status, requireText(reason, "reason"), UUID.fromString(operation.operationId()), null,
                UUID.fromString(operation.bindingId()), operation.profileId(),
                operation.candidateGeneration(), operation.expectedProfileRevision(),
                nullableCooldown(operation.targetCooldownUntilMs()),
                toState(operation.targetLifecycleState()), operation.targetItemId(),
                operation.replacementFingerprint());
    }

    private BondedVesselOperationResult denied(String reason) {
        return new BondedVesselOperationResult(
                BondedVesselOperationResult.Status.DENIED, reason,
                null, null, null, null,
                BondedVesselOperationResult.UNKNOWN, BondedVesselOperationResult.UNKNOWN,
                null, null, null, null);
    }

    private BondedVesselOperationView toOperationView(BondedVesselOperationRecord operation) {
        return new BondedVesselOperationView(
                UUID.fromString(operation.operationId()), operation.callerNamespace(),
                operation.idempotencyKey(), toDurableStatus(operation.state()),
                reason(operation, "operation-status-" + operation.state().name().toLowerCase()),
                UUID.fromString(operation.bindingId()), operation.profileId(),
                toTransition(operation.action()), operation.priorGeneration(),
                operation.candidateGeneration(), operation.expectedProfileRevision(),
                nullableCooldown(operation.targetCooldownUntilMs()),
                !"PREPARED".equals(operation.recoveryStatus()), operation.updatedAtMs());
    }

    private BondedVesselView toView(BondedVesselBindingRecord binding) {
        return new BondedVesselView(
                UUID.fromString(binding.bindingId()), binding.profileId(), binding.ownerUuid(),
                binding.configId(), toState(binding.lifecycleState()), binding.generation(),
                binding.expectedProfileRevision(), nullableCooldown(binding.cooldownUntilMs()),
                toProjection(binding.itemProjectionStatus()), binding.activeNpcUuid(),
                binding.updatedAtMs());
    }

    private void emitCommitted(BondedVesselOperationRecord operation, boolean recovered) {
        try {
            eventSink.emit(new BondedVesselStateChangedEvent(
                    UUID.fromString(operation.operationId()), UUID.fromString(operation.bindingId()),
                    operation.profileId(),
                    requireBinding(operation.bindingId()).ownerUuid(),
                    operation.configId(), operation.priorGeneration(), operation.candidateGeneration(),
                    toState(operation.priorLifecycleState()), toState(operation.targetLifecycleState()),
                    operation.expectedProfileRevision(), operation.targetCooldownUntilMs(),
                    reason(operation, "transition-committed"), recovered,
                    operation.appliedAtMs(), wallClockMs.getAsLong()));
        } catch (Exception ignored) {
            // Event delivery is post-commit and cannot change the durable result.
        }
    }

    private BondedVesselBindingRecord requireBinding(String bindingId) throws Exception {
        BondedVesselBindingRecord binding = journal.findBinding(bindingId);
        if (binding == null) throw new IllegalStateException("Committed binding is missing.");
        return binding;
    }

    private BondedVesselOperationRecord requireOperation(
            BondedVesselRepository.MutationResult result,
            BondedVesselOperationRecord fallback
    ) {
        return result.operation() == null ? fallback : result.operation();
    }

    private BondedVesselOperationResult unavailableResult(Throwable failure) {
        markUnavailable("bonded-vessel-runtime-failure");
        return BondedVesselOperationResult.unavailable(UNAVAILABLE);
    }

    private void markUnavailable(String reason) {
        readiness.set(new BondedVesselReadinessView(
                BondedVesselReadinessView.Readiness.UNAVAILABLE,
                reason, 0L, 0L, 0L, Math.max(0L, wallClockMs.getAsLong())));
    }

    private String evidenceReason(BondedVesselEvidenceAuthority.SourceObservation observation) {
        return switch (observation.status()) {
            case EXACT -> "source-evidence-field-mismatch";
            case CHANGED -> "source-evidence-changed";
            case INCOMPLETE -> "source-evidence-incomplete";
            case UNAVAILABLE -> "source-evidence-unavailable";
        };
    }

    private String reason(BondedVesselOperationRecord operation, String fallback) {
        return operation.reasonCode() == null ? fallback : operation.reasonCode();
    }

    private static BondedVesselDurableOperationStatus toDurableStatus(
            BondedVesselOperationRecord.State state
    ) {
        return switch (state) {
            case PREPARED -> BondedVesselDurableOperationStatus.PREPARED;
            case APPLYING, COMPENSATING -> BondedVesselDurableOperationStatus.APPLYING;
            case APPLIED -> BondedVesselDurableOperationStatus.APPLIED;
            case COMMITTED -> BondedVesselDurableOperationStatus.COMMITTED;
            case CANCELED -> BondedVesselDurableOperationStatus.CANCELED;
            case TERMINAL_DENIED -> BondedVesselDurableOperationStatus.TERMINAL_DENIED;
            case QUARANTINED -> BondedVesselDurableOperationStatus.QUARANTINED;
        };
    }

    private static BondedVesselOperationRecord.Action toAction(BondedVesselTransition transition) {
        return switch (transition) {
            case SUMMON -> BondedVesselOperationRecord.Action.SUMMON;
            case STORE -> BondedVesselOperationRecord.Action.STORE;
            case REPAIR_DEAD_TO_STORED -> BondedVesselOperationRecord.Action.REPAIR;
            case RELEASE -> BondedVesselOperationRecord.Action.RELEASE;
        };
    }

    private static BondedVesselTransition toTransition(BondedVesselOperationRecord.Action action) {
        return switch (action) {
            case SUMMON -> BondedVesselTransition.SUMMON;
            case STORE -> BondedVesselTransition.STORE;
            case REPAIR -> BondedVesselTransition.REPAIR_DEAD_TO_STORED;
            case RELEASE -> BondedVesselTransition.RELEASE;
            default -> throw new IllegalArgumentException("Operation is not a public transition: " + action);
        };
    }

    private static BondedVesselState toState(BondedVesselBindingRecord.LifecycleState state) {
        return BondedVesselState.valueOf(state.name());
    }

    private static BondedVesselBindingRecord.LifecycleState toLifecycle(BondedVesselState state) {
        return BondedVesselBindingRecord.LifecycleState.valueOf(state.name());
    }

    private static BondedVesselProjectionStatus toProjection(
            BondedVesselBindingRecord.ItemProjectionStatus status
    ) {
        return BondedVesselProjectionStatus.valueOf(status.name());
    }

    private static BondedVesselBindingRecord.ItemProjectionStatus toProjection(
            BondedVesselProjectionStatus status
    ) {
        return switch (status) {
            case PRESENT -> BondedVesselBindingRecord.ItemProjectionStatus.PRESENT;
            case MISSING -> BondedVesselBindingRecord.ItemProjectionStatus.MISSING;
            case AMBIGUOUS -> BondedVesselBindingRecord.ItemProjectionStatus.AMBIGUOUS;
            case REISSUE_PENDING -> BondedVesselBindingRecord.ItemProjectionStatus.REISSUE_PENDING;
            case QUARANTINED, UNKNOWN -> BondedVesselBindingRecord.ItemProjectionStatus.QUARANTINED;
        };
    }

    @Nullable
    private static Long nullableCooldown(long cooldownUntilMs) {
        return cooldownUntilMs == 0L ? null : cooldownUntilMs;
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required.");
        return normalized;
    }

    private static String requireValue(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " is missing.");
        }
        return value;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private <T> CompletionStage<T> supplyAsync(CheckedSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (Exception exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }, executor);
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private static final class Preparation {
        private final BondedVesselBindingRecord binding;
        private final BondedVesselTransitionPlanner.Plan plan;
        private final String denial;

        private Preparation(BondedVesselBindingRecord binding,
                            BondedVesselTransitionPlanner.Plan plan,
                            String denial) {
            this.binding = binding;
            this.plan = plan;
            this.denial = denial;
        }

        static Preparation accepted(BondedVesselBindingRecord binding,
                                    BondedVesselTransitionPlanner.Plan plan) {
            return new Preparation(binding, plan, null);
        }

        static Preparation denied(String reason) {
            return new Preparation(null, null, reason);
        }
    }

    private static final class TerminalPreApplyDenial extends RuntimeException {
        private final BondedVesselOperationRecord operation;

        private TerminalPreApplyDenial(String message, BondedVesselOperationRecord operation) {
            super(message);
            this.operation = operation;
        }
    }

    private static final class IndeterminateEvidence extends RuntimeException {
        private IndeterminateEvidence(String message) {
            super(message);
        }
    }

    private static final class RecoveryAccumulator {
        private final int scanned;
        private int committed;
        private int pending;
        private int quarantined;
        private int failed;

        private RecoveryAccumulator(int scanned) {
            this.scanned = scanned;
        }

        private RecoveryReport report() {
            return new RecoveryReport(scanned, committed, pending, quarantined, failed);
        }
    }

    public record RecoveryReport(int scanned,
                                 int committed,
                                 int pending,
                                 int quarantined,
                                 int failed) {
        public RecoveryReport {
            if (scanned < 0 || committed < 0 || pending < 0 || quarantined < 0 || failed < 0) {
                throw new IllegalArgumentException("Recovery counts cannot be negative.");
            }
        }
    }
}
