package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.CommandRosterMembershipChangeCodec;
import com.alechilles.alecstamework.companion.command.CommandRosterMutationOutcome;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChangeCodec;
import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlan;
import com.alechilles.alecstamework.companion.population.OwnerPopulationScope;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentChange;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentChangeCodec;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.companion.provisioning.CompanionProvisioningDefinition;
import com.alechilles.alecstamework.companion.provisioning.CompanionProvisioningRequest;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningRecord;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningRecordChangeCodec;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.DurableOperationWork;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import javax.annotation.Nonnull;

/** Atomic dormant provisioning through existing canonical authorities. */
public final class SqliteCompanionProvisioningOperations {
    public static final String FEATURE_SCOPE = "provisioning";

    private final SqliteDatabaseOperationCoordinator coordinator;
    private final List<ProjectionConsumer> requiredConsumers;

    public SqliteCompanionProvisioningOperations(
            @Nonnull SqliteDatabaseOperationCoordinator coordinator,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (coordinator == null || requiredConsumers == null) {
            throw new IllegalArgumentException(
                    "Provisioning operation dependencies are required"
            );
        }
        this.coordinator = coordinator;
        this.requiredConsumers = List.copyOf(requiredConsumers);
    }

    /** Starts or resumes one deterministic dormant profile grant. */
    @Nonnull
    public SqliteDatabaseOperationCoordinator.Submission submit(
            @Nonnull OperationId operationId,
            @Nonnull CompanionProvisioningRequest request
    ) {
        if (operationId == null || request == null) {
            throw new IllegalArgumentException(
                    "Complete provisioning operation is required"
            );
        }
        SqliteCompanionProvisioningPreparation source =
                new SqliteCompanionProvisioningPreparation(request);
        SqliteOwnerPopulationParticipant population =
                new SqliteOwnerPopulationParticipant(population(request));
        SqlitePopulationGroupProvisioningParticipant groups =
                new SqlitePopulationGroupProvisioningParticipant(request);
        DurableOperationWork work = groups.decorate(
                population.decorate((transaction, operation) ->
                        commit(transaction, operation, request))
        );
        return coordinator.execute(
                CompanionProvisioningDefinition.INSTANCE,
                new OperationRequest<>(
                        operationId,
                        request.origin().operationKey(),
                        request,
                        FEATURE_SCOPE,
                        null,
                        participants(request),
                        request.requestedAtMs()
                ),
                PreparedOperationDetail.compose(
                        source, population, groups
                ),
                work,
                requiredConsumers
        );
    }

    private List<ProjectionEventDraft> commit(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionProvisioningRequest request
    ) {
        requireApplied(
                transaction.identities().createProfile(
                        request.identity()
                ),
                "provisioning_identity"
        );
        requireApplied(
                transaction.lifecycles().create(request.lifecycle()),
                "provisioning_lifecycle"
        );
        ProvisioningRecord record = requireApplied(
                transaction.provisioning().create(
                        new ProvisioningRecord(
                                request.origin().profileId(),
                                request.origin(),
                                request.correlationId(),
                                request.groupAssignment()
                                        .policyRevision(),
                                operation.operationId(),
                                request.requestedAtMs()
                        )
                ),
                "provisioning_record"
        );
        requireApplied(
                transaction.populationGroups().replaceAssignment(
                        null, request.groupAssignment()
                ),
                "provisioning_group_assignment"
        );
        CommandRosterMutationOutcome roster = commandMembership(
                transaction, request
        );
        CompanionProfileProjectionState after =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction, request.origin().profileId()
                );
        return events(operation, request, record, roster, after);
    }

    private CommandRosterMutationOutcome commandMembership(
            SqlitePersistenceTransactionContext transaction,
            CompanionProvisioningRequest request
    ) {
        if (request.commandMembership() == null) {
            return null;
        }
        return requireApplied(
                transaction.commandRosters().upsert(
                        request.expectedCommandRosterRevision(),
                        null,
                        request.commandMembership()
                ),
                "provisioning_command_membership"
        );
    }

    private List<ProjectionEventDraft> events(
            OperationEnvelope operation,
            CompanionProvisioningRequest request,
            ProvisioningRecord record,
            CommandRosterMutationOutcome roster,
            CompanionProfileProjectionState after
    ) {
        ArrayList<ProjectionEventDraft> events = new ArrayList<>();
        events.add(ProvisioningRecordChangeCodec.draft(
                operation.operationId(), record
        ));
        events.add(SqliteCompanionProfileProjectionComposer.event(
                operation.operationId(),
                new CompanionProfileProjectionChange(
                        CompanionProfileProjectionChange.Source.METADATA,
                        request.origin().profileId(),
                        0,
                        null,
                        after,
                        request.requestedAtMs()
                )
        ));
        events.add(CompanionLifecycleProjectionChangeCodec.draft(
                operation.operationId(),
                null,
                request.lifecycle(),
                request.requestedAtMs()
        ));
        events.add(PopulationGroupAssignmentChangeCodec.draft(
                operation.operationId(),
                new PopulationGroupAssignmentChange(
                        request.origin().profileId(),
                        null,
                        request.groupAssignment()
                )
        ));
        if (roster != null) {
            events.add(CommandRosterMembershipChangeCodec.draft(
                    operation.operationId(),
                    roster,
                    request.requestedAtMs()
            ));
        }
        return List.copyOf(events);
    }

    private OwnerPopulationAdmissionPlan population(
            CompanionProvisioningRequest request
    ) {
        return new OwnerPopulationAdmissionPlan(
                request.origin().profileId(),
                null,
                List.of(
                        new OwnerPopulationAdmissionPlan.LimitIncrease(
                                OwnerPopulationScope.global(
                                        request.lifecycle().ownerId()
                                ),
                                1,
                                request.globalOwnerLimit()
                        ),
                        new OwnerPopulationAdmissionPlan.LimitIncrease(
                                OwnerPopulationScope.perWorld(
                                        request.lifecycle().ownerId(),
                                        request.lifecycle()
                                                .ownerWorldKey()
                                ),
                                1,
                                request.perWorldOwnerLimit()
                        )
                )
        );
    }

    private List<OperationScope> participants(
            CompanionProvisioningRequest request
    ) {
        TreeSet<OperationScope> scopes = new TreeSet<>();
        scopes.add(OperationScope.profile(
                request.origin().profileId()
        ));
        scopes.add(OperationScope.owner(
                request.lifecycle().ownerId()
        ));
        if (request.commandMembership() != null) {
            scopes.add(OperationScope.commandFamily(
                    request.commandMembership().familyKey()
            ));
        }
        return List.copyOf(scopes);
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

