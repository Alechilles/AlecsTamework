package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.OwnerPopulationCapDecisionViewV2;
import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV3;
import com.alechilles.alecstamework.api.PopulationAdmissionToken;
import com.alechilles.alecstamework.companion.population.domain.ManagedAdmissionEvidenceAuthor;
import com.alechilles.alecstamework.companion.population.domain.ManagedBatchAdmissionRequest;
import com.alechilles.alecstamework.companion.population.domain.ManagedBatchSettlement;
import com.alechilles.alecstamework.companion.population.domain.PopulationAdmissionComposition;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionDefinition;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionOperation;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/** Owns staged admission tokens and their durable idempotency lifecycle. */
final class PopulationAdmissionStaging {
    private static final long TOKEN_TTL_NANOS = 30_000_000_000L;

    private final PopulationDomainAdmissionOperation operations;
    private final LongSupplier wallClock;
    private final LongSupplier monotonicClock;
    private final ConcurrentMap<UUID, ActiveToken> active =
            new ConcurrentHashMap<>();
    private final PopulationAdmissionSettlementFlights<PopulationAdmissionDecision>
            settlementFlights = new PopulationAdmissionSettlementFlights<>();
    private final PopulationAdmissionBatchStaging batches;
    PopulationAdmissionStaging(
            PopulationDomainAdmissionOperation operations
    ) {
        this(operations, System::nanoTime);
    }
    PopulationAdmissionStaging(
            PopulationDomainAdmissionOperation operations,
            LongSupplier clock
    ) {
        this(operations, clock, System::nanoTime);
    }
    PopulationAdmissionStaging(
            PopulationDomainAdmissionOperation operations,
            LongSupplier wallClock,
            LongSupplier monotonicClock
    ) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
        this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
        this.batches = new PopulationAdmissionBatchStaging(
                operations, active, wallClock, monotonicClock
        );
    }
    Identity identity(PopulationAdmissionRequestV3 request) {
        String supplied = request.request().request().identity().idempotencyKey();
        if (supplied == null || supplied.isBlank()) {
            UUID random = UUID.randomUUID();
            return new Identity(random, random, "population-domain:" + random);
        }
        String key = "population-domain:" + supplied.trim();
        UUID operation = UUID.nameUUIDFromBytes(
                (key + ":operation").getBytes(StandardCharsets.UTF_8)
        );
        UUID reservation = UUID.nameUUIDFromBytes(
                (key + ":reservation").getBytes(StandardCharsets.UTF_8)
        );
        return new Identity(operation, reservation, key);
    }
    Identity batchIdentity(UUID litterOperationId) {
        Objects.requireNonNull(litterOperationId, "litterOperationId");
        String key = "population-domain:litter:" + litterOperationId;
        return new Identity(
                litterOperationId,
                UUID.nameUUIDFromBytes(
                        (litterOperationId + ":reservation")
                                .getBytes(StandardCharsets.UTF_8)
                ),
                key
        );
    }
    CompletionStage<PopulationAdmissionDecision> prepareOrReuse(
            Identity identity,
            ManagedAdmissionEvidenceAuthor.Authoring evidence
    ) {
        return prepareOrReuse(identity, evidence, null);
    }
    CompletionStage<PopulationAdmissionDecision> prepareOrReuse(
            Identity identity,
            ManagedAdmissionEvidenceAuthor.Authoring evidence,
            PopulationAdmissionComposition composition
    ) {
        OperationId operationId = new OperationId(identity.operationId());
        IdempotencyKey idempotencyKey = new IdempotencyKey(
                identity.idempotencyKey()
        );
        return operations.findByIdempotency(idempotencyKey)
                .thenCompose(existing -> existing.isPresent()
                        ? reuseExisting(existing.orElseThrow(), evidence, composition)
                        : prepareNew(operationId, idempotencyKey,
                                identity.reservationId(), evidence, composition));
    }

    CompletionStage<PopulationAdmissionDecision> prepareBatch(
            ManagedBatchAdmissionRequest batch,
            ManagedAdmissionEvidenceAuthor author,
            CompanionLifecycle source,
            PopulationAdmissionCompositionAuthor compositionAuthor
    ) {
        return batches.prepare(batch, author, this, source, compositionAuthor);
    }

    CompletionStage<ManagedBatchSettlement> settleBatch(
            PopulationAdmissionToken token,
            Set<Integer> ordinals,
            Map<Integer, UUID> actualChildIds
    ) {
        return batches.settle(token, ordinals, actualChildIds);
    }

    PopulationAdmissionDecision claimForApply(
            PopulationAdmissionToken token
    ) {
        ActiveToken current = active.get(token.operationId());
        if (current == null || !current.token().equals(token)
                || current.applying()
                || current.settling()
                || monotonicClock.getAsLong() >= token.expiresAtMonotonicNanos()) {
            return PopulationAdmissionDecision.unavailable(
                    "population-admission-token-invalid"
            );
        }
        if (!active.replace(token.operationId(), current, current.asApplying())) {
            return PopulationAdmissionDecision.unavailable(
                    "population-admission-token-invalid"
            );
        }
        return new PopulationAdmissionDecision(
                PopulationAdmissionDecision.Status.APPLYING,
                "population-admission-applying",
                token,
                OwnerPopulationCapDecisionViewV2.Readiness.READY,
                0,
                1
        );
    }

    CompletionStage<PopulationAdmissionDecision> settle(
            PopulationAdmissionToken token,
            boolean canceled
    ) {
        ActiveToken current = active.get(token.operationId());
        if (current == null || !current.token().equals(token)) {
            return operations.settlementEvidence(new OperationId(token.operationId()))
                    .thenApply(evidence -> replayDecision(token, evidence))
                    .exceptionally(failure -> PopulationAdmissionDecision.unavailable(
                            "population-admission-token-invalid"
                    ));
        }
        if (current.settling()) {
            CompletableFuture<PopulationAdmissionDecision> flight = settlementFlights.get(
                    token.operationId()
            );
            if (flight != null) {
                return flight;
            }
            return operations.settlementEvidence(new OperationId(token.operationId()))
                    .thenApply(evidence -> replayDecision(token, evidence))
                    .exceptionally(failure -> PopulationAdmissionDecision.unavailable(
                            "population-admission-settlement-pending"
                    ));
        }
        if ((!canceled && !current.applying())
                || (canceled && current.applying())
                || (!canceled
                && monotonicClock.getAsLong() >= token.expiresAtMonotonicNanos())) {
            return CompletableFuture.completedFuture(
                    PopulationAdmissionDecision.unavailable(
                            "population-admission-token-not-applying"
                    )
            );
        }
        ActiveToken settling = current.asSettling();
        PopulationAdmissionSettlementFlights.Lease<PopulationAdmissionDecision> lease =
                settlementFlights.acquire(token.operationId());
        if (lease.saturated()) {
            return CompletableFuture.completedFuture(
                    PopulationAdmissionDecision.unavailable(
                            "population-admission-settlement-busy"
                    )
            );
        }
        if (!lease.owner()) {
            return lease.future();
        }
        CompletableFuture<PopulationAdmissionDecision> flight = lease.future();
        if (!active.replace(token.operationId(), current, settling)) {
            PopulationAdmissionDecision decision = PopulationAdmissionDecision.unavailable(
                    "population-admission-token-invalid"
            );
            settlementFlights.complete(token.operationId(), flight, decision);
            return CompletableFuture.completedFuture(
                    decision
            );
        }
        CompletionStage<PopulationDomainAdmissionOperation.OperationWorkflow> settlement =
                canceled && !current.applying()
                        ? operations.cancelPreclaimed(new OperationId(token.operationId()))
                        : operations.commit(new OperationId(token.operationId()), canceled);
        return settlement
                .thenCompose(result -> {
                    if (result == null || result.result() == null
                            || result.result().status()
                            != com.alechilles.alecstamework.persistence.operation
                            .OperationWorkflowResult.Status.PUBLISHED) {
                        throw new IllegalStateException(
                                "population-admission-publication-pending"
                        );
                    }
                    return operations.settlementEvidence(
                            new OperationId(token.operationId())
                    );
                })
                .thenApply(evidence -> {
                    PopulationAdmissionDecision decision = replayDecision(
                            token, evidence
                    );
                    active.remove(token.operationId(), settling);
                    settlementFlights.complete(token.operationId(), flight, decision);
                    return decision;
                })
                .exceptionally(failure -> {
                    active.replace(token.operationId(), settling, current);
                    PopulationAdmissionDecision decision = PopulationAdmissionDecision.unavailable(
                            "population-admission-settlement-failed"
                    );
                    settlementFlights.complete(token.operationId(), flight, decision);
                    return decision;
                });
    }

    CompletionStage<Integer> cleanupExpired() {
        long now = monotonicClock.getAsLong();
        java.util.ArrayList<CompletionStage<PopulationAdmissionDecision>> pending =
                new java.util.ArrayList<>();
        for (ActiveToken value : active.values()) {
            if (now >= value.token().expiresAtMonotonicNanos()) {
                if (value.applying()) {
                    pending.add(operations.containExpiredClaim(
                            new OperationId(value.token().operationId())
                    ).thenApply(result -> {
                        if (result != null
                                && result.status()
                                == OperationWorkflowResult.Status.LIVE_UNKNOWN) {
                            active.remove(value.token().operationId(), value);
                            return PopulationAdmissionDecision.unavailable(
                                    "population-admission-live-effect-contained"
                            );
                        }
                        return PopulationAdmissionDecision.unavailable(
                                "population-admission-live-effect-containment-pending"
                        );
                    }).exceptionally(failure -> PopulationAdmissionDecision.unavailable(
                            "population-admission-live-effect-containment-pending"
                    )));
                } else {
                    pending.add(settle(value.token(), true));
                }
            }
        }
        if (pending.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        return CompletableFuture.allOf(pending.stream()
                        .map(CompletionStage::toCompletableFuture)
                        .toArray(CompletableFuture[]::new))
                .thenApply(ignored -> pending.size());
    }

    private CompletionStage<PopulationAdmissionDecision> prepareNew(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            UUID reservationId,
            ManagedAdmissionEvidenceAuthor.Authoring evidence,
            PopulationAdmissionComposition composition
    ) {
        return operations.prepare(
                operationId,
                idempotencyKey,
                evidence.payload(),
                composition
        ).completion().thenCompose(result -> {
            OperationEnvelope envelope = envelope(result,
                    "population-admission-prepare-failed");
            if (!staged(envelope)) {
                throw new IllegalStateException(
                        "population-admission-already-settled"
                );
            }
            PopulationAdmissionToken token = token(
                    reservationId,
                    envelope.operationId(),
                    evidence.readiness().configRevision(),
                    evidence.providerReadiness().generationToken()
            );
            return preclaim(envelope, token, evidence);
        });
    }

    private CompletionStage<PopulationAdmissionDecision> reuseExisting(
            OperationEnvelope envelope,
            ManagedAdmissionEvidenceAuthor.Authoring evidence,
            PopulationAdmissionComposition composition
    ) {
        PopulationDomainAdmissionOperation.Payload stored =
                PopulationDomainAdmissionDefinition.INSTANCE.decode(
                        envelope.payloadJson()
                );
        if (!PopulationAdmissionPayloadMatcher.sameExceptTimes(
                stored, evidence.payload()
        )) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "population-admission-idempotency-conflict"
                    )
            );
        }
        PopulationAdmissionToken replayToken = token(
                stored.reservationId(),
                envelope.operationId(),
                stored.managedConfigRevision(),
                stored.providerGenerationToken()
        );
        if (envelope.phase() == OperationPhase.DURABLE
                || envelope.phase() == OperationPhase.PUBLISHED) {
            return operations.settlementEvidence(envelope.operationId())
                    .thenApply(settlement -> replayDecision(replayToken, settlement));
        }
        if (envelope.phase() == OperationPhase.LIVE_APPLYING) {
            ActiveToken local = active.get(envelope.operationId().value());
            if (local != null && local.token().equals(replayToken)
                    && !local.applying() && !local.settling()) {
                return CompletableFuture.completedFuture(reserved(replayToken));
            }
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "population-admission-live-effect-contained"
                    )
                );
        }
        if (!staged(envelope)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "population-admission-already-settled"
                    )
            );
        }
        if (wallClock.getAsLong() >= stored.expiresAtMs()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("population-admission-expired")
            );
        }
        return operations.prepare(
                envelope.operationId(),
                envelope.idempotencyKey(),
                stored,
                composition
        ).completion().thenCompose(result -> {
            OperationEnvelope refreshed = envelope(result,
                    "population-admission-already-settled");
            if (!staged(refreshed)) {
                throw new IllegalStateException(
                        "population-admission-already-settled"
                );
            }
            PopulationAdmissionToken token = token(
                    stored.reservationId(), refreshed.operationId(),
                    stored.managedConfigRevision(), stored.providerGenerationToken()
            );
            return preclaim(refreshed, token, evidence);
        });
    }

    private CompletionStage<PopulationAdmissionDecision> preclaim(
            OperationEnvelope envelope,
            PopulationAdmissionToken token,
            ManagedAdmissionEvidenceAuthor.Authoring evidence
    ) {
        return operations.claim(envelope.operationId()).thenApply(claimed -> {
            if (claimed == null || claimed.phase() != OperationPhase.LIVE_APPLYING) {
                throw new IllegalStateException(
                        "population-admission-preclaim-failed"
                );
            }
            active.put(envelope.operationId().value(),
                    new ActiveToken(token, evidence));
            return reserved(token);
        });
    }

    private OperationEnvelope envelope(
            PersistenceTransactionResult<?> result,
            String failure
    ) {
        if (result instanceof PersistenceTransactionResult.Committed<?> committed
                && committed.value() instanceof OperationEnvelope envelope) {
            return envelope;
        }
        throw new IllegalStateException(failure);
    }

    private boolean staged(OperationEnvelope envelope) {
        return envelope.phase() == OperationPhase.PREPARED;
    }

    private PopulationAdmissionDecision reserved(
            PopulationAdmissionToken token
    ) {
        return new PopulationAdmissionDecision(
                PopulationAdmissionDecision.Status.RESERVED,
                "population-admission-reserved",
                token,
                OwnerPopulationCapDecisionViewV2.Readiness.READY,
                0,
                1
        );
    }

    private PopulationAdmissionToken token(
            UUID reservationId,
            OperationId operationId,
            long settingsRevision,
            String providerGenerationToken
    ) {
        return new PopulationAdmissionToken(
                operationId.value(),
                reservationId,
                monotonicClock.getAsLong() + TOKEN_TTL_NANOS,
                settingsRevision,
                providerGenerationToken,
                OwnerPopulationCapDecisionViewV2.Readiness.READY
        );
    }

    record Identity(UUID operationId, UUID reservationId, String idempotencyKey) {}

    record ActiveToken(
            PopulationAdmissionToken token,
            ManagedAdmissionEvidenceAuthor.Authoring evidence,
            boolean applying,
            boolean settling
    ) {
        private ActiveToken(
                PopulationAdmissionToken token,
                ManagedAdmissionEvidenceAuthor.Authoring evidence
        ) {
            this(token, evidence, false, false);
        }

        ActiveToken asApplying() {
            return new ActiveToken(token, evidence, true, false);
        }

        ActiveToken asSettling() {
            return new ActiveToken(token, evidence, applying, true);
        }
    }

    private PopulationAdmissionDecision replayDecision(
            PopulationAdmissionToken token,
            PopulationDomainAdmissionOperation.SettlementEvidence evidence
    ) {
        return new PopulationAdmissionDecision(
                evidence.canceled()
                        ? PopulationAdmissionDecision.Status.CANCELED
                        : PopulationAdmissionDecision.Status.COMMITTED,
                evidence.canceled()
                        ? "population-admission-canceled"
                        : "population-admission-committed",
                evidence.canceled() ? null : token,
                OwnerPopulationCapDecisionViewV2.Readiness.READY,
                0,
                0
        );
    }

}
