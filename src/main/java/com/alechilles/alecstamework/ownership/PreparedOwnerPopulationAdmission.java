package com.alechilles.alecstamework.ownership;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Opaque mutation capability returned only after the operation journal is durable.
 */
public final class PreparedOwnerPopulationAdmission {
    enum State {
        PREPARED,
        APPLYING,
        COMMITTING,
        SOURCE_FINALIZATION_PENDING,
        SOURCE_FINALIZING,
        COMMITTED,
        CANCELED,
        DEGRADED
    }

    private final UUID operationId;
    private final OwnerPopulationAdmissionPlan plan;
    private final OwnerPopulationDecision decision;
    private final AtomicReference<State> state = new AtomicReference<>(State.PREPARED);
    @Nullable
    private CompletableFuture<Boolean> cancellationCompletion;
    @Nullable
    private CompletableFuture<Boolean> sourceFinalizationCompletion;

    PreparedOwnerPopulationAdmission(@Nonnull UUID operationId,
                                     @Nonnull OwnerPopulationAdmissionPlan plan,
                                     @Nonnull OwnerPopulationDecision decision) {
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.decision = Objects.requireNonNull(decision, "decision");
        if (!decision.allowed() || decision.reservation() == null) {
            throw new IllegalArgumentException("A prepared admission requires an allowed reservation.");
        }
    }

    @Nonnull
    public UUID operationId() {
        return operationId;
    }

    @Nonnull
    public OwnerPopulationDecision decision() {
        return decision;
    }

    public long settingsRevision() {
        return plan.settingsRevision();
    }

    @Nonnull
    OwnerPopulationAdmissionPlan plan() {
        return plan;
    }

    @Nonnull
    OwnerPopulationReservation reservation() {
        return decision.reservation();
    }

    boolean transition(State expected, State next) {
        return state.compareAndSet(expected, next);
    }

    void setState(State next) {
        state.set(next);
    }

    State state() {
        return state.get();
    }

    @Nullable
    synchronized CompletableFuture<Boolean> cancellationCompletion() {
        return cancellationCompletion;
    }

    synchronized void cancellationCompletion(@Nonnull CompletableFuture<Boolean> completion) {
        cancellationCompletion = Objects.requireNonNull(completion, "completion");
    }

    @Nullable
    synchronized CompletableFuture<Boolean> sourceFinalizationCompletion() {
        return sourceFinalizationCompletion;
    }

    synchronized void sourceFinalizationCompletion(@Nonnull CompletableFuture<Boolean> completion) {
        sourceFinalizationCompletion = Objects.requireNonNull(completion, "completion");
    }
}
