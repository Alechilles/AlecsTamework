package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationEngine;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationPublisher;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationReader;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqlitePopulationDomainParticipant;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqlitePersistenceTransactionContext;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteUnitOfWorkRunner;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.operation.DurableCommitEvidence;
import com.alechilles.alecstamework.persistence.operation.DurableOperationWork;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.alechilles.alecstamework.persistence.recovery.OperationRecoveryClaim;
import java.util.ArrayList;
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
        if (operationId == null || idempotencyKey == null || payload == null) {
            throw new IllegalArgumentException("Complete domain admission preparation is required");
        }
        SqlitePopulationDomainParticipant participant = participant(operationId, payload);
        return engine.prepare(
                PopulationDomainAdmissionDefinition.INSTANCE,
                new OperationRequest<>(
                        operationId,
                        idempotencyKey,
                        payload,
                        FEATURE_SCOPE,
                        payload.expectedLifecycleRevision(),
                        participants(operationId, payload),
                        payload.createdAtMs()
                ),
                participant
        );
    }

    /** Returns the reusable transaction participant for lifecycle composition. */
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
        return participant(operationId, payload);
    }

    /** Reads an existing staged operation for a durable idempotency retry. */
    @Nonnull
    public CompletionStage<Optional<OperationEnvelope>> findByIdempotency(
            @Nonnull IdempotencyKey idempotencyKey
    ) {
        if (idempotencyKey == null) {
            throw new IllegalArgumentException("Domain admission idempotency key is required");
        }
        return reader.findByIdempotency(
                PopulationDomainAdmissionDefinition.KIND,
                idempotencyKey
        ).thenApply(result -> {
            if (result instanceof PersistenceReadResult.Found<SqliteOperationReader.OperationReadModel> found) {
                return Optional.of(found.value().operation());
            }
            return Optional.empty();
        });
    }

    /** Returns a live-applying transition for the synchronous claim stage. */
    @Nonnull
    public CompletionStage<OperationEnvelope> claim(@Nonnull OperationId operationId) {
        return read(operationId).thenCompose(operation -> {
            if (operation.phase() != OperationPhase.PREPARED) {
                return CompletableFuture.completedFuture(operation);
            }
            return engine.transition(
                    operation,
                    OperationPhase.LIVE_APPLYING,
                    null,
                    null,
                    clock.getAsLong()
            ).completion().thenCompose(result -> committedValue(
                    result,
                    "domain_admission_claim"
            ));
        });
    }

    /** Commits one staged reservation and publishes exact outbox evidence. */
    @Nonnull
    public CompletionStage<OperationWorkflow> commit(
            @Nonnull OperationId operationId,
            boolean canceled
    ) {
        return durable(operationId, canceled);
    }

    /** Recovery route that cancels a retained staged reservation. */
    @Nonnull
    public CompletionStage<com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult>
    recover(
            @Nonnull OperationId operationId,
            boolean canceled
    ) {
        return durable(operationId, canceled).thenCompose(workflow ->
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
        return recover(claim.operation().operationId(), true);
    }

    private CompletionStage<OperationWorkflow> durable(
            OperationId operationId,
            boolean canceled
    ) {
        return read(operationId).thenCompose(operation -> {
            if (operation.phase() == OperationPhase.DURABLE
                    || operation.phase() == OperationPhase.PUBLISHED) {
                return publisher.resume(operation, consumers)
                        .thenApply(result -> new OperationWorkflow(result, operation));
            }
            if (operation.phase() != OperationPhase.PREPARED
                    && operation.phase() != OperationPhase.LIVE_APPLYING
                    && operation.phase() != OperationPhase.RETRYABLE
                    && operation.phase() != OperationPhase.UNKNOWN) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "domain_admission_invalid_phase_"
                                + operation.phase().name().toLowerCase()
                ));
            }
            Payload payload = PopulationDomainAdmissionDefinition.INSTANCE.decode(
                    operation.payloadJson()
            );
            SqlitePopulationDomainParticipant participant = participant(
                    operationId, payload
            );
            DurableOperationWork work = (transaction, current) -> {
                List<ProjectionEventDraft> events = List.of(
                        new ProjectionEventDraft(
                                current.operationId(),
                                EVENT_TYPE,
                                payload.reservationId().toString(),
                                1,
                                1,
                                canceled ? "{\"status\":\"CANCELED\"}"
                                        : "{\"status\":\"COMMITTED\"}",
                                clock.getAsLong()
                        )
                );
                return events;
            };
            DurableOperationWork decorated = participant.decorate(work);
            return engine.commitDurable(
                    operation,
                    decorated,
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
        return reader.find(operationId).thenCompose(result -> {
            if (result instanceof PersistenceReadResult.Found<SqliteOperationReader.OperationReadModel> found) {
                return CompletableFuture.completedFuture(found.value().operation());
            }
            return CompletableFuture.failedFuture(
                    new IllegalStateException("domain_admission_operation_missing")
            );
        });
    }

    private CompletionStage<OperationEnvelope> committedValue(
            PersistenceTransactionResult<OperationEnvelope> result,
            String failureCode
    ) {
        if (result instanceof PersistenceTransactionResult.Committed<OperationEnvelope> committed
                && committed.value() != null) {
            return CompletableFuture.completedFuture(committed.value());
        }
        return CompletableFuture.failedFuture(new IllegalStateException(failureCode));
    }

    private SqlitePopulationDomainParticipant participant(
            OperationId operationId,
            Payload payload
    ) {
        return new SqlitePopulationDomainParticipant(
                payload.reservations(operationId)
        );
    }

    private List<OperationScope> participants(OperationId operationId, Payload payload) {
        ArrayList<OperationScope> scopes = new ArrayList<>();
        scopes.add(OperationScope.profile(payload.profileId()));
        if (payload.ownerId() != null) {
            scopes.add(OperationScope.owner(payload.ownerId()));
        }
        return List.copyOf(scopes);
    }

    /** Result wrapper used by the facade to preserve the published workflow evidence. */
    public record OperationWorkflow(
            @Nullable com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult result,
            @Nonnull OperationEnvelope operation
    ) {
    }

    /** Immutable frozen provider/config and domain evidence. */
    public record Payload(
            @Nonnull UUID reservationId,
            @Nonnull ProfileId profileId,
            @Nullable OwnerId ownerId,
            @Nullable LifecycleRevision expectedLifecycleRevision,
            @Nullable String ownerWorldKey,
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
            if (reservationId == null || profileId == null || providerId == null
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
