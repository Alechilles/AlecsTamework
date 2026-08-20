package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.OwnerPopulationCapDecisionViewV2;
import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV3;
import com.alechilles.alecstamework.api.PopulationAdmissionToken;
import com.alechilles.alecstamework.companion.population.domain.ManagedAdmissionEvidenceAuthor;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionDefinition;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionOperation;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Owns staged admission tokens and their durable idempotency lifecycle. */
final class PopulationAdmissionStaging {
    private static final long TOKEN_TTL_NANOS = 30_000_000_000L;

    private final PopulationDomainAdmissionOperation operations;
    private final ConcurrentMap<UUID, ActiveToken> active =
            new ConcurrentHashMap<>();

    PopulationAdmissionStaging(
            PopulationDomainAdmissionOperation operations
    ) {
        this.operations = Objects.requireNonNull(operations, "operations");
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

    CompletionStage<PopulationAdmissionDecision> prepareOrReuse(
            Identity identity,
            ManagedAdmissionEvidenceAuthor.Authoring evidence
    ) {
        OperationId operationId = new OperationId(identity.operationId());
        IdempotencyKey idempotencyKey = new IdempotencyKey(
                identity.idempotencyKey()
        );
        return operations.findByIdempotency(idempotencyKey)
                .thenCompose(existing -> existing.isPresent()
                        ? reuseExisting(existing.orElseThrow(), evidence)
                        : prepareNew(operationId, idempotencyKey,
                                identity.reservationId(), evidence));
    }

    PopulationAdmissionDecision claimForApply(
            PopulationAdmissionToken token
    ) {
        ActiveToken current = active.get(token.operationId());
        if (current == null || !current.token().equals(token)
                || System.nanoTime() >= token.expiresAtMonotonicNanos()) {
            return PopulationAdmissionDecision.unavailable(
                    "population-admission-token-invalid"
            );
        }
        active.put(token.operationId(), current.asApplying());
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
            return CompletableFuture.completedFuture(
                    PopulationAdmissionDecision.unavailable(
                            "population-admission-token-invalid"
                    )
            );
        }
        return operations.commit(
                        new OperationId(token.operationId()),
                        canceled
                )
                .thenApply(result -> {
                    if (result == null || result.result() == null
                            || result.result().status()
                            != com.alechilles.alecstamework.persistence.operation
                            .OperationWorkflowResult.Status.PUBLISHED) {
                        throw new IllegalStateException(
                                "population-admission-publication-pending"
                        );
                    }
                    active.remove(token.operationId());
                    return new PopulationAdmissionDecision(
                            canceled
                                    ? PopulationAdmissionDecision.Status.CANCELED
                                    : PopulationAdmissionDecision.Status.COMMITTED,
                            canceled
                                    ? "population-admission-canceled"
                                    : "population-admission-committed",
                            canceled ? null : current.token(),
                            OwnerPopulationCapDecisionViewV2.Readiness.READY,
                            0,
                            0
                    );
                })
                .exceptionally(failure -> PopulationAdmissionDecision.unavailable(
                        "population-admission-settlement-failed"
                ));
    }

    CompletionStage<Integer> cleanupExpired() {
        long now = System.nanoTime();
        java.util.ArrayList<CompletionStage<PopulationAdmissionDecision>> pending =
                new java.util.ArrayList<>();
        for (ActiveToken value : active.values()) {
            if (!value.applying()
                    && now >= value.token().expiresAtMonotonicNanos()) {
                pending.add(settle(value.token(), true));
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
            ManagedAdmissionEvidenceAuthor.Authoring evidence
    ) {
        return operations.prepare(
                operationId,
                idempotencyKey,
                evidence.payload()
        ).completion().thenApply(result -> {
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
            active.put(envelope.operationId().value(),
                    new ActiveToken(token, evidence));
            return reserved(token);
        });
    }

    private CompletionStage<PopulationAdmissionDecision> reuseExisting(
            OperationEnvelope envelope,
            ManagedAdmissionEvidenceAuthor.Authoring evidence
    ) {
        PopulationDomainAdmissionOperation.Payload stored =
                PopulationDomainAdmissionDefinition.INSTANCE.decode(
                        envelope.payloadJson()
                );
        if (!samePayloadExceptTimes(stored, evidence.payload())) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "population-admission-idempotency-conflict"
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
        return operations.prepare(
                envelope.operationId(),
                envelope.idempotencyKey(),
                stored
        ).completion().thenApply(result -> {
            OperationEnvelope refreshed = envelope(result,
                    "population-admission-already-settled");
            if (!staged(refreshed)) {
                throw new IllegalStateException(
                        "population-admission-already-settled"
                );
            }
            PopulationAdmissionToken token = token(
                    stored.reservationId(),
                    refreshed.operationId(),
                    stored.managedConfigRevision(),
                    stored.providerGenerationToken()
            );
            active.put(refreshed.operationId().value(),
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
        return envelope.phase() == OperationPhase.PREPARED
                || envelope.phase() == OperationPhase.LIVE_APPLYING;
    }

    private boolean samePayloadExceptTimes(
            PopulationDomainAdmissionOperation.Payload stored,
            PopulationDomainAdmissionOperation.Payload requested
    ) {
        return stored.reservationId().equals(requested.reservationId())
                && stored.profileId().equals(requested.profileId())
                && Objects.equals(stored.ownerId(), requested.ownerId())
                && Objects.equals(
                        stored.expectedLifecycleRevision(),
                        requested.expectedLifecycleRevision()
                )
                && Objects.equals(stored.ownerWorldKey(), requested.ownerWorldKey())
                && stored.providerId().equals(requested.providerId())
                && stored.providerContractVersion() == requested.providerContractVersion()
                && stored.providerGenerationToken().equals(requested.providerGenerationToken())
                && stored.providerSnapshotRevision() == requested.providerSnapshotRevision()
                && stored.managedConfigRevision() == requested.managedConfigRevision()
                && stored.requestedCount() == requested.requestedCount()
                && stored.domains().equals(requested.domains())
                && stored.provisionalChildIds().equals(requested.provisionalChildIds());
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
                System.nanoTime() + TOKEN_TTL_NANOS,
                settingsRevision,
                providerGenerationToken,
                OwnerPopulationCapDecisionViewV2.Readiness.READY
        );
    }

    record Identity(
            UUID operationId,
            UUID reservationId,
            String idempotencyKey
    ) {
    }

    private record ActiveToken(
            PopulationAdmissionToken token,
            ManagedAdmissionEvidenceAuthor.Authoring evidence,
            boolean applying
    ) {
        private ActiveToken(
                PopulationAdmissionToken token,
                ManagedAdmissionEvidenceAuthor.Authoring evidence
        ) {
            this(token, evidence, false);
        }

        private ActiveToken asApplying() {
            return new ActiveToken(token, evidence, true);
        }
    }
}
