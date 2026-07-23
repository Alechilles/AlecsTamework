package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.command.CommandRosterTransitionDefinition;
import com.alechilles.alecstamework.companion.command.CommandRosterTransitionRequest;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChangeCodec;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.DurableOperationWork;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import java.util.List;
import javax.annotation.Nonnull;

/** Command roster storage/live lifecycle changes through shared group admission. */
public final class SqliteCommandRosterTransitionOperations {
    public static final String FEATURE_SCOPE = "command_roster";

    private final SqliteDatabaseOperationCoordinator coordinator;
    private final List<ProjectionConsumer> requiredConsumers;

    public SqliteCommandRosterTransitionOperations(
            @Nonnull SqliteDatabaseOperationCoordinator coordinator,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (coordinator == null || requiredConsumers == null) {
            throw new IllegalArgumentException(
                    "Command transition dependencies are required"
            );
        }
        this.coordinator = coordinator;
        this.requiredConsumers = List.copyOf(requiredConsumers);
    }

    /** Starts or resumes one exact roster-to-live or live-to-roster transition. */
    @Nonnull
    public SqliteDatabaseOperationCoordinator.Submission submit(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CommandRosterTransitionRequest request
    ) {
        if (operationId == null || idempotencyKey == null
                || request == null) {
            throw new IllegalArgumentException(
                    "Complete command transition operation is required"
            );
        }
        SqlitePopulationGroupTransitionParticipant groups =
                new SqlitePopulationGroupTransitionParticipant(
                        request.groupAdmission()
                );
        PreparedOperationDetail detail = preparationDetail(
                request, groups
        );
        DurableOperationWork work = groups.decorate(
                (transaction, operation) -> commit(
                        transaction, operation, request
                )
        );
        return coordinator.execute(
                CommandRosterTransitionDefinition.INSTANCE,
                new OperationRequest<>(
                        operationId,
                        idempotencyKey,
                        request,
                        FEATURE_SCOPE,
                        request.groupAdmission().before().revision(),
                        List.of(
                                OperationScope.profile(
                                        request.groupAdmission()
                                                .before().profileId()
                                ),
                                OperationScope.owner(
                                        request.familyKey().ownerId()
                                ),
                                OperationScope.commandFamily(
                                        request.familyKey()
                                )
                        ),
                        request.groupAdmission().requestedAtMs()
                ),
                detail,
                work,
                requiredConsumers
        );
    }

    static PreparedOperationDetail preparationDetail(
            CommandRosterTransitionRequest request
    ) {
        return preparationDetail(
                request,
                new SqlitePopulationGroupTransitionParticipant(
                        request.groupAdmission()
                )
        );
    }

    private static PreparedOperationDetail preparationDetail(
            CommandRosterTransitionRequest request,
            SqlitePopulationGroupTransitionParticipant groups
    ) {
        return PreparedOperationDetail.compose(
                new ExactRosterDetail(request),
                groups
        );
    }

    private List<ProjectionEventDraft> commit(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CommandRosterTransitionRequest request
    ) {
        CompanionLifecycle before =
                requireExact(transaction, request);
        CompanionProfileProjectionState profileBefore =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction, before.profileId()
                );
        CompanionLifecycle after = request.groupAdmission().after();
        requireApplied(
                transaction.lifecycles().transition(
                        new LifecycleTransition(
                                before.revision(), null, after
                        )
                ),
                "command_roster_lifecycle"
        );
        CompanionProfileProjectionState profileAfter =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction, after.profileId()
                );
        CompanionProfileProjectionChange profileChange =
                new CompanionProfileProjectionChange(
                        CompanionProfileProjectionChange.Source.LIFECYCLE,
                        after.profileId(),
                        after.revision().value(),
                        profileBefore,
                        profileAfter,
                        request.groupAdmission().requestedAtMs()
                );
        return List.of(
                SqliteCompanionProfileProjectionComposer.event(
                        operation.operationId(), profileChange
                ),
                CompanionLifecycleProjectionChangeCodec.draft(
                        operation.operationId(),
                        before,
                        after,
                        request.groupAdmission().requestedAtMs()
                )
        );
    }

    private static CompanionLifecycle requireExact(
            SqlitePersistenceTransactionContext transaction,
            CommandRosterTransitionRequest request
    ) {
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(
                        request.groupAdmission().before().profileId()
                ).orElseThrow(() -> new IllegalStateException(
                        "command_roster_lifecycle_missing"
                ));
        CommandRosterMembership membership =
                transaction.commandRosters()
                        .findByProfile(lifecycle.profileId())
                        .orElseThrow(() -> new IllegalStateException(
                                "command_roster_membership_missing"
                        ));
        if (!lifecycle.equals(request.groupAdmission().before())
                || !membership.familyKey().equals(request.familyKey())
                || !membership.slotId().equals(request.slotId())
                || membership.membershipRevision()
                != request.expectedMembershipRevision()) {
            throw new IllegalStateException(
                    "command_roster_transition_source_mismatch"
            );
        }
        return lifecycle;
    }

    private static <T> T requireApplied(
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

    /** Exact roster/lifecycle preparation without feature-local phase state. */
    private static final class ExactRosterDetail
            implements PreparedOperationDetail {
        private final CommandRosterTransitionRequest request;

        private ExactRosterDetail(
                CommandRosterTransitionRequest request
        ) {
            this.request = request;
        }

        @Override
        public void prepare(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation
        ) {
            requireExact(transaction, request);
        }

        @Override
        public boolean matches(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation
        ) {
            if (operation.phase() == OperationPhase.DURABLE
                    || operation.phase() == OperationPhase.PUBLISHED) {
                return true;
            }
            try {
                requireExact(transaction, request);
                return operation.phase() == OperationPhase.PREPARED
                        || operation.phase() == OperationPhase.RETRYABLE;
            } catch (IllegalStateException invalid) {
                return false;
            }
        }
    }
}
