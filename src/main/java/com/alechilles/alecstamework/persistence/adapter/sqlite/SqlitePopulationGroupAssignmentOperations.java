package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentChange;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentChangeCodec;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentDefinition;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentPlan;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentRequest;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.DurableOperationWork;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import java.util.List;
import java.util.TreeSet;
import javax.annotation.Nonnull;

/** Complete population-group classification through the shared database operation protocol. */
public final class SqlitePopulationGroupAssignmentOperations {
    public static final String FEATURE_SCOPE = "population_groups";

    private final SqliteDatabaseOperationCoordinator coordinator;
    private final List<ProjectionConsumer> requiredConsumers;

    public SqlitePopulationGroupAssignmentOperations(
            @Nonnull SqliteDatabaseOperationCoordinator coordinator,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (coordinator == null || requiredConsumers == null) {
            throw new IllegalArgumentException(
                    "Population group operation dependencies are required"
            );
        }
        this.coordinator = coordinator;
        this.requiredConsumers = List.copyOf(requiredConsumers);
    }

    /** Starts or resumes one exact complete assignment. */
    @Nonnull
    public SqliteDatabaseOperationCoordinator.Submission submit(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull PopulationGroupAssignmentRequest assignment
    ) {
        if (operationId == null || idempotencyKey == null
                || assignment == null) {
            throw new IllegalArgumentException(
                    "Complete population group operation is required"
            );
        }
        SqlitePopulationGroupAssignmentPreparation preparation =
                new SqlitePopulationGroupAssignmentPreparation(assignment);
        DurableOperationWork work = preparation.decorate(
                (transaction, operation) -> commit(
                        transaction, operation, assignment
                )
        );
        return coordinator.execute(
                PopulationGroupAssignmentDefinition.INSTANCE,
                new OperationRequest<>(
                        operationId,
                        idempotencyKey,
                        assignment,
                        FEATURE_SCOPE,
                        assignment.expectedLifecycleRevision(),
                        participants(assignment),
                        assignment.requestedAtMs()
                ),
                preparation,
                work,
                requiredConsumers
        );
    }

    private List<ProjectionEventDraft> commit(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            PopulationGroupAssignmentRequest request
    ) {
        PopulationGroupAssignment before =
                transaction.populationGroups()
                        .findAssignment(request.profileId())
                        .orElse(null);
        PopulationGroupAssignmentPlan plan =
                SqlitePopulationGroupAssignmentPreparation.plan(
                        transaction, operation, request
                );
        PopulationGroupAssignment committed = requireApplied(
                transaction.populationGroups().replaceAssignment(
                        request.expectedAssignmentRevision(),
                        plan.target()
                ),
                "population_group_assignment"
        );
        return List.of(PopulationGroupAssignmentChangeCodec.draft(
                operation.operationId(),
                new PopulationGroupAssignmentChange(
                        request.profileId(), before, committed
                )
        ));
    }

    private List<OperationScope> participants(
            PopulationGroupAssignmentRequest request
    ) {
        TreeSet<OperationScope> scopes = new TreeSet<>();
        scopes.add(OperationScope.profile(request.profileId()));
        addOwner(scopes, request.expectedOwnerId());
        return List.copyOf(scopes);
    }

    private void addOwner(
            TreeSet<OperationScope> scopes,
            OwnerId ownerId
    ) {
        if (ownerId != null) {
            scopes.add(OperationScope.owner(ownerId));
        }
    }

    private <T> T requireApplied(
            PersistenceMutationResult<T> result,
            String operation
    ) {
        if (result == null || !result.applied()) {
            throw new IllegalStateException(
                    operation + "_" + (result == null
                            ? "null"
                            : result.status().name().toLowerCase())
            );
        }
        return result.value();
    }
}

