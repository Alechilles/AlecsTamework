package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.OwnerPopulationCapDecisionViewV2;
import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionToken;
import com.alechilles.alecstamework.companion.population.domain.ManagedAdmissionEvidenceAuthor;
import com.alechilles.alecstamework.companion.population.domain.ManagedBatchAdmissionRequest;
import com.alechilles.alecstamework.companion.population.domain.ManagedBatchSettlement;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionOperation;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/** Stages the private aggregate litter contract over one durable admission. */
final class PopulationAdmissionBatchStaging {
    private final PopulationDomainAdmissionOperation operations;
    private final ConcurrentMap<UUID, PopulationAdmissionStaging.ActiveToken> active;
    private final LongSupplier wallClock;
    private final LongSupplier monotonicClock;
    private final PopulationAdmissionSettlementFlights<ManagedBatchSettlement>
            settlementFlights = new PopulationAdmissionSettlementFlights<>();

    PopulationAdmissionBatchStaging(
            PopulationDomainAdmissionOperation operations,
            ConcurrentMap<UUID, PopulationAdmissionStaging.ActiveToken> active
    ) {
        this(operations, active, System::currentTimeMillis, System::nanoTime);
    }

    PopulationAdmissionBatchStaging(
            PopulationDomainAdmissionOperation operations,
            ConcurrentMap<UUID, PopulationAdmissionStaging.ActiveToken> active,
            LongSupplier wallClock,
            LongSupplier monotonicClock
    ) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.active = Objects.requireNonNull(active, "active");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
        this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
    }

    CompletionStage<PopulationAdmissionDecision> prepare(
            ManagedBatchAdmissionRequest batch,
            ManagedAdmissionEvidenceAuthor author,
            PopulationAdmissionStaging staging,
            CompanionLifecycle source,
            PopulationAdmissionCompositionAuthor compositionAuthor
    ) {
        PopulationAdmissionStaging.Identity identity = staging.batchIdentity(
                batch.litterOperationId()
        );
        return author.authorBatch(
                        batch,
                        source == null ? null : source.state(),
                        source == null ? null : source.ownerId(),
                        source == null ? null : source.ownerWorldKey()
                )
                .thenCompose(evidence -> compositionAuthor.compose(
                        batch.admission(), source, evidence.payload(),
                        new OperationId(batch.litterOperationId())
                ).thenCompose(composition -> staging.prepareOrReuse(
                        identity, evidence, composition
                )));
    }

    CompletionStage<ManagedBatchSettlement> settle(
            PopulationAdmissionToken token,
            Set<Integer> ordinals,
            Map<Integer, UUID> actualChildIds
    ) {
        PopulationAdmissionStaging.ActiveToken current = active.get(
                token.operationId()
        );
        if (current != null && current.token().equals(token)
                && current.settling()) {
            CompletableFuture<ManagedBatchSettlement> flight = settlementFlights.get(
                    token.operationId()
            );
            if (flight != null) {
                return flight;
            }
        }
        if (current == null || !current.token().equals(token)
                || !current.applying()) {
            if (current == null) {
                return settleRestarted(token, ordinals, actualChildIds);
            }
            return operations.settlementEvidence(new OperationId(token.operationId()))
                    .thenApply(evidence -> batchSettlement(
                            current == null && evidence.requestedUnits() > 0
                                    ? evidence.requestedUnits()
                                    : current == null ? ordinals.size()
                                    : current.evidence().payload().requestedCount(),
                            evidence
                    )).exceptionally(failure -> unavailableBatch(
                            "population-admission-batch-token-invalid",
                            current == null ? ordinals.size()
                                    : current.evidence().payload().requestedCount()
                    ));
        }
        try {
            validate(current.evidence().payload(), token.operationId(), ordinals,
                    actualChildIds);
        } catch (IllegalArgumentException invalid) {
            return CompletableFuture.completedFuture(unavailableBatch(
                    "population-admission-batch-receipts-invalid",
                    current.evidence().payload().requestedCount()
            ));
        }
        PopulationAdmissionSettlementFlights.Lease<ManagedBatchSettlement> lease =
                settlementFlights.acquire(token.operationId());
        if (lease.saturated()) {
            return CompletableFuture.completedFuture(unavailableBatch(
                    "population-admission-batch-settlement-busy",
                    current.evidence().payload().requestedCount()
            ));
        }
        if (!lease.owner()) {
            return lease.future();
        }
        CompletableFuture<ManagedBatchSettlement> flight = lease.future();
        PopulationAdmissionStaging.ActiveToken settling = current.asSettling();
        if (!active.replace(token.operationId(), current, settling)) {
            ManagedBatchSettlement unavailable = unavailableBatch(
                    "population-admission-batch-token-invalid",
                    current.evidence().payload().requestedCount()
            );
            settlementFlights.complete(token.operationId(), flight, unavailable);
            return CompletableFuture.completedFuture(unavailable);
        }
        return operations.commitBatch(
                        new OperationId(token.operationId()),
                        ordinals,
                        actualChildIds
                )
                .thenCompose(result -> {
                    if (result == null || result.result() == null
                            || result.result().status()
                            != com.alechilles.alecstamework.persistence.operation
                            .OperationWorkflowResult.Status.PUBLISHED) {
                        throw new IllegalStateException(
                                "population-admission-batch-publication-pending"
                        );
                    }
                    return operations.settlementEvidence(
                            new OperationId(token.operationId())
                    );
                })
                .thenApply(evidence -> {
                    ManagedBatchSettlement settlement = batchSettlement(
                            current.evidence().payload().requestedCount(), evidence
                    );
                    active.remove(token.operationId(), settling);
                    settlementFlights.complete(token.operationId(), flight, settlement);
                    return settlement;
                })
                .exceptionally(failure -> {
                    active.replace(token.operationId(), settling, current);
                    ManagedBatchSettlement unavailable = unavailableBatch(
                            "population-admission-batch-settlement-failed",
                            current.evidence().payload().requestedCount()
                    );
                    settlementFlights.complete(token.operationId(), flight, unavailable);
                    return unavailable;
                });
    }

    private CompletionStage<ManagedBatchSettlement> settleRestarted(
            PopulationAdmissionToken token,
            Set<Integer> ordinals,
            Map<Integer, UUID> actualChildIds
    ) {
        PopulationAdmissionSettlementFlights.Lease<ManagedBatchSettlement> lease =
                settlementFlights.acquire(token.operationId());
        if (lease.saturated()) {
            return CompletableFuture.completedFuture(unavailableBatch(
                    "population-admission-batch-settlement-busy",
                    Math.max(1, ordinals == null ? 1 : ordinals.size())
            ));
        }
        if (!lease.owner()) {
            return lease.future();
        }
        CompletableFuture<ManagedBatchSettlement> flight = lease.future();
        CompletionStage<ManagedBatchSettlement> result = settleRestartedWork(
                token, ordinals, actualChildIds
        );
        result.whenComplete((value, failure) -> {
            if (failure == null) {
                flight.complete(value);
            } else {
                flight.completeExceptionally(failure);
            }
        });
        return result;
    }

    private CompletionStage<ManagedBatchSettlement> settleRestartedWork(
            PopulationAdmissionToken token,
            Set<Integer> ordinals,
            Map<Integer, UUID> actualChildIds
    ) {
        return operations.batchSettlementAuthority(
                        new OperationId(token.operationId())
                )
                .thenCompose(authority -> {
                    OperationEnvelope operation = authority.operation();
                    PopulationDomainAdmissionOperation.Payload payload =
                            authority.payload();
                    if (!tokenMatches(token, authority,
                            operation.phase() == OperationPhase.LIVE_APPLYING)) {
                        return CompletableFuture.completedFuture(unavailableBatch(
                                "population-admission-batch-token-invalid",
                                payload.requestedCount()
                        ));
                    }
                    if (operation.phase() == OperationPhase.DURABLE
                            || operation.phase() == OperationPhase.PUBLISHED) {
                        return operations.settlementEvidence(operation.operationId())
                                .thenApply(evidence -> batchSettlement(
                                        evidence.requestedUnits() > 0
                                                ? evidence.requestedUnits()
                                                : payload.requestedCount(),
                                        evidence
                                ));
                    }
                    if (operation.phase() != OperationPhase.LIVE_APPLYING) {
                        return CompletableFuture.completedFuture(unavailableBatch(
                                "population-admission-batch-token-invalid",
                                payload.requestedCount()
                        ));
                    }
                    try {
                        validate(payload, token.operationId(), ordinals, actualChildIds);
                    } catch (IllegalArgumentException invalid) {
                        return CompletableFuture.completedFuture(unavailableBatch(
                                "population-admission-batch-receipts-invalid",
                                payload.requestedCount()
                        ));
                    }
                    return commitAndRead(token, payload.requestedCount(),
                            ordinals, actualChildIds);
                })
                .exceptionally(failure -> unavailableBatch(
                        "population-admission-batch-settlement-failed",
                        Math.max(1, ordinals == null ? 1 : ordinals.size())
                ));
    }

    private boolean tokenMatches(
            PopulationAdmissionToken token,
            PopulationDomainAdmissionOperation.BatchSettlementAuthority authority,
            boolean live
    ) {
        OperationEnvelope operation = authority.operation();
        PopulationDomainAdmissionOperation.Payload payload = authority.payload();
        return operation.operationId().value().equals(token.operationId())
                && payload.reservationId().equals(token.reservationId())
                && payload.managedConfigRevision() == token.settingsRevision()
                && payload.providerGenerationToken().equals(
                        token.providerGenerationToken()
                )
                && token.readiness()
                == OwnerPopulationCapDecisionViewV2.Readiness.READY;
    }

    private CompletionStage<ManagedBatchSettlement> commitAndRead(
            PopulationAdmissionToken token,
            int requestedUnits,
            Set<Integer> ordinals,
            Map<Integer, UUID> actualChildIds
    ) {
        return operations.commitBatch(
                        new OperationId(token.operationId()),
                        ordinals,
                        actualChildIds
                )
                .thenCompose(workflow -> {
                    if (workflow == null || workflow.result() == null
                            || workflow.result().status()
                            != OperationWorkflowResult.Status.PUBLISHED) {
                        throw new IllegalStateException(
                                "population-admission-batch-publication-pending"
                        );
                    }
                    return operations.settlementEvidence(
                            new OperationId(token.operationId())
                    );
                })
                .thenApply(evidence -> batchSettlement(requestedUnits, evidence));
    }

    private void validate(
            PopulationDomainAdmissionOperation.Payload payload,
            UUID operationId,
            Set<Integer> ordinals,
            Map<Integer, UUID> actualChildIds
    ) {
        if (payload.provisionalChildIds().size() != payload.requestedCount()
                || !deterministicChildren(payload, operationId)
                || ordinals == null || actualChildIds == null
                || !actualChildIds.keySet().equals(ordinals)
                || ordinals.stream().anyMatch(ordinal ->
                ordinal == null || ordinal < 0 || ordinal >= payload.requestedCount())
                || actualChildIds.values().stream().anyMatch(Objects::isNull)
                || actualChildIds.values().stream().distinct().count()
                != actualChildIds.size()) {
            throw new IllegalArgumentException("Exact batch child receipts are required");
        }
    }

    private boolean deterministicChildren(
            PopulationDomainAdmissionOperation.Payload payload,
            UUID operationId
    ) {
        for (int ordinal = 0; ordinal < payload.requestedCount(); ordinal++) {
            UUID expected = UUID.nameUUIDFromBytes(
                    (operationId + ":child:" + ordinal)
                            .getBytes(StandardCharsets.UTF_8)
            );
            if (!expected.equals(payload.provisionalChildIds().get(ordinal))) {
                return false;
            }
        }
        return true;
    }

    private ManagedBatchSettlement batchSettlement(
            int requestedUnits,
            PopulationDomainAdmissionOperation.SettlementEvidence evidence
    ) {
        return new ManagedBatchSettlement(
                evidence.canceled()
                        ? ManagedBatchSettlement.Status.CANCELED
                        : ManagedBatchSettlement.Status.COMMITTED,
                evidence.canceled()
                        ? "population-admission-batch-canceled"
                        : "population-admission-batch-committed",
                requestedUnits,
                evidence.settledOrdinals(),
                evidence.actualChildIds()
        );
    }

    private ManagedBatchSettlement unavailableBatch(
            String reason,
            int requestedUnits
    ) {
        return new ManagedBatchSettlement(
                ManagedBatchSettlement.Status.UNAVAILABLE,
                reason,
                Math.max(1, requestedUnits),
                Set.of(),
                Map.of()
        );
    }
}
