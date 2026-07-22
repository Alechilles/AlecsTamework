package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.ItemCostComponentView;
import com.alechilles.alecstamework.api.PaidCommandRevivalApi;
import com.alechilles.alecstamework.api.PaidCommandRevivalCostQuoteView;
import com.alechilles.alecstamework.api.PaidCommandRevivalOperationView;
import com.alechilles.alecstamework.api.PaidCommandRevivalQuote;
import com.alechilles.alecstamework.api.PaidCommandRevivalQuoteRequest;
import com.alechilles.alecstamework.api.PaidCommandRevivalRequest;
import com.alechilles.alecstamework.api.PaidCommandRevivalResult;
import com.alechilles.alecstamework.api.PaidCommandRevivedEvent;
import com.alechilles.alecstamework.persistence.sqlite.PaidCommandRevivalRecord;
import com.alechilles.alecstamework.persistence.sqlite.PaidCommandRevivalRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Server-authoritative paid revival state machine shared by the command panel and public API. */
public final class PaidCommandRevivalCoordinator implements PaidCommandRevivalApi {
    private final PaidCommandRevivalRepository repository;
    private final Authority authority;
    private final Clock clock;
    private final Consumer<PaidCommandRevivedEvent> eventSink;

    public PaidCommandRevivalCoordinator(@Nonnull PaidCommandRevivalRepository repository,
                                         @Nonnull Authority authority,
                                         @Nonnull Clock clock,
                                         @Nonnull Consumer<PaidCommandRevivedEvent> eventSink) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
    }

    @Override
    public CompletionStage<PaidCommandRevivalQuote> quote(PaidCommandRevivalQuoteRequest request) {
        Objects.requireNonNull(request, "request");
        return authority.resolve(request.ownerUuid(), request.profileId(), request.commandFamilyId(), false)
                .thenApply(resolved -> resolved.toQuote(request.ownerUuid(), request.profileId(), request.commandFamilyId()));
    }

    @Override
    public CompletionStage<PaidCommandRevivalResult> revive(PaidCommandRevivalRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            PaidCommandRevivalRecord existing = repository.findByIdempotency(
                    request.callerNamespace(), request.idempotencyKey());
            if (existing != null) return CompletableFuture.completedFuture(result(existing, true));
        } catch (Exception failure) {
            return CompletableFuture.completedFuture(PaidCommandRevivalResult.unavailable(
                    request.profileId(), "paid-revival-journal-read-failed"));
        }
        return authority.resolve(request.ownerUuid(), request.profileId(), request.commandFamilyId(), true)
                .thenCompose(resolved -> begin(request, resolved));
    }

    @Override
    public CompletionStage<Optional<PaidCommandRevivalOperationView>> findOperation(
            String callerNamespace, String idempotencyKey) {
        try {
            PaidCommandRevivalRecord record = repository.findByIdempotency(callerNamespace, idempotencyKey);
            return CompletableFuture.completedFuture(Optional.ofNullable(record).map(PaidCommandRevivalCoordinator::view));
        } catch (Exception failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletionStage<PaidCommandRevivalResult> begin(PaidCommandRevivalRequest request,
                                                             ResolvedRevival resolved) {
        if (resolved.status() != PaidCommandRevivalQuote.Status.READY) {
            return CompletableFuture.completedFuture(denied(request.profileId(), resolved));
        }
        if (!resolved.affordable()) {
            return CompletableFuture.completedFuture(new PaidCommandRevivalResult(
                    null, PaidCommandRevivalResult.Status.INSUFFICIENT_COST, request.profileId(),
                    resolved.exactCost(), "insufficient-revival-cost", false));
        }
        long now = clock.millis();
        UUID operationId = UUID.randomUUID();
        PaidCommandRevivalRecord prepared = new PaidCommandRevivalRecord(
                operationId, request.callerNamespace(), request.idempotencyKey(), request.ownerUuid(),
                request.profileId(), request.commandFamilyId(), resolved.roleId(), resolved.configId(),
                resolved.configRevision(), resolved.deathRevision(), resolved.profileRevision(),
                "pending:" + operationId, resolved.placementFingerprint(),
                resolved.reviveProjectionOperationId(), PaidCommandRevivalRecord.State.PREPARED,
                resolved.exactCost(), List.of(), null, now, now, null);
        return committed(repository.prepareAsync(prepared)).thenCompose(prepare -> {
            if (prepare == null || prepare.operation() == null) {
                return completedUnavailable(request.profileId(), "paid-revival-prepare-failed");
            }
            if (prepare.status() == PaidCommandRevivalRepository.Status.CONFLICT) {
                return CompletableFuture.completedFuture(new PaidCommandRevivalResult(
                        prepare.operation().operationId(), PaidCommandRevivalResult.Status.CONFLICT,
                        request.profileId(), prepare.operation().exactCost(), prepare.reason(), false));
            }
            if (prepare.status() == PaidCommandRevivalRepository.Status.IDEMPOTENT) {
                return CompletableFuture.completedFuture(result(prepare.operation(), true));
            }
            return authority.prepareActivation(resolved, operationId).thenCompose(activation -> {
                if (activation == null || !activation.accepted()) {
                    String reason = activation != null && activation.reason() != null
                            ? activation.reason() : "population-admission-denied";
                    return cancel(prepared, reason, activation);
                }
                if (activation.populationOperationId() == null
                        || activation.populationOperationId().isBlank()) {
                    return cancel(prepared, "population-admission-operation-unavailable", activation);
                }
                return committed(repository.recordActivationAsync(operationId,
                        activation.populationOperationId(), resolved.placementFingerprint(), clock.millis()))
                        .thenCompose(recorded -> {
                            PaidCommandRevivalRecord frozen = recorded != null ? recorded.operation() : null;
                            if (frozen == null) {
                                return cancel(prepared, "population-admission-journal-failed", activation);
                            }
                            CommandReviveInventoryPaymentService.PlanResult plan =
                                    authority.plan(resolved, operationId);
                            if (!plan.ready()) {
                                return cancel(frozen,
                                        plan.status() == CommandReviveInventoryPaymentService.Status.INSUFFICIENT
                                                ? "insufficient-revival-cost"
                                                : "inventory-reservation-unavailable",
                                        activation);
                            }
                            return committed(repository.reserveAsync(
                                    operationId, plan.reservations(), clock.millis()))
                                    .thenCompose(reserved -> reserved == null || reserved.operation() == null
                                            ? cancel(frozen, "inventory-reservation-persist-failed", activation)
                                            : holdAndConsume(resolved, reserved, activation));
                        });
            });
        });
    }

    private CompletionStage<PaidCommandRevivalResult> holdAndConsume(
            ResolvedRevival resolved,
            @Nullable PaidCommandRevivalRepository.MutationResult reserved,
            @Nullable ActivationPreparation activation) {
        if (reserved == null || reserved.operation() == null) {
            return completedUnavailable(resolved.profileId(), "inventory-reservation-persist-failed");
        }
        PaidCommandRevivalRecord operation = reserved.operation();
        return authority.hold(resolved, operation.operationId(), operation.reservations())
                .thenCompose(held -> {
                    if (!held.succeeded()) return cancel(operation,
                            held.reason() == null ? "inventory-reservation-hold-failed" : held.reason(),
                            activation);
                    return revalidateAndConsume(resolved, reserved, activation);
                });
    }

    private CompletionStage<PaidCommandRevivalResult> revalidateAndConsume(
            ResolvedRevival frozen,
            @Nullable PaidCommandRevivalRepository.MutationResult reservedResult,
            @Nullable ActivationPreparation activation) {
        if (reservedResult == null || reservedResult.operation() == null
                || (reservedResult.status() != PaidCommandRevivalRepository.Status.APPLIED
                && reservedResult.status() != PaidCommandRevivalRepository.Status.IDEMPOTENT)) {
            return completedUnavailable(frozen.profileId(), "inventory-reservation-persist-failed");
        }
        PaidCommandRevivalRecord operation = reservedResult.operation();
        return authority.resolve(operation.ownerUuid(), operation.profileId(), operation.commandFamilyId(), true)
                .thenCompose(current -> {
                    String staleReason = frozenMismatch(frozen, current);
                    if (staleReason != null) return cancel(operation, staleReason, activation);
                    return authority.consume(current, operation.operationId(),
                                    operation.exactCost(), operation.reservations())
                            .thenCompose(consumed -> {
                                if (!consumed.succeeded()) return cancel(operation,
                                        consumed.reason() == null ? "inventory-consumption-failed" : consumed.reason(),
                                        activation);
                                return committed(repository.transitionAsync(operation.operationId(),
                                        PaidCommandRevivalRecord.State.RESERVED,
                                        PaidCommandRevivalRecord.State.COST_CONSUMED,
                                        null, clock.millis())).thenCompose(costCommitted -> {
                                    if (costCommitted == null || costCommitted.operation() == null) {
                                        return quarantineAfterAmbiguousCharge(operation,
                                                "cost-consumed-journal-commit-ambiguous", true, activation);
                                    }
                                    return apply(current, costCommitted.operation(), false, activation);
                                });
                            });
                });
    }

    private CompletionStage<PaidCommandRevivalResult> apply(ResolvedRevival context,
                                                             PaidCommandRevivalRecord operation,
                                                             boolean recovered,
                                                             @Nullable ActivationPreparation activation) {
        return committed(repository.transitionAsync(operation.operationId(),
                PaidCommandRevivalRecord.State.COST_CONSUMED,
                PaidCommandRevivalRecord.State.APPLYING, null, clock.millis())).thenCompose(applying -> {
            if (applying == null || applying.operation() == null) {
                return requireRefund(operation, "revival-apply-journal-failed", activation);
            }
            return authority.apply(context, operation, activation).thenCompose(outcome -> {
                if (!outcome.succeeded()) {
                    return requireRefund(applying.operation(),
                            outcome.reason() == null ? "revival-apply-failed" : outcome.reason(), activation);
                }
                return committed(repository.transitionAsync(operation.operationId(),
                        PaidCommandRevivalRecord.State.APPLYING,
                        PaidCommandRevivalRecord.State.SUCCEEDED, null, clock.millis())).thenApply(success -> {
                    PaidCommandRevivalRecord committed = success != null ? success.operation() : null;
                    if (committed == null) {
                        return new PaidCommandRevivalResult(operation.operationId(),
                                PaidCommandRevivalResult.Status.RECOVERY_PENDING, operation.profileId(),
                                operation.exactCost(), "revival-success-journal-ambiguous", recovered);
                    }
                    long now = clock.millis();
                    eventSink.accept(new PaidCommandRevivedEvent(
                            committed.operationId(), committed.callerNamespace(), committed.idempotencyKey(),
                            committed.ownerUuid(), committed.profileId(), committed.commandFamilyId(),
                            committed.exactCost(), recovered, now, now));
                    return result(committed, recovered);
                });
            });
        });
    }

    private CompletionStage<PaidCommandRevivalResult> cancel(PaidCommandRevivalRecord operation,
                                                              String reason) {
        return cancel(operation, reason, null);
    }

    private CompletionStage<PaidCommandRevivalResult> cancel(
            PaidCommandRevivalRecord operation,
            String reason,
            @Nullable ActivationPreparation activation) {
        PaidCommandRevivalRecord.State expected = operation.state() == PaidCommandRevivalRecord.State.PREPARED
                ? PaidCommandRevivalRecord.State.PREPARED : PaidCommandRevivalRecord.State.RESERVED;
        CompletionStage<Boolean> activationCanceled = operation.populationAdmissionOperationId() != null
                && operation.populationAdmissionOperationId().startsWith("pending:")
                ? CompletableFuture.completedFuture(true)
                : cancelActivation(operation, activation);
        return activationCanceled.thenCompose(canceled -> {
            if (!canceled) return CompletableFuture.completedFuture(new PaidCommandRevivalResult(
                    operation.operationId(), PaidCommandRevivalResult.Status.RECOVERY_PENDING,
                    operation.profileId(), operation.exactCost(),
                    "population-admission-cancel-pending", true));
            return committed(repository.transitionAsync(operation.operationId(), expected,
                    PaidCommandRevivalRecord.State.CANCELED, reason, clock.millis()))
                    .thenCompose(ignored -> authority.release(operation)
                            .handle((released, failure) -> new PaidCommandRevivalResult(
                                    operation.operationId(),
                                    reason.contains("insufficient")
                                            ? PaidCommandRevivalResult.Status.INSUFFICIENT_COST
                                            : PaidCommandRevivalResult.Status.DENIED,
                                    operation.profileId(), operation.exactCost(), reason, false)));
        });
    }

    private CompletionStage<PaidCommandRevivalResult> requireRefund(
            PaidCommandRevivalRecord operation,
            String reason,
            @Nullable ActivationPreparation activation) {
        return cancelActivation(operation, activation).thenCompose(canceled -> committed(
                repository.transitionAsync(operation.operationId(), operation.state(),
                PaidCommandRevivalRecord.State.REFUND_REQUIRED, reason, clock.millis())).thenCompose(required -> {
            PaidCommandRevivalRecord marked = required != null ? required.operation() : null;
            if (marked == null) return quarantineAfterAmbiguousCharge(
                    operation, reason, false, activation);
            return deliverRefund(marked, reason, false);
        }));
    }

    /** Bounded startup classification; owner-bound inventory/world work resumes on player join. */
    @Nonnull
    public CompletionStage<RecoveryReport> recoverStartup(int limit) {
        try {
            List<PaidCommandRevivalRecord> rows = repository.loadRecoverable();
            int bounded = Math.min(Math.max(0, limit), rows.size());
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            int[] canceled = {0};
            for (int index = 0; index < bounded; index++) {
                PaidCommandRevivalRecord row = rows.get(index);
                if (row.state() == PaidCommandRevivalRecord.State.PREPARED
                        && row.populationAdmissionOperationId() != null
                        && row.populationAdmissionOperationId().startsWith("pending:")) {
                    chain = chain.thenCompose(ignored -> cancel(row, "startup-prepared-no-charge")
                            .thenAccept(result -> canceled[0]++));
                }
            }
            return chain.handle((ignored, failure) -> new RecoveryReport(
                    failure == null, bounded, canceled[0], bounded - canceled[0],
                    failure == null ? null : "startup-recovery-failed"));
        } catch (Exception failure) {
            return CompletableFuture.completedFuture(new RecoveryReport(
                    false, 0, 0, 0, "startup-recovery-read-failed"));
        }
    }

    /** Resumes all journal rows for an owner once their authoritative inventory/world is online. */
    @Nonnull
    public CompletionStage<RecoveryReport> recoverOwner(@Nonnull UUID ownerUuid, int limit) {
        try {
            List<PaidCommandRevivalRecord> rows = repository.loadRecoverable().stream()
                    .filter(row -> ownerUuid.equals(row.ownerUuid())).limit(Math.max(0, limit)).toList();
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            int[] recovered = {0};
            for (PaidCommandRevivalRecord row : rows) {
                chain = chain.thenCompose(ignored -> recover(row).thenAccept(result -> recovered[0]++));
            }
            return chain.handle((ignored, failure) -> new RecoveryReport(
                    failure == null, rows.size(), recovered[0], rows.size() - recovered[0],
                    failure == null ? null : "owner-recovery-failed"));
        } catch (Exception failure) {
            return CompletableFuture.completedFuture(new RecoveryReport(
                    false, 0, 0, 0, "owner-recovery-read-failed"));
        }
    }

    private CompletionStage<PaidCommandRevivalResult> recover(PaidCommandRevivalRecord operation) {
        return switch (operation.state()) {
            case PREPARED -> cancel(operation, "recovered-prepared-no-charge");
            case RESERVED -> recoverReserved(operation);
            case COST_CONSUMED -> resolveForRecovery(operation)
                    .thenCompose(context -> apply(context, operation, true, null));
            case APPLYING -> recoverApplying(operation);
            case REFUND_REQUIRED -> deliverRefund(operation, "recovered-refund", true);
            case QUARANTINED -> recoverQuarantined(operation);
            default -> CompletableFuture.completedFuture(result(operation, true));
        };
    }

    private CompletionStage<PaidCommandRevivalResult> recoverReserved(PaidCommandRevivalRecord operation) {
        return authority.inspectReservation(operation).thenCompose(evidence -> {
            if (evidence == CommandReviveInventoryPaymentService.ReceiptEvidence.AMBIGUOUS
                    || evidence == CommandReviveInventoryPaymentService.ReceiptEvidence.UNAVAILABLE) {
                return quarantineAfterAmbiguousCharge(
                        operation, "reserved-inventory-evidence-ambiguous", true, null);
            }
            return resolveForRecovery(operation).thenCompose(context -> {
                CompletionStage<CommandReviveInventoryPaymentService.ConsumeResult> held =
                        evidence == CommandReviveInventoryPaymentService.ReceiptEvidence.HELD
                                ? CompletableFuture.completedFuture(
                                new CommandReviveInventoryPaymentService.ConsumeResult(
                                        CommandReviveInventoryPaymentService.Status.READY, null))
                                : authority.hold(context, operation.operationId(), operation.reservations());
                return held.thenCompose(result -> {
                    if (!result.succeeded()) return quarantineAfterAmbiguousCharge(
                            operation, "reservation-receipt-recovery-failed", true, null);
                    return revalidateAndConsume(context,
                            new PaidCommandRevivalRepository.MutationResult(
                                    PaidCommandRevivalRepository.Status.IDEMPOTENT, operation, null), null);
                });
            });
        });
    }

    private CompletionStage<PaidCommandRevivalResult> recoverApplying(PaidCommandRevivalRecord operation) {
        return authority.inspectProjection(operation).thenCompose(evidence -> {
            if (evidence == ProjectionEvidence.REVIVED) return commitRecoveredSuccess(operation);
            if (evidence == ProjectionEvidence.DEAD) return resolveForRecovery(operation)
                    .thenCompose(context -> authority.apply(context, operation, null))
                    .thenCompose(outcome -> outcome.succeeded()
                            ? commitRecoveredSuccess(operation)
                            : requireRefund(operation, outcome.reason(), null));
            return quarantineAfterAmbiguousCharge(
                    operation, "projection-evidence-ambiguous", false, null);
        });
    }

    private CompletionStage<PaidCommandRevivalResult> recoverQuarantined(PaidCommandRevivalRecord operation) {
        return authority.inspectProjection(operation).thenCompose(evidence ->
                evidence == ProjectionEvidence.REVIVED
                        ? committed(repository.transitionAsync(operation.operationId(),
                        PaidCommandRevivalRecord.State.QUARANTINED,
                        PaidCommandRevivalRecord.State.SUCCEEDED,
                        "recovered-projection-proof", clock.millis()))
                        .thenApply(done -> recoveredSuccessResult(operation, done))
                        : CompletableFuture.completedFuture(result(operation, true)));
    }

    private CompletionStage<PaidCommandRevivalResult> deliverRefund(
            PaidCommandRevivalRecord operation, String reason, boolean recovered) {
        return committed(repository.beginRefundDeliveryAsync(operation.operationId(), clock.millis()))
                .thenCompose(status -> {
                    if (status == PaidCommandRevivalRepository.RefundDeliveryStatus.STARTED) {
                        return authority.refund(operation).thenCompose(refund -> {
                            if (refund.succeeded()) return finalizeRefund(operation, reason, recovered);
                            return committed(repository.resetRefundDeliveryAsync(
                                    operation.operationId(), clock.millis())).thenApply(reset ->
                                    new PaidCommandRevivalResult(operation.operationId(),
                                            PaidCommandRevivalResult.Status.REFUND_PENDING,
                                            operation.profileId(), operation.exactCost(), refund.reason(), recovered));
                        });
                    }
                    if (status == PaidCommandRevivalRepository.RefundDeliveryStatus.DELIVERED) {
                        return finalizeRefund(operation, reason, recovered);
                    }
                    if (status == PaidCommandRevivalRepository.RefundDeliveryStatus.DELIVERING) {
                        return authority.inspectRefundDelivery(operation).thenCompose(evidence -> {
                            if (evidence == CommandReviveInventoryPaymentService.RefundEvidence.DELIVERED) {
                                return finalizeRefund(operation, reason, recovered);
                            }
                            if (evidence == CommandReviveInventoryPaymentService.RefundEvidence.UNAVAILABLE) {
                                return CompletableFuture.completedFuture(result(operation, true));
                            }
                            return quarantineAfterAmbiguousCharge(operation,
                                    "refund-delivery-evidence-ambiguous", false, null);
                        });
                    }
                    return quarantineAfterAmbiguousCharge(
                            operation, "refund-claim-unavailable", false, null);
                });
    }

    private CompletionStage<PaidCommandRevivalResult> finalizeRefund(
            PaidCommandRevivalRecord operation, String reason, boolean recovered) {
        return committed(repository.transitionAsync(operation.operationId(),
                PaidCommandRevivalRecord.State.REFUND_REQUIRED,
                PaidCommandRevivalRecord.State.REFUNDED, reason, clock.millis())).thenCompose(done ->
                authority.clearRefundReceipt(operation).handle((ignored, failure) ->
                        result(done != null && done.operation() != null ? done.operation() : operation, recovered)));
    }

    private CompletionStage<ResolvedRevival> resolveForRecovery(PaidCommandRevivalRecord operation) {
        return authority.resolve(operation.ownerUuid(), operation.profileId(),
                operation.commandFamilyId(), true).thenApply(current -> {
            if (!operation.configRevision().equals(current.configRevision())
                    || operation.deathRevision() != current.deathRevision()
                    || operation.profileRevision() != current.profileRevision()
                    || !operation.exactCost().equals(current.exactCost())) {
                throw new IllegalStateException("paid-revival-recovery-fence-changed");
            }
            return current;
        });
    }

    private CompletionStage<PaidCommandRevivalResult> commitRecoveredSuccess(
            PaidCommandRevivalRecord operation) {
        return committed(repository.transitionAsync(operation.operationId(),
                PaidCommandRevivalRecord.State.APPLYING,
                PaidCommandRevivalRecord.State.SUCCEEDED, "recovered-success", clock.millis()))
                .thenApply(done -> recoveredSuccessResult(operation, done));
    }

    private PaidCommandRevivalResult recoveredSuccessResult(
            PaidCommandRevivalRecord fallback,
            @Nullable PaidCommandRevivalRepository.MutationResult mutation) {
        PaidCommandRevivalRecord committed = mutation != null && mutation.operation() != null
                ? mutation.operation() : fallback;
        if (committed.state() == PaidCommandRevivalRecord.State.SUCCEEDED) {
            long now = clock.millis();
            eventSink.accept(new PaidCommandRevivedEvent(
                    committed.operationId(), committed.callerNamespace(), committed.idempotencyKey(),
                    committed.ownerUuid(), committed.profileId(), committed.commandFamilyId(),
                    committed.exactCost(), true, now, now));
        }
        return result(committed, true);
    }

    private CompletionStage<PaidCommandRevivalResult> quarantineAfterAmbiguousCharge(
            PaidCommandRevivalRecord operation,
            String reason,
            boolean releaseActivation,
            @Nullable ActivationPreparation activation) {
        PaidCommandRevivalRecord.State expected = operation.state();
        if (expected == PaidCommandRevivalRecord.State.PREPARED) expected = PaidCommandRevivalRecord.State.RESERVED;
        PaidCommandRevivalRecord.State expectedState = expected;
        CompletionStage<Boolean> canceled = releaseActivation
                ? cancelActivation(operation, activation)
                : CompletableFuture.completedFuture(true);
        return canceled.thenCompose(cancelIgnored -> committed(repository.transitionAsync(operation.operationId(), expectedState,
                PaidCommandRevivalRecord.State.QUARANTINED, reason, clock.millis())).handle((transitionIgnored, failure) ->
                new PaidCommandRevivalResult(operation.operationId(),
                        PaidCommandRevivalResult.Status.RECOVERY_PENDING, operation.profileId(),
                        operation.exactCost(), reason, true)));
    }

    private CompletionStage<Boolean> cancelActivation(
            PaidCommandRevivalRecord operation,
            @Nullable ActivationPreparation activation) {
        return authority.cancelActivation(operation, activation != null ? activation.runtimeHandle() : null)
                .handle((canceled, failure) -> failure == null && Boolean.TRUE.equals(canceled));
    }

    private static String frozenMismatch(ResolvedRevival frozen, ResolvedRevival current) {
        if (current.status() != PaidCommandRevivalQuote.Status.READY) return "revival-no-longer-eligible";
        if (!frozen.roleId().equals(current.roleId())) return "revival-role-changed";
        if (!frozen.configRevision().equals(current.configRevision())) return "revival-config-changed";
        if (frozen.deathRevision() != current.deathRevision()) return "revival-death-record-changed";
        if (frozen.profileRevision() != current.profileRevision()) return "revival-profile-changed";
        if (!frozen.exactCost().equals(current.exactCost())) return "revival-cost-changed";
        return null;
    }

    private static PaidCommandRevivalResult denied(String profileId, ResolvedRevival resolved) {
        PaidCommandRevivalResult.Status status = switch (resolved.status()) {
            case COOLDOWN -> PaidCommandRevivalResult.Status.COOLDOWN;
            case INSUFFICIENT_COST -> PaidCommandRevivalResult.Status.INSUFFICIENT_COST;
            case UNAVAILABLE -> PaidCommandRevivalResult.Status.UNAVAILABLE;
            default -> PaidCommandRevivalResult.Status.DENIED;
        };
        return new PaidCommandRevivalResult(null, status, profileId, resolved.exactCost(), resolved.reason(), false);
    }

    private static PaidCommandRevivalResult result(PaidCommandRevivalRecord record, boolean recovered) {
        PaidCommandRevivalResult.Status status = switch (record.state()) {
            case SUCCEEDED -> recovered ? PaidCommandRevivalResult.Status.ALREADY_REVIVED
                    : PaidCommandRevivalResult.Status.REVIVED;
            case REFUND_REQUIRED -> PaidCommandRevivalResult.Status.REFUND_PENDING;
            case REFUNDED -> PaidCommandRevivalResult.Status.REFUNDED;
            case CANCELED -> PaidCommandRevivalResult.Status.DENIED;
            case QUARANTINED, PREPARED, RESERVED, COST_CONSUMED, APPLYING ->
                    PaidCommandRevivalResult.Status.RECOVERY_PENDING;
        };
        return new PaidCommandRevivalResult(record.operationId(), status, record.profileId(),
                record.exactCost(), record.detail(), recovered);
    }

    private static PaidCommandRevivalOperationView view(PaidCommandRevivalRecord record) {
        return new PaidCommandRevivalOperationView(record.operationId(), record.callerNamespace(),
                record.idempotencyKey(), record.ownerUuid(), record.profileId(),
                PaidCommandRevivalOperationView.State.valueOf(record.state().name()), record.exactCost(),
                record.detail(), record.updatedAtMs());
    }

    private static <T> CompletionStage<T> committed(PersistenceWriteQueue.WriteSubmission<T> submission) {
        return submission.completion().thenApply(outcome -> outcome.isCommitted() ? outcome.value() : null);
    }

    private static CompletionStage<PaidCommandRevivalResult> completedUnavailable(String profileId, String reason) {
        return CompletableFuture.completedFuture(PaidCommandRevivalResult.unavailable(profileId, reason));
    }

    /** Runtime seam that keeps world-thread inventory and projection work out of the journal worker. */
    public interface Authority {
        @Nonnull CompletionStage<ResolvedRevival> resolve(@Nonnull UUID ownerUuid,
                                                          @Nonnull String profileId,
                                                          @Nonnull String commandFamilyId,
                                                          boolean forCommit);

        @Nonnull CommandReviveInventoryPaymentService.PlanResult plan(
                @Nonnull ResolvedRevival resolved, @Nonnull UUID operationId);

        @Nonnull CompletionStage<ActivationPreparation> prepareActivation(
                @Nonnull ResolvedRevival resolved, @Nonnull UUID operationId);

        @Nonnull CompletionStage<Boolean> cancelActivation(
                @Nonnull PaidCommandRevivalRecord operation, @Nullable Object runtimeHandle);

        @Nonnull CompletionStage<CommandReviveInventoryPaymentService.ConsumeResult> consume(
                @Nonnull ResolvedRevival resolved,
                @Nonnull UUID operationId,
                @Nonnull List<ItemCostComponentView> exactCost,
                @Nonnull List<PaidCommandRevivalRecord.Reservation> reservations);

        @Nonnull CompletionStage<CommandReviveInventoryPaymentService.ConsumeResult> hold(
                @Nonnull ResolvedRevival resolved, @Nonnull UUID operationId,
                @Nonnull List<PaidCommandRevivalRecord.Reservation> reservations);

        @Nonnull CompletionStage<CommandReviveInventoryPaymentService.ReceiptEvidence> inspectReservation(
                @Nonnull PaidCommandRevivalRecord operation);

        @Nonnull CompletionStage<Boolean> release(@Nonnull PaidCommandRevivalRecord operation);

        @Nonnull CompletionStage<ApplyOutcome> apply(
                @Nonnull ResolvedRevival resolved,
                @Nonnull PaidCommandRevivalRecord operation,
                @Nullable ActivationPreparation activation);

        @Nonnull CompletionStage<CommandReviveInventoryPaymentService.ConsumeResult> refund(
                @Nonnull PaidCommandRevivalRecord operation);

        @Nonnull CompletionStage<CommandReviveInventoryPaymentService.RefundEvidence> inspectRefundDelivery(
                @Nonnull PaidCommandRevivalRecord operation);

        @Nonnull CompletionStage<Boolean> clearRefundReceipt(@Nonnull PaidCommandRevivalRecord operation);

        @Nonnull CompletionStage<ProjectionEvidence> inspectProjection(
                @Nonnull PaidCommandRevivalRecord operation);
    }

    public record ResolvedRevival(@Nonnull String profileId,
                                  @Nonnull String roleId,
                                  @Nullable String configId,
                                  @Nonnull String configRevision,
                                  long deathRevision,
                                  long profileRevision,
                                  @Nonnull PaidCommandRevivalQuote.Status status,
                                  long cooldownRemainingMs,
                                  @Nonnull List<PaidCommandRevivalCostQuoteView> costQuote,
                                  @Nullable String messageKey,
                                  @Nullable String reason,
                                  @Nullable String populationAdmissionOperationId,
                                  @Nullable String placementFingerprint,
                                  @Nullable String reviveProjectionOperationId,
                                  @Nullable Object runtimeContext) {
        public ResolvedRevival {
            profileId = requireText(profileId, "profileId");
            roleId = requireText(roleId, "roleId");
            configRevision = requireText(configRevision, "configRevision");
            if (deathRevision < 0 || profileRevision < 0 || cooldownRemainingMs < 0) {
                throw new IllegalArgumentException("revisions and cooldown must be non-negative");
            }
            status = Objects.requireNonNull(status, "status");
            costQuote = List.copyOf(costQuote);
        }

        public List<ItemCostComponentView> exactCost() {
            ArrayList<ItemCostComponentView> costs = new ArrayList<>(costQuote.size());
            for (PaidCommandRevivalCostQuoteView line : costQuote) {
                costs.add(new ItemCostComponentView(line.itemId(), line.requiredQuantity()));
            }
            return List.copyOf(costs);
        }

        public boolean affordable() {
            return costQuote.stream().allMatch(PaidCommandRevivalCostQuoteView::satisfied);
        }

        PaidCommandRevivalQuote toQuote(UUID ownerUuid, String requestedProfileId, String commandFamilyId) {
            return new PaidCommandRevivalQuote(ownerUuid, requestedProfileId, commandFamilyId, status,
                    cooldownRemainingMs, costQuote, configRevision, messageKey, reason);
        }
    }

    public record ApplyOutcome(boolean succeeded, @Nullable String reason) {
        public static ApplyOutcome applied() { return new ApplyOutcome(true, null); }
        public static ApplyOutcome failed(String reason) { return new ApplyOutcome(false, reason); }
    }

    public record ActivationPreparation(boolean accepted,
                                        @Nullable String reason,
                                        @Nullable String populationOperationId,
                                        @Nullable Object runtimeHandle) {
        public static ActivationPreparation denied(@Nullable String reason) {
            return new ActivationPreparation(false, reason, null, null);
        }
    }

    public enum ProjectionEvidence { DEAD, REVIVED, AMBIGUOUS }

    public record RecoveryReport(boolean healthy, int examined, int recovered,
                                 int pending, @Nullable String reason) { }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
