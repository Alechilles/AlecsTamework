package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationEngine;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteManagedAdmissionParticipant;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationPublisher;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationReader;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqlitePersistenceTransactionContext;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteUnitOfWorkRunner;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.operation.DurableCommitEvidence;
import com.alechilles.alecstamework.persistence.operation.DurableOperationWork;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.alechilles.alecstamework.persistence.recovery.OperationRecoveryClaim;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Shared operation adapter for staged provider-aware domain admission. */
public final class PopulationDomainAdmissionOperation {
    public static final String FEATURE_SCOPE = "population_domains";
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("population_domain_admission_committed");

    private final SqliteOperationEngine engine;
    private final SqliteOperationPublisher publisher;
    private final SqliteOperationReader reader;
    private final List<ProjectionConsumer> consumers;
    private final LongSupplier clock;
    private final PopulationDomainAdmissionParticipantRegistry participantRegistry =
            new PopulationDomainAdmissionParticipantRegistry();
    public PopulationDomainAdmissionOperation(
            @Nonnull SqliteOperationEngine engine,
            @Nonnull SqliteOperationPublisher publisher,
            @Nonnull SqliteOperationReader reader,
            @Nonnull List<? extends ProjectionConsumer> consumers,
            @Nonnull LongSupplier clock
    ) {
        if (engine == null || publisher == null || reader == null
                || consumers == null || clock == null) {
            throw new IllegalArgumentException("Complete domain operation dependencies are required");
        }
        this.engine = engine;
        this.publisher = publisher;
        this.reader = reader;
        this.consumers = List.copyOf(consumers);
        this.clock = clock;
    }
    @Nonnull
    public SqliteUnitOfWorkRunner.Submission<OperationEnvelope> prepare(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull Payload payload
    ) {
        return prepare(operationId, idempotencyKey, payload, null);
    }

    @Nonnull
    public SqliteUnitOfWorkRunner.Submission<OperationEnvelope> prepare(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull Payload payload,
            @Nullable PopulationAdmissionComposition composition
    ) {
        if (operationId == null || idempotencyKey == null || payload == null) {
            throw new IllegalArgumentException("Complete domain admission preparation is required");
        }
        SqliteManagedAdmissionParticipant participant = participant(
                operationId, payload, composition
        );
        return engine.prepare(
                PopulationDomainAdmissionDefinition.INSTANCE,
                new OperationRequest<>(
                        operationId,
                        idempotencyKey,
                        payload,
                        FEATURE_SCOPE,
                        payload.expectedLifecycleRevision(),
                        PopulationDomainAdmissionParticipants.scopes(payload),
                        payload.createdAtMs()
                ),
                participant
        );
    }

    @Nonnull
    public PreparedOperationDetail preparedDetail(
            @Nonnull OperationId operationId,
            @Nonnull Payload payload
    ) {
        if (operationId == null || payload == null) {
            throw new IllegalArgumentException(
                    "Complete domain admission detail is required"
            );
        }
        return participant(operationId, payload, null);
    }

    @Nonnull
    public PreparedOperationDetail preparedDetail(
            @Nonnull OperationId operationId,
            @Nonnull Payload payload,
            @Nonnull PopulationAdmissionComposition composition
    ) {
        if (operationId == null || payload == null || composition == null) {
            throw new IllegalArgumentException(
                    "Complete composed admission detail is required"
            );
        }
        return participant(operationId, payload, composition);
    }

    @Nonnull
    public CompletionStage<Optional<OperationEnvelope>> findByIdempotency(
            @Nonnull IdempotencyKey idempotencyKey
    ) {
        if (idempotencyKey == null) {
            throw new IllegalArgumentException("Domain admission idempotency key is required");
        }
        return PopulationDomainAdmissionOperationSupport.findByIdempotency(
                reader, idempotencyKey
        );
    }

    @Nonnull
    public CompletionStage<OperationEnvelope> claim(@Nonnull OperationId operationId) {
        return read(operationId).thenCompose(operation -> {
            if (operation.phase() != OperationPhase.PREPARED) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "domain_admission_claim_invalid_phase_"
                                + operation.phase().name().toLowerCase()
                ));
            }
            return engine.transition(
                    operation,
                    OperationPhase.LIVE_APPLYING,
                    null,
                    null,
                    clock.getAsLong()
            ).completion().thenCompose(result -> PopulationDomainAdmissionOperationSupport.committedValue(
                    result, "domain_admission_claim"
            ));
        });
    }

    @Nonnull
    public CompletionStage<OperationWorkflow> commit(
            @Nonnull OperationId operationId,
            boolean canceled
    ) {
        return durable(operationId, canceled, null, null);
    }

    /** Cancels a locally preclaimed token before world mutation is authorized. */
    @Nonnull
    public CompletionStage<OperationWorkflow> cancelPreclaimed(
            @Nonnull OperationId operationId
    ) {
        return durable(operationId, true, null, null, true);
    }

    @Nonnull
    public CompletionStage<OperationWorkflow> commitBatch(
            @Nonnull OperationId operationId,
            @Nonnull java.util.Set<Integer> settledOrdinals,
            @Nonnull java.util.Map<Integer, UUID> actualChildIds
    ) {
        if (operationId == null || settledOrdinals == null || actualChildIds == null) {
            throw new IllegalArgumentException("Complete batch settlement is required");
        }
        return durable(operationId, false, settledOrdinals, actualChildIds);
    }

    @Nonnull
    public CompletionStage<SettlementEvidence> settlementEvidence(
            @Nonnull OperationId operationId
    ) {
        return read(operationId).thenCompose(operation -> {
            if (operation.phase() != OperationPhase.DURABLE
                    && operation.phase() != OperationPhase.PUBLISHED) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "domain_admission_settlement_not_terminal"
                ));
            }
            return publisher.resume(operation, consumers).thenApply(workflow -> {
                if (workflow.status() != OperationWorkflowResult.Status.PUBLISHED
                        || workflow.events().isEmpty()) {
                    throw new IllegalStateException(
                            "domain_admission_settlement_readback_failed"
                    );
                }
                return PopulationDomainAdmissionSettlement.decode(
                        workflow.events().getLast().payloadJson()
                );
            });
        });
    }

    /** Recovery route that cancels a retained staged reservation. */
    @Nonnull
    public CompletionStage<com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult>
    recover(
            @Nonnull OperationId operationId,
            boolean canceled
    ) {
        return durable(operationId, canceled, null, null).thenCompose(workflow ->
                workflow.result() == null
                        ? CompletableFuture.failedFuture(
                        new IllegalStateException("domain_admission_recovery_missing_result"))
                        : CompletableFuture.completedFuture(workflow.result())
        );
    }

    /** Re-enters the exact shared recovery route for a leased operation claim. */
    @Nonnull
    public CompletionStage<OperationWorkflowResult> recover(
            @Nonnull OperationRecoveryClaim claim
    ) {
        if (claim == null) {
            throw new IllegalArgumentException("Domain admission recovery claim is required");
        }
        return switch (claim.operation().phase()) {
            case PREPARED -> recoverPreparedClaim(claim);
            case DURABLE, PUBLISHED -> publisher.resume(
                    claim.operation(), consumers
            );
            case LIVE_APPLYING, UNKNOWN, RETRYABLE, COMPENSATING -> containLiveClaim(claim);
            case COMPENSATED, FAILED -> CompletableFuture.failedFuture(
                    new IllegalStateException("domain_admission_terminal_recovery")
            );
        };
    }

    private CompletionStage<OperationWorkflowResult> recoverPreparedClaim(
            OperationRecoveryClaim claim
    ) {
        Payload payload;
        try {
            payload = PopulationDomainAdmissionDefinition.INSTANCE.decode(
                    claim.operation().payloadJson()
            );
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(new OperationWorkflowResult(
                    OperationWorkflowResult.Status.LIVE_RETRYABLE,
                    claim.operation(),
                    List.of(),
                    failure
            ));
        }
        if (clock.getAsLong() < payload.expiresAtMs()) {
            return CompletableFuture.completedFuture(new OperationWorkflowResult(
                    OperationWorkflowResult.Status.LIVE_RETRYABLE,
                    claim.operation(),
                    List.of(),
                    new IllegalStateException(
                            "domain_admission_prepared_reservation_not_expired"
                    )
            ));
        }
        return recover(claim.operation().operationId(), true);
    }

    private CompletionStage<OperationWorkflow> durable(
            OperationId operationId,
            boolean canceled,
            java.util.Set<Integer> settledOrdinals,
            java.util.Map<Integer, UUID> actualChildIds
    ) {
        return durable(
                operationId, canceled, settledOrdinals, actualChildIds, false
        );
    }

    private CompletionStage<OperationWorkflow> durable(
            OperationId operationId,
            boolean canceled,
            java.util.Set<Integer> settledOrdinals,
            java.util.Map<Integer, UUID> actualChildIds,
            boolean allowLiveCancellation
    ) {
        return read(operationId).thenCompose(operation -> {
            if (operation.phase() == OperationPhase.DURABLE
                    || operation.phase() == OperationPhase.PUBLISHED) {
                return publisher.resume(operation, consumers)
                        .thenApply(result -> new OperationWorkflow(result, operation));
            }
            boolean preparingCancel = canceled
                    && (operation.phase() == OperationPhase.PREPARED
                    || allowLiveCancellation
                    && operation.phase() == OperationPhase.LIVE_APPLYING);
            boolean committing = !canceled && operation.phase() == OperationPhase.LIVE_APPLYING;
            if (!preparingCancel && !committing) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "domain_admission_invalid_phase_"
                                + operation.phase().name().toLowerCase()
                ));
            }
            Payload payload = PopulationDomainAdmissionDefinition.INSTANCE.decode(
                    operation.payloadJson()
            );
            SqliteManagedAdmissionParticipant participant = participant(
                    operationId, payload, null
            );
            java.util.Set<Integer> ordinals = settledOrdinals == null
                    ? canceled
                            ? java.util.Set.of()
                            : java.util.stream.IntStream.range(
                                    0, payload.requestedCount()
                            ).boxed().collect(java.util.stream.Collectors.toUnmodifiableSet())
                    : java.util.Set.copyOf(settledOrdinals);
            java.util.Map<Integer, UUID> children = actualChildIds == null
                    ? java.util.Map.of() : java.util.Map.copyOf(actualChildIds);
            PopulationDomainAdmissionSettlement.validate(payload, ordinals, children);
            DurableOperationWork work = (transaction, current) -> {
                List<ProjectionEventDraft> events = List.of(
                        new ProjectionEventDraft(
                                current.operationId(),
                                EVENT_TYPE,
                                payload.reservationId().toString(),
                                1,
                                1,
                                PopulationDomainAdmissionSettlement.encode(
                                        payload, canceled, ordinals, children
                                ),
                                clock.getAsLong()
                        )
                );
                if (canceled) {
                    participant.retirePrepared(transaction, current);
                } else {
                    participant.settleBatch(
                            transaction,
                            current,
                            payload.requestedCount(),
                            ordinals.size()
                    );
                }
                return events;
            };
            return engine.commitDurable(
                    operation,
                    work,
                    clock.getAsLong()
            ).completion().thenCompose(result -> {
                if (!(result instanceof PersistenceTransactionResult.Committed<?> committed)
                        || !(committed.value() instanceof DurableCommitEvidence evidence)) {
                    Throwable failure = result instanceof PersistenceTransactionResult.RolledBack<?> rolled
                            ? rolled.failure().cause()
                            : new IllegalStateException("domain_admission_commit_failed");
                    return CompletableFuture.failedFuture(failure == null
                            ? new IllegalStateException("domain_admission_commit_failed")
                            : failure);
                }
                return publisher.publish(evidence, consumers)
                        .thenApply(published -> new OperationWorkflow(published, published.operation()));
            });
        });
    }

    private CompletionStage<OperationEnvelope> read(OperationId operationId) {
        return PopulationDomainAdmissionOperationSupport.read(reader, operationId);
    }

    private CompletionStage<OperationWorkflowResult> containLiveClaim(
            OperationRecoveryClaim claim
    ) {
        return PopulationDomainAdmissionRecovery.contain(
                engine, claim.operation(), clock
        );
    }

    /** Bounded containment for a claimed token that expired before settlement. */
    @Nonnull
    public CompletionStage<OperationWorkflowResult> containExpiredClaim(
            @Nonnull OperationId operationId
    ) {
        return read(operationId).thenCompose(operation ->
                PopulationDomainAdmissionRecovery.contain(
                        engine, operation, clock
                )
        );
    }

    private SqliteManagedAdmissionParticipant participant(
            OperationId operationId,
            Payload payload,
            @Nullable PopulationAdmissionComposition composition
    ) {
        return participantRegistry.getOrCreate(
                operationId,
                payload,
                composition == null ? null : composition.ownerPlan(),
                composition == null ? null : composition.groupRequest()
        );
    }

    /** Result wrapper used by the facade to preserve the published workflow evidence. */
    public record OperationWorkflow(
            @Nullable com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult result,
            @Nonnull OperationEnvelope operation
    ) {
    }

    /** Exact durable result used by duplicate settlement callers. */
    public record SettlementEvidence(
            boolean canceled,
            @Nonnull java.util.Set<Integer> settledOrdinals,
            @Nonnull java.util.Map<Integer, UUID> actualChildIds,
            int requestedUnits
    ) {
        public SettlementEvidence(
                boolean canceled,
                @Nonnull java.util.Set<Integer> settledOrdinals,
                @Nonnull java.util.Map<Integer, UUID> actualChildIds
        ) {
            this(canceled, settledOrdinals, actualChildIds, 0);
        }

        public SettlementEvidence {
            if (requestedUnits < 0) {
                throw new IllegalArgumentException("Requested units cannot be negative");
            }
            settledOrdinals = java.util.Set.copyOf(settledOrdinals);
            actualChildIds = java.util.Map.copyOf(actualChildIds);
        }
    }

    /** Immutable frozen provider/config and domain evidence. */
    public record Payload(
            @Nonnull UUID reservationId,
            @Nonnull ProfileId profileId,
            @Nullable OwnerId ownerId,
            @Nullable LifecycleRevision expectedLifecycleRevision,
            @Nullable String ownerWorldKey,
            @Nullable OwnerId sourceOwnerId,
            @Nullable String sourceWorldKey,
            @Nullable LifecycleState sourceLifecycle,
            @Nonnull LifecycleState targetLifecycle,
            @Nonnull String familyGroupId,
            @Nonnull String providerId,
            int providerContractVersion,
            @Nonnull String providerGenerationToken,
            long providerSnapshotRevision,
            long managedConfigRevision,
            long expiresAtMs,
            int requestedCount,
            @Nonnull List<DomainInput> domains,
            @Nonnull List<UUID> provisionalChildIds,
            long createdAtMs
    ) {
        public Payload {
            if (reservationId == null || profileId == null || targetLifecycle == null
                    || familyGroupId == null || familyGroupId.isBlank()
                    || providerId == null
                    || providerId.isBlank() || providerContractVersion <= 0
                    || providerGenerationToken == null || providerGenerationToken.isBlank()
                    || providerSnapshotRevision < 0 || managedConfigRevision < 0
                    || requestedCount <= 0 || domains == null || provisionalChildIds == null
                    || provisionalChildIds.size() > requestedCount
                    || (!provisionalChildIds.isEmpty()
                    && provisionalChildIds.size() != requestedCount)
                    || provisionalChildIds.stream().anyMatch(java.util.Objects::isNull)
                    || provisionalChildIds.stream().distinct().count()
                    != provisionalChildIds.size()
                    || createdAtMs == Long.MIN_VALUE) {
                throw new IllegalArgumentException("Complete frozen domain admission payload is required");
            }
            providerId = providerId.trim();
            providerGenerationToken = providerGenerationToken.trim();
            ownerWorldKey = ownerWorldKey == null || ownerWorldKey.isBlank()
                    ? null : ownerWorldKey.trim();
            sourceWorldKey = sourceWorldKey == null || sourceWorldKey.isBlank()
                    ? null : sourceWorldKey.trim();
            familyGroupId = familyGroupId.trim();
            if (sourceOwnerId == null && sourceWorldKey != null) {
                throw new IllegalArgumentException(
                        "An absent source owner cannot carry a source world"
                );
            }
            domains = List.copyOf(domains);
            provisionalChildIds = List.copyOf(provisionalChildIds);
        }

        @Nonnull
        public List<PopulationDomainReservation> reservations(OperationId operationId) {
            return domains.stream().map(input -> new PopulationDomainReservation(
                    operationId,
                    profileId,
                    expectedLifecycleRevision,
                    new PopulationDomainBucket(
                            ownerId,
                            input.domainId(),
                            input.scope(),
                            input.scope() == PopulationDomainScope.PER_WORLD
                                    ? input.worldKey() == null ? ownerWorldKey : input.worldKey()
                                    : null
                    ),
                    input.ownedDelta(),
                    input.deployableDelta(),
                    input.weight(),
                    input.maxOwned(),
                    input.maxDeployable(),
                    providerSnapshotRevision,
                    managedConfigRevision,
                    input.policyRevision(),
                    createdAtMs
            )).toList();
        }
    }

    /** One immutable domain row input with provider-frozen limits. */
    public record DomainInput(
            @Nonnull String domainId,
            @Nonnull PopulationDomainScope scope,
            @Nullable String worldKey,
            int ownedDelta,
            int deployableDelta,
            int weight,
            int maxOwned,
            int maxDeployable,
            long policyRevision
    ) {
        public DomainInput {
            if (domainId == null || domainId.isBlank() || scope == null
                    || ownedDelta < 0 || deployableDelta < 0
                    || (ownedDelta == 0 && deployableDelta == 0)
                    || weight <= 0 || maxOwned < 0 || maxDeployable < 0
                    || policyRevision < 0) {
                throw new IllegalArgumentException("Valid domain input is required");
            }
            domainId = domainId.trim();
            worldKey = worldKey == null || worldKey.isBlank() ? null : worldKey.trim();
        }
    }
}
