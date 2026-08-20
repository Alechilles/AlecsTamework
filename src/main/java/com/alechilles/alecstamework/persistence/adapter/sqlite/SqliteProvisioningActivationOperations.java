package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseChange;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseChangeCodec;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseChangeEvidence;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonActivation;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChangeCodec;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationDefinition;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationEventCodec;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationLiveBoundary;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationOutcome;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationRequest;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.operation.TimedDurableOperationWork;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Initial provisioned entity activation through the shared live protocol. */
public final class SqliteProvisioningActivationOperations {
    public static final String FEATURE_SCOPE = "provisioning";

    private final SqliteLiveOperationCoordinator workflow;
    private final SqliteOperationEngine operations;
    private final LongSupplier clock;
    @Nullable
    private final SqliteManagedProvisioningActivationAdmission admission;
    private final SqliteLifecycleAdmissionSingleFlight singleFlight =
            new SqliteLifecycleAdmissionSingleFlight();
    private final List<ProjectionConsumer> requiredConsumers;

    public SqliteProvisioningActivationOperations(
            @Nonnull SqliteOperationEngine operations,
            @Nonnull SqliteOperationPublisher publisher,
            @Nonnull LongSupplier clock,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        this(
                operations,
                publisher,
                clock,
                null,
                null,
                null,
                requiredConsumers
        );
    }

    SqliteProvisioningActivationOperations(
            @Nonnull SqliteOperationEngine operations,
            @Nonnull SqliteOperationPublisher publisher,
            @Nonnull LongSupplier clock,
            @Nullable SqliteOperationReader reader,
            @Nullable SqliteLifecycleAdmissionBinding lifecycleAdmission,
            @Nullable SqliteLifecycleAdmissionSourceReader sourceReader,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (operations == null || publisher == null || clock == null
                || requiredConsumers == null) {
            throw new IllegalArgumentException(
                    "Provisioning activation dependencies are required"
            );
        }
        this.workflow = new SqliteLiveOperationCoordinator(
                operations, publisher, clock
        );
        this.operations = operations;
        this.clock = clock;
        admission = reader == null || lifecycleAdmission == null
                || sourceReader == null
                ? null
                : new SqliteManagedProvisioningActivationAdmission(
                        reader, lifecycleAdmission, sourceReader
                );
        this.requiredConsumers = List.copyOf(requiredConsumers);
    }

    /** Starts or resolves one exact receipt-correlated initial spawn. */
    @Nonnull
    public Submission submit(
            @Nonnull OperationId operationId,
            @Nonnull ProvisioningActivationRequest request,
            @Nonnull ProvisioningActivationLiveBoundary liveBoundary
    ) {
        if (operationId == null || request == null
                || liveBoundary == null) {
            throw new IllegalArgumentException(
                    "Complete provisioning activation is required"
            );
        }
        IdempotencyKey idempotencyKey = request.origin().activationKey(
                request.spawnReceiptKey()
        );
        if (admission == null) {
            return execute(operationId, idempotencyKey, request, liveBoundary);
        }
        CompletionStage<OperationWorkflowResult> completion =
                singleFlight.submit(
                        ProvisioningActivationDefinition.KIND,
                        operationId,
                        idempotencyKey,
                        ProvisioningActivationDefinition.INSTANCE.encode(
                                request
                        ),
                        () -> admission.resolve(
                                        operationId,
                                        idempotencyKey,
                                        request
                                )
                                .thenCompose(value -> execute(
                                        operationId,
                                        idempotencyKey,
                                        value,
                                        liveBoundary
                                ).completion())
                );
        return new Submission(
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
                        ))
        );
    }

    private Submission execute(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            ProvisioningActivationRequest request,
            ProvisioningActivationLiveBoundary liveBoundary
    ) {
        SqlitePopulationGroupTransitionParticipant groups =
                needsExternalGroups(request)
                        ? new SqlitePopulationGroupTransitionParticipant(
                        request.groupAdmission()
                ) : null;
        SqliteProvisioningActivationPreparation activation =
                new SqliteProvisioningActivationPreparation(request);
        SqliteManagedAdmissionParticipant managed =
                request.admissionEvidence() != null
                        && request.admissionEvidence().status()
                        == LifecycleAdmissionEvidence.Status.MANAGED
                        ? SqliteManagedAdmissionParticipant.from(
                        operationId, request.admissionEvidence()
                ) : null;
        PreparedOperationDetail detail = preparationDetail(
                groups, activation, managed
        );
        TimedDurableOperationWork<ProvisioningActivationRequest> durable =
                (transaction, operation, payload, committedAtMs) -> commit(
                        transaction, operation, payload, committedAtMs
                );
        if (managed != null) {
            TimedDurableOperationWork<ProvisioningActivationRequest> delegate =
                    durable;
            durable = (transaction, operation, payload, committedAtMs) ->
                    managed.decorate((current, envelope) -> delegate.execute(
                            current, envelope, payload, committedAtMs
                    )).execute(transaction, operation);
        }
        if (groups != null) {
            TimedDurableOperationWork<ProvisioningActivationRequest> delegate =
                    durable;
            durable = (transaction, operation, payload, committedAtMs) ->
                    groups.decorate((current, envelope) -> delegate.execute(
                            current, envelope, payload, committedAtMs
                    )).execute(transaction, operation);
        }
        SqliteLiveOperationCoordinator.Submission submission = workflow.execute(
                        ProvisioningActivationDefinition.INSTANCE,
                        new OperationRequest<>(
                                operationId,
                                idempotencyKey,
                                request,
                                FEATURE_SCOPE,
                                request.groupAdmission()
                                        .before().revision(),
                                SqliteProvisioningActivationContainment
                                        .participants(request),
                                request.requestedAtMs()
                        ),
                        detail,
                        liveBoundary,
                        durable,
                        requiredConsumers,
                        "provisioning_activation"
                );
        CompletionStage<OperationWorkflowResult> completion =
                submission.completion().thenCompose(
                        result -> SqliteProvisioningActivationContainment
                                .contain(operations, clock, result, request)
                );
        return new Submission(
                submission.acceptance(), completion
        );
    }

    private static PreparedOperationDetail preparationDetail(
            @Nullable SqlitePopulationGroupTransitionParticipant groups,
            SqliteProvisioningActivationPreparation activation,
            @Nullable SqliteManagedAdmissionParticipant managed
    ) {
        if (groups == null && managed == null) {
            return activation;
        }
        if (groups == null) {
            return PreparedOperationDetail.compose(activation, managed);
        }
        if (managed == null) {
            return PreparedOperationDetail.compose(groups, activation);
        }
        return PreparedOperationDetail.compose(groups, activation, managed);
    }

    private static boolean needsExternalGroups(
            ProvisioningActivationRequest request
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
            ProvisioningActivationRequest request,
            long committedAtMs
    ) {
        CompanionLifecycle fenced = requireFenced(
                transaction, operation, request
        );
        requireStableSources(transaction, request);
        CompanionProfileProjectionState before =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction, request.origin().profileId()
                );
        requireApplied(
                transaction.identities().promoteAlias(
                        request.targetAlias(),
                        operation.operationId(),
                        committedAtMs
                ),
                "provisioning_activation_alias"
        );
        CompanionLifecycle after = request.finalLifecycle();
        requireApplied(
                transaction.lifecycles().transition(
                        new LifecycleTransition(
                                fenced.revision(),
                                operation.operationId(),
                                after
                        )
                ),
                "provisioning_activation_lifecycle"
        );
        TimedSummonLeaseChange lease = request.timedActivation() == null
                ? null
                : requireApplied(
                        transaction.timedSummons().replace(
                                request.timedActivation()
                                        .expectedPreviousLease() == null
                                        ? null
                                        : request.timedActivation()
                                        .expectedPreviousLease()
                                        .leaseRevision(),
                                request.timedActivation().lease()
                        ),
                        "provisioning_activation_timed_lease"
                );
        CompanionProfileProjectionState afterProfile =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction, request.origin().profileId()
                );
        return events(
                transaction, operation, request, fenced, after, before,
                afterProfile, lease, committedAtMs
        );
    }

    private List<ProjectionEventDraft> events(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            ProvisioningActivationRequest request,
            CompanionLifecycle beforeLifecycle,
            CompanionLifecycle afterLifecycle,
            CompanionProfileProjectionState beforeProfile,
            CompanionProfileProjectionState afterProfile,
            TimedSummonLeaseChange lease,
            long committedAtMs
    ) {
        ArrayList<ProjectionEventDraft> result = new ArrayList<>();
        result.add(ProvisioningActivationEventCodec.draft(
                operation.operationId(),
                new ProvisioningActivationOutcome(
                        request.origin().profileId(),
                        request.targetAlias(),
                        request.targetWorldKey(),
                        afterLifecycle.revision(),
                        request.spawnReceiptKey(),
                        request.timedActivation() == null
                                ? null
                                : request.timedActivation()
                                .lease().sessionId(),
                        committedAtMs
                )
        ));
        result.add(SqliteCompanionProfileProjectionComposer.event(
                operation.operationId(),
                new CompanionProfileProjectionChange(
                        CompanionProfileProjectionChange.Source
                                .LIFECYCLE,
                        request.origin().profileId(),
                        afterLifecycle.revision().value(),
                        beforeProfile,
                        afterProfile,
                        committedAtMs
                )
        ));
        result.add(CompanionLifecycleProjectionChangeCodec.draft(
                operation.operationId(),
                beforeLifecycle,
                afterLifecycle,
                committedAtMs
        ));
        if (lease != null) {
            result.add(TimedSummonLeaseChangeCodec.draft(
                    operation.operationId(),
                    SqliteCommandSemanticEventEvidence.timed(
                            transaction,
                            lease,
                            lease.before() == null
                                    ? null
                                    : beforeLifecycle,
                            afterLifecycle,
                            TimedSummonLeaseChangeEvidence.Reason
                                    .PROVISIONING_ACTIVATED
                    )
            ));
        }
        return List.copyOf(result);
    }

    private CompanionLifecycle requireFenced(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            ProvisioningActivationRequest request
    ) {
        CompanionLifecycle source = request.groupAdmission().before();
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(source.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "provisioning_activation_lifecycle_missing"
                ));
        if (lifecycle.revision().equals(source.revision().next())
                && lifecycle.state() == source.state()
                && lifecycle.location().equals(source.location())
                && operation.operationId().equals(
                lifecycle.activeOperationId()
        )) {
            return lifecycle;
        }
        throw new IllegalStateException(
                "provisioning_activation_lifecycle_fence_mismatch"
        );
    }

    private void requireStableSources(
            SqlitePersistenceTransactionContext transaction,
            ProvisioningActivationRequest request
    ) {
        var identity = transaction.identities()
                .findProfile(request.origin().profileId())
                .orElse(null);
        if (identity == null
                || !request.expectedRoleId().equals(identity.roleId())
                || !request.fullState().payloadHash().matchesUtf8(
                request.fullState().payloadJson()
        )
                || transaction.provisioning()
                .findByOrigin(request.origin())
                .filter(record -> record.profileId().equals(
                        request.origin().profileId()
                ))
                .isEmpty()) {
            throw new IllegalStateException(
                    "provisioning_activation_record_mismatch"
            );
        }
        TimedSummonActivation timed =
                request.timedActivation();
        if (timed != null) {
            SqliteCommandRosterEvidence.requireExact(
                    transaction,
                    request.origin().profileId(),
                    timed.familyKey(),
                    timed.slotId(),
                    timed.expectedMembershipRevision()
            );
        }
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

    /** Writer admission plus eventual shared workflow result. */
    public record Submission(
            @Nonnull SqliteSingleWriter.WriteAcceptance acceptance,
            @Nonnull CompletionStage<OperationWorkflowResult> completion
    ) {
        public Submission {
            if (acceptance == null || completion == null) {
                throw new IllegalArgumentException(
                        "Provisioning activation submission is incomplete"
                );
            }
        }
    }
}
