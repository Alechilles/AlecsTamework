package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.api.OwnerPopulationCapDecisionViewV2;
import com.alechilles.alecstamework.api.PopulationAdmissionToken;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationEngine;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;
import java.util.function.Function;
import javax.annotation.Nonnull;

/** Local, single-use proof that one async claim is still unused by the world. */
public final class PopulationAdmissionCancellationPermit {
    private final PopulationAdmissionToken token;
    private final long durableExpiresAtMs;
    private final AtomicReference<State> state =
            new AtomicReference<>(State.UNUSED);

    private PopulationAdmissionCancellationPermit(
            PopulationAdmissionToken token,
            long durableExpiresAtMs
    ) {
        this.token = Objects.requireNonNull(token, "token");
        this.durableExpiresAtMs = durableExpiresAtMs;
    }

    static PopulationAdmissionCancellationPermit issue(
            @Nonnull PopulationAdmissionToken token,
            long durableExpiresAtMs
    ) {
        return new PopulationAdmissionCancellationPermit(token, durableExpiresAtMs);
    }

    static CompletionStage<PopulationAdmissionCancellationPermit> claim(
            PopulationAdmissionToken token,
            OperationEnvelope operation,
            SqliteOperationEngine engine,
            LongSupplier clock,
            ConcurrentMap<UUID, PopulationAdmissionCancellationPermit> permits
    ) {
        PopulationDomainAdmissionOperation.Payload payload;
        try {
            payload = PopulationDomainAdmissionDefinition.INSTANCE.decode(
                    operation.payloadJson()
            );
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (operation.phase() != OperationPhase.PREPARED
                || !matches(token, operation, payload)
                || clock.getAsLong() >= payload.expiresAtMs()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "domain_admission_claim_invalid"
            ));
        }
        return engine.transition(
                operation, OperationPhase.LIVE_APPLYING, null, null,
                clock.getAsLong()
        ).completion().thenCompose(result ->
                PopulationDomainAdmissionOperationSupport.committedValue(
                        result, "domain_admission_claim"
                )
        ).thenApply(ignored -> {
            PopulationAdmissionCancellationPermit permit = issue(
                    token, payload.expiresAtMs()
            );
            permits.put(token.operationId(), permit);
            return permit;
        });
    }

    static boolean acquire(
            PopulationAdmissionCancellationPermit permit,
            long nowMs,
            ConcurrentMap<UUID, PopulationAdmissionCancellationPermit> permits
    ) {
        return permit != null
                && permits.get(permit.token.operationId()) == permit
                && permit.beginCancellation(nowMs);
    }

    public static boolean reusable(
            PopulationAdmissionToken token,
            boolean applying,
            boolean settling,
            OperationEnvelope operation,
            PopulationDomainAdmissionOperation.Payload payload,
            boolean payloadEquivalent,
            long wallNowMs,
            long monotonicNow
    ) {
        return token != null && !applying && !settling
                && token.operationId().equals(operation.operationId().value())
                && token.reservationId().equals(payload.reservationId())
                && token.settingsRevision() == payload.managedConfigRevision()
                && token.providerGenerationToken().equals(
                        payload.providerGenerationToken()
                )
                && token.readiness() == OwnerPopulationCapDecisionViewV2.Readiness.READY
                && payloadEquivalent && wallNowMs < payload.expiresAtMs()
                && monotonicNow < token.expiresAtMonotonicNanos();
    }

    static CompletionStage<PopulationDomainAdmissionOperation.OperationWorkflow> cancel(
            PopulationAdmissionCancellationPermit permit,
            long nowMs,
            ConcurrentMap<UUID, PopulationAdmissionCancellationPermit> permits,
            Function<OperationId, CompletionStage<PopulationDomainAdmissionOperation.OperationWorkflow>> durable
    ) {
        if (!acquire(permit, nowMs, permits)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "domain_admission_cancellation_permit_invalid"
            ));
        }
        return durable.apply(new OperationId(permit.token.operationId()))
                .whenComplete((workflow, failure) -> {
                    boolean published = failure == null && workflow != null
                            && workflow.result() != null
                            && workflow.result().status()
                            == OperationWorkflowResult.Status.PUBLISHED;
                    if (published) {
                        permit.finishCancellation();
                        permits.remove(permit.token.operationId(), permit);
                    } else {
                        permit.retryCancellation();
                    }
                });
    }

    static CompletionStage<OperationWorkflowResult> evictAfterVerifiedContainment(
            CompletionStage<OperationWorkflowResult> result,
            OperationId operationId,
            ConcurrentMap<UUID, PopulationAdmissionCancellationPermit> permits
    ) {
        return result.thenApply(value -> {
            if (value.status() == OperationWorkflowResult.Status.LIVE_UNKNOWN) {
                permits.remove(operationId.value());
            }
            return value;
        });
    }

    private static boolean matches(
            PopulationAdmissionToken token,
            OperationEnvelope operation,
            PopulationDomainAdmissionOperation.Payload payload
    ) {
        return operation.operationId().value().equals(token.operationId())
                && payload.reservationId().equals(token.reservationId())
                && payload.managedConfigRevision() == token.settingsRevision()
                && payload.providerGenerationToken().equals(
                        token.providerGenerationToken()
                )
                && token.readiness() == OwnerPopulationCapDecisionViewV2.Readiness.READY;
    }

    /** Invalidates cancellation before a caller is allowed to start world work. */
    public boolean markWorldApplyStarted() {
        return state.compareAndSet(State.UNUSED, State.APPLYING);
    }

    /** Restores the permit when the matching local active-token CAS loses a race. */
    public void retryWorldApplyStart() {
        state.compareAndSet(State.APPLYING, State.UNUSED);
    }

    private boolean beginCancellation(long nowMs) {
        return nowMs < durableExpiresAtMs
                && state.compareAndSet(State.UNUSED, State.SETTLING);
    }

    private void finishCancellation() {
        state.compareAndSet(State.SETTLING, State.CONSUMED);
    }

    private void retryCancellation() {
        state.compareAndSet(State.SETTLING, State.UNUSED);
    }

    PopulationAdmissionToken token() {
        return token;
    }

    private enum State {
        UNUSED,
        APPLYING,
        SETTLING,
        CONSUMED
    }
}
