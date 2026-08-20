package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.CommandRosterTransitionDefinition;
import com.alechilles.alecstamework.companion.command.CommandRosterTransitionRequest;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChangeCodec;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
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
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import java.util.List;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Command roster storage/live lifecycle changes through shared group admission. */
public final class SqliteCommandRosterTransitionOperations {
    public static final String FEATURE_SCOPE = "command_roster";

    private final SqliteDatabaseOperationCoordinator coordinator;
    @Nullable
    private final SqliteManagedRosterReturnAdmission admission;
    private final SqliteLifecycleAdmissionSingleFlight singleFlight =
            new SqliteLifecycleAdmissionSingleFlight();
    private final List<ProjectionConsumer> requiredConsumers;

    public SqliteCommandRosterTransitionOperations(
            @Nonnull SqliteDatabaseOperationCoordinator coordinator,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        this(coordinator, null, null, null, requiredConsumers);
    }

    SqliteCommandRosterTransitionOperations(
            @Nonnull SqliteDatabaseOperationCoordinator coordinator,
            @Nullable SqliteOperationReader reader,
            @Nullable SqliteLifecycleAdmissionBinding lifecycleAdmission,
            @Nullable SqliteLifecycleAdmissionSourceReader sourceReader,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (coordinator == null || requiredConsumers == null) {
            throw new IllegalArgumentException(
                    "Command transition dependencies are required"
            );
        }
        this.coordinator = coordinator;
        admission = reader == null || lifecycleAdmission == null
                || sourceReader == null
                ? null
                : new SqliteManagedRosterReturnAdmission(
                        reader, lifecycleAdmission, sourceReader
                );
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
        if (!positiveReturn(request) && request.admissionEvidence() != null) {
            return rejected(
                    "command_roster_store_admission_evidence_forbidden"
            );
        }
        if (!positiveReturn(request) || admission == null) {
            return execute(operationId, idempotencyKey, request);
        }
        CompletionStage<OperationWorkflowResult> completion =
                singleFlight.submit(
                        CommandRosterTransitionDefinition.KIND,
                        operationId,
                        idempotencyKey,
                        CommandRosterTransitionDefinition.INSTANCE.encode(
                                request
                        ),
                        () -> admission.resolve(
                                        operationId, idempotencyKey, request
                                )
                                .thenCompose(value -> execute(
                                        operationId, idempotencyKey, value
                                ).completion())
                );
        return new SqliteDatabaseOperationCoordinator.Submission(
                SqliteSingleWriter.WriteAcceptance.ACCEPTED,
                completion.exceptionally(failure ->
                        SqliteOperationResults.failed(
                                OperationWorkflowResult.Status.PREPARE_FAILED,
                                null,
                                List.of(),
                                failure instanceof java.util.concurrent
                                .CompletionException
                                && failure.getCause() != null
                                        ? failure.getCause() : failure
                        )
                )
        );
    }

    private SqliteDatabaseOperationCoordinator.Submission rejected(
            String code
    ) {
        return new SqliteDatabaseOperationCoordinator.Submission(
                SqliteSingleWriter.WriteAcceptance.ACCEPTED,
                java.util.concurrent.CompletableFuture.completedFuture(
                        SqliteOperationResults.failed(
                                OperationWorkflowResult.Status.PREPARE_FAILED,
                                null,
                                List.of(),
                                new IllegalArgumentException(code)
                        )
                )
        );
    }

    private SqliteDatabaseOperationCoordinator.Submission execute(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            CommandRosterTransitionRequest request
    ) {
        SqlitePopulationGroupTransitionParticipant groups =
                needsExternalGroups(request)
                        ? new SqlitePopulationGroupTransitionParticipant(
                        request.groupAdmission()
                ) : null;
        SqliteManagedAdmissionParticipant managed =
                request.admissionEvidence() != null
                        && request.admissionEvidence().status()
                        == LifecycleAdmissionEvidence.Status.MANAGED
                        ? SqliteManagedAdmissionParticipant.from(
                        operationId, request.admissionEvidence()
                ) : null;
        PreparedOperationDetail detail = preparationDetail(
                request, groups, managed
        );
        DurableOperationWork work = (transaction, operation) -> commit(
                transaction, operation, request
        );
        if (managed != null) {
            work = managed.decorate(work);
        }
        if (groups != null) {
            work = groups.decorate(work);
        }
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
        return preparationDetail(request, groups, null);
    }

    private static PreparedOperationDetail preparationDetail(
            CommandRosterTransitionRequest request,
            @Nullable SqlitePopulationGroupTransitionParticipant groups,
            @Nullable SqliteManagedAdmissionParticipant managed
    ) {
        if (groups == null && managed == null) {
            return new ExactRosterDetail(request);
        }
        if (groups == null) {
            return PreparedOperationDetail.compose(
                    new ExactRosterDetail(request), managed
            );
        }
        if (managed == null) {
            return PreparedOperationDetail.compose(
                    new ExactRosterDetail(request), groups
            );
        }
        return PreparedOperationDetail.compose(
                new ExactRosterDetail(request), groups, managed
        );
    }

    private static boolean positiveReturn(
            CommandRosterTransitionRequest request
    ) {
        return request.groupAdmission().before().state()
                == LifecycleState.ROSTER_STORED
                && request.groupAdmission().after().state()
                == LifecycleState.ACTIVE;
    }

    private static boolean needsExternalGroups(
            CommandRosterTransitionRequest request
    ) {
        return request.admissionEvidence() == null
                || request.admissionEvidence().status()
                != LifecycleAdmissionEvidence.Status.MANAGED
                || request.admissionEvidence().composition() == null
                || request.admissionEvidence().composition().groupRequest()
                == null;
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
        var membership = SqliteCommandRosterEvidence.requireExact(
                transaction,
                lifecycle.profileId(),
                request.familyKey(),
                request.slotId(),
                request.expectedMembershipRevision()
        );
        if (!lifecycle.equals(request.groupAdmission().before())
                || !membership.profileId().equals(lifecycle.profileId())) {
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

