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
                resolved.populationAdmissionOperationId(), resolved.placementFingerprint(),
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
            CommandReviveInventoryPaymentService.PlanResult plan = authority.plan(resolved, operationId);
            if (!plan.ready()) {
                return cancel(prepared, plan.status() == CommandReviveInventoryPaymentService.Status.INSUFFICIENT
                        ? "insufficient-revival-cost" : "inventory-reservation-unavailable");
            }
            return committed(repository.reserveAsync(operationId, plan.reservations(), clock.millis()))
                    .thenCompose(reserved -> revalidateAndConsume(resolved, reserved));
        });
    }

    private CompletionStage<PaidCommandRevivalResult> revalidateAndConsume(
            ResolvedRevival frozen,
            @Nullable PaidCommandRevivalRepository.MutationResult reservedResult) {
        if (reservedResult == null || reservedResult.operation() == null
                || (reservedResult.status() != PaidCommandRevivalRepository.Status.APPLIED
                && reservedResult.status() != PaidCommandRevivalRepository.Status.IDEMPOTENT)) {
            return completedUnavailable(frozen.profileId(), "inventory-reservation-persist-failed");
        }
        PaidCommandRevivalRecord operation = reservedResult.operation();
        return authority.resolve(operation.ownerUuid(), operation.profileId(), operation.commandFamilyId(), true)
                .thenCompose(current -> {
                    String staleReason = frozenMismatch(frozen, current);
                    if (staleReason != null) return cancel(operation, staleReason);
                    return authority.consume(current, operation.exactCost(), operation.reservations())
                            .thenCompose(consumed -> {
                                if (!consumed.succeeded()) return cancel(operation,
                                        consumed.reason() == null ? "inventory-consumption-failed" : consumed.reason());
                                return committed(repository.transitionAsync(operation.operationId(),
                                        PaidCommandRevivalRecord.State.RESERVED,
                                        PaidCommandRevivalRecord.State.COST_CONSUMED,
                                        null, clock.millis())).thenCompose(costCommitted -> {
                                    if (costCommitted == null || costCommitted.operation() == null) {
                                        return quarantineAfterAmbiguousCharge(operation,
                                                "cost-consumed-journal-commit-ambiguous");
                                    }
                                    return apply(current, costCommitted.operation(), false);
                                });
                            });
                });
    }

    private CompletionStage<PaidCommandRevivalResult> apply(ResolvedRevival context,
                                                             PaidCommandRevivalRecord operation,
                                                             boolean recovered) {
        return committed(repository.transitionAsync(operation.operationId(),
                PaidCommandRevivalRecord.State.COST_CONSUMED,
                PaidCommandRevivalRecord.State.APPLYING, null, clock.millis())).thenCompose(applying -> {
            if (applying == null || applying.operation() == null) {
                return requireRefund(operation, context, "revival-apply-journal-failed");
            }
            return authority.apply(context, operation.operationId()).thenCompose(outcome -> {
                if (!outcome.succeeded()) {
                    return requireRefund(applying.operation(), context,
                            outcome.reason() == null ? "revival-apply-failed" : outcome.reason());
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
        PaidCommandRevivalRecord.State expected = operation.state() == PaidCommandRevivalRecord.State.PREPARED
                ? PaidCommandRevivalRecord.State.PREPARED : PaidCommandRevivalRecord.State.RESERVED;
        return committed(repository.transitionAsync(operation.operationId(), expected,
                PaidCommandRevivalRecord.State.CANCELED, reason, clock.millis())).thenApply(ignored ->
                new PaidCommandRevivalResult(operation.operationId(),
                        reason.contains("insufficient")
                                ? PaidCommandRevivalResult.Status.INSUFFICIENT_COST
                                : PaidCommandRevivalResult.Status.DENIED,
                        operation.profileId(), operation.exactCost(), reason, false));
    }

    private CompletionStage<PaidCommandRevivalResult> requireRefund(
            PaidCommandRevivalRecord operation, ResolvedRevival context, String reason) {
        return committed(repository.transitionAsync(operation.operationId(), operation.state(),
                PaidCommandRevivalRecord.State.REFUND_REQUIRED, reason, clock.millis())).thenCompose(required -> {
            PaidCommandRevivalRecord marked = required != null ? required.operation() : null;
            if (marked == null) return quarantineAfterAmbiguousCharge(operation, reason);
            return authority.refund(context, operation.exactCost()).thenCompose(refund -> {
                if (!refund.succeeded()) {
                    return CompletableFuture.completedFuture(new PaidCommandRevivalResult(
                            operation.operationId(), PaidCommandRevivalResult.Status.REFUND_PENDING,
                            operation.profileId(), operation.exactCost(), refund.reason(), false));
                }
                return committed(repository.transitionAsync(operation.operationId(),
                        PaidCommandRevivalRecord.State.REFUND_REQUIRED,
                        PaidCommandRevivalRecord.State.REFUNDED, reason, clock.millis())).thenApply(done ->
                        new PaidCommandRevivalResult(operation.operationId(),
                                PaidCommandRevivalResult.Status.REFUNDED, operation.profileId(),
                                operation.exactCost(), reason, false));
            });
        });
    }

    private CompletionStage<PaidCommandRevivalResult> quarantineAfterAmbiguousCharge(
            PaidCommandRevivalRecord operation, String reason) {
        PaidCommandRevivalRecord.State expected = operation.state();
        if (expected == PaidCommandRevivalRecord.State.PREPARED) expected = PaidCommandRevivalRecord.State.RESERVED;
        return committed(repository.transitionAsync(operation.operationId(), expected,
                PaidCommandRevivalRecord.State.QUARANTINED, reason, clock.millis())).handle((ignored, failure) ->
                new PaidCommandRevivalResult(operation.operationId(),
                        PaidCommandRevivalResult.Status.RECOVERY_PENDING, operation.profileId(),
                        operation.exactCost(), reason, true));
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

        @Nonnull CompletionStage<CommandReviveInventoryPaymentService.ConsumeResult> consume(
                @Nonnull ResolvedRevival resolved,
                @Nonnull List<ItemCostComponentView> exactCost,
                @Nonnull List<PaidCommandRevivalRecord.Reservation> reservations);

        @Nonnull CompletionStage<ApplyOutcome> apply(@Nonnull ResolvedRevival resolved,
                                                     @Nonnull UUID operationId);

        @Nonnull CompletionStage<CommandReviveInventoryPaymentService.ConsumeResult> refund(
                @Nonnull ResolvedRevival resolved, @Nonnull List<ItemCostComponentView> exactCost);
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

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
