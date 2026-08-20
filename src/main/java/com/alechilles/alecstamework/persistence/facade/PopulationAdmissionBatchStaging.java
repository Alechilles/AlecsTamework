package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.OwnerPopulationCapDecisionViewV2;
import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionToken;
import com.alechilles.alecstamework.companion.population.domain.ManagedAdmissionEvidenceAuthor;
import com.alechilles.alecstamework.companion.population.domain.ManagedBatchAdmissionRequest;
import com.alechilles.alecstamework.companion.population.domain.ManagedBatchSettlement;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionOperation;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentMap;

/** Stages the private aggregate litter contract over one durable admission. */
final class PopulationAdmissionBatchStaging {
    private final PopulationDomainAdmissionOperation operations;
    private final ConcurrentMap<UUID, PopulationAdmissionStaging.ActiveToken> active;
    private final ConcurrentMap<UUID, CompletableFuture<ManagedBatchSettlement>> settlementFlights =
            new java.util.concurrent.ConcurrentHashMap<>();

    PopulationAdmissionBatchStaging(
            PopulationDomainAdmissionOperation operations,
            ConcurrentMap<UUID, PopulationAdmissionStaging.ActiveToken> active
    ) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.active = Objects.requireNonNull(active, "active");
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
        if (current != null && current.terminal() != null) {
            return terminalEvidence(token, current.evidence().payload().requestedCount());
        }
        if (current != null && current.settling()) {
            CompletableFuture<ManagedBatchSettlement> flight = settlementFlights.get(
                    token.operationId()
            );
            if (flight != null) {
                return flight;
            }
        }
        if (current == null || !current.token().equals(token)
                || !current.applying()) {
            if (current == null && exactRestartToken(token)) {
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
            validate(current.evidence().payload(), ordinals, actualChildIds);
        } catch (IllegalArgumentException invalid) {
            return CompletableFuture.completedFuture(unavailableBatch(
                    "population-admission-batch-receipts-invalid",
                    current.evidence().payload().requestedCount()
            ));
        }
        PopulationAdmissionStaging.ActiveToken settling = current.asSettling();
        CompletableFuture<ManagedBatchSettlement> flight = new CompletableFuture<>();
        CompletableFuture<ManagedBatchSettlement> existingFlight = settlementFlights.putIfAbsent(
                token.operationId(), flight
        );
        if (existingFlight != null) {
            return existingFlight;
        }
        if (!active.replace(token.operationId(), current, settling)) {
            ManagedBatchSettlement unavailable = unavailableBatch(
                    "population-admission-batch-token-invalid",
                    current.evidence().payload().requestedCount()
            );
            flight.complete(unavailable);
            settlementFlights.remove(token.operationId(), flight);
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
                    active.put(token.operationId(), settling.terminal(
                            decisionForSettlement(token, evidence)
                    ));
                    flight.complete(settlement);
                    settlementFlights.remove(token.operationId(), flight);
                    return settlement;
                })
                .exceptionally(failure -> {
                    active.replace(token.operationId(), settling, current);
                    ManagedBatchSettlement unavailable = unavailableBatch(
                            "population-admission-batch-settlement-failed",
                            current.evidence().payload().requestedCount()
                    );
                    flight.complete(unavailable);
                    settlementFlights.remove(token.operationId(), flight);
                    return unavailable;
                });
    }

    private CompletionStage<ManagedBatchSettlement> terminalEvidence(
            PopulationAdmissionToken token,
            int requestedUnits
    ) {
        return operations.settlementEvidence(new OperationId(token.operationId()))
                .thenApply(evidence -> batchSettlement(requestedUnits, evidence));
    }

    private CompletionStage<ManagedBatchSettlement> settleRestarted(
            PopulationAdmissionToken token,
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
                .thenApply(evidence -> batchSettlement(
                        evidence.requestedUnits() > 0
                                ? evidence.requestedUnits() : ordinals.size(),
                        evidence
                ))
                .exceptionally(failure -> unavailableBatch(
                        "population-admission-batch-settlement-failed",
                        Math.max(1, ordinals.size())
                ));
    }

    private boolean exactRestartToken(PopulationAdmissionToken token) {
        String key = "population-domain:litter:" + token.operationId();
        UUID expected = UUID.nameUUIDFromBytes(
                (key + ":reservation").getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        return expected.equals(token.reservationId());
    }

    private void validate(
            PopulationDomainAdmissionOperation.Payload payload,
            Set<Integer> ordinals,
            Map<Integer, UUID> actualChildIds
    ) {
        if (ordinals == null || actualChildIds == null
                || !actualChildIds.keySet().equals(ordinals)
                || ordinals.stream().anyMatch(ordinal ->
                ordinal == null || ordinal < 0 || ordinal >= payload.requestedCount())
                || actualChildIds.values().stream().anyMatch(Objects::isNull)
                || actualChildIds.values().stream().distinct().count()
                != actualChildIds.size()) {
            throw new IllegalArgumentException("Exact batch child receipts are required");
        }
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

    private PopulationAdmissionDecision decisionForSettlement(
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
