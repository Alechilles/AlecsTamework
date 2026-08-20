package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteManagedAdmissionParticipant;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationEngine;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationPublisher;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationReader;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.operation.DurableCommitEvidence;
import com.alechilles.alecstamework.persistence.operation.DurableOperationWork;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;

/** Owns the durable transition and publication path for domain admission. */
final class PopulationDomainAdmissionWorkflowSupport {
    private PopulationDomainAdmissionWorkflowSupport() {
    }

    static CompletionStage<PopulationDomainAdmissionOperation.OperationWorkflow> durable(
            SqliteOperationEngine engine,
            SqliteOperationPublisher publisher,
            SqliteOperationReader reader,
            List<ProjectionConsumer> consumers,
            LongSupplier clock,
            PopulationDomainAdmissionParticipantRegistry registry,
            OperationId operationId,
            boolean canceled,
            Set<Integer> settledOrdinals,
            Map<Integer, UUID> actualChildIds,
            boolean allowLiveCancellation
    ) {
        return PopulationDomainAdmissionOperationSupport.read(reader, operationId)
                .thenCompose(operation -> {
                    if (operation.phase() == OperationPhase.DURABLE
                            || operation.phase() == OperationPhase.PUBLISHED) {
                        return publisher.resume(operation, consumers)
                                .thenApply(result -> new PopulationDomainAdmissionOperation.OperationWorkflow(
                                        terminalResult(result, registry, operation),
                                        result.operation() == null ? operation : result.operation()
                                ));
                    }
                    boolean preparingCancel = canceled
                            && (operation.phase() == OperationPhase.PREPARED
                            || allowLiveCancellation
                            && operation.phase() == OperationPhase.LIVE_APPLYING);
                    boolean committing = !canceled
                            && operation.phase() == OperationPhase.LIVE_APPLYING;
                    if (!preparingCancel && !committing) {
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                "domain_admission_invalid_phase_"
                                        + operation.phase().name().toLowerCase()
                        ));
                    }
                    return commit(
                        engine, publisher, consumers, clock, registry,
                            operation, operationId, canceled, settledOrdinals,
                            actualChildIds
                    );
                });
    }

    private static CompletionStage<PopulationDomainAdmissionOperation.OperationWorkflow> commit(
            SqliteOperationEngine engine,
            SqliteOperationPublisher publisher,
            List<ProjectionConsumer> consumers,
            LongSupplier clock,
            PopulationDomainAdmissionParticipantRegistry registry,
            OperationEnvelope operation,
            OperationId operationId,
            boolean canceled,
            Set<Integer> settledOrdinals,
            Map<Integer, UUID> actualChildIds
    ) {
        PopulationDomainAdmissionOperation.Payload payload =
                PopulationDomainAdmissionDefinition.INSTANCE.decode(
                        operation.payloadJson()
                );
        SqliteManagedAdmissionParticipant participant = registry.getOrCreate(
                operationId, payload, null, null
        );
        Set<Integer> ordinals = settledOrdinals == null
                ? canceled ? Set.of() : java.util.stream.IntStream.range(
                        0, payload.requestedCount()
                ).boxed().collect(java.util.stream.Collectors.toUnmodifiableSet())
                : Set.copyOf(settledOrdinals);
        Map<Integer, UUID> children = actualChildIds == null
                ? Map.of() : Map.copyOf(actualChildIds);
        PopulationDomainAdmissionSettlement.validate(payload, ordinals, children);
        DurableOperationWork work = (transaction, current) -> {
            List<ProjectionEventDraft> events = List.of(new ProjectionEventDraft(
                    current.operationId(),
                    PopulationDomainAdmissionOperation.EVENT_TYPE,
                    payload.reservationId().toString(),
                    1,
                    1,
                    PopulationDomainAdmissionSettlement.encode(
                            payload, canceled, ordinals, children
                    ),
                    clock.getAsLong()
            ));
            if (canceled) {
                participant.retirePrepared(transaction, current);
            } else {
                participant.settleBatch(
                        transaction, current, payload.requestedCount(), ordinals.size()
                );
            }
            return events;
        };
        return engine.commitDurable(operation, work, clock.getAsLong())
                .completion().thenCompose(result -> publish(
                        publisher, consumers, registry, result
                ));
    }

    private static CompletionStage<PopulationDomainAdmissionOperation.OperationWorkflow> publish(
            SqliteOperationPublisher publisher,
            List<ProjectionConsumer> consumers,
            PopulationDomainAdmissionParticipantRegistry registry,
            PersistenceTransactionResult<?> result
    ) {
        if (!(result instanceof PersistenceTransactionResult.Committed<?> committed)
                || !(committed.value() instanceof DurableCommitEvidence evidence)) {
            Throwable failure = result instanceof PersistenceTransactionResult.RolledBack<?> rolled
                    ? rolled.failure().cause()
                    : new IllegalStateException("domain_admission_commit_failed");
            return CompletableFuture.failedFuture(failure == null
                    ? new IllegalStateException("domain_admission_commit_failed")
                    : failure);
        }
        return publisher.publish(evidence, consumers).thenApply(published ->
                new PopulationDomainAdmissionOperation.OperationWorkflow(
                        terminalResult(published, registry, published.operation()),
                        published.operation()
                )
        );
    }

    private static OperationWorkflowResult terminalResult(
            OperationWorkflowResult result,
            PopulationDomainAdmissionParticipantRegistry registry,
            OperationEnvelope operation
    ) {
        if (result.status() == OperationWorkflowResult.Status.PUBLISHED) {
            registry.evict(operation.operationId());
        }
        return result;
    }
}
