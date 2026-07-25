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
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** Initial provisioned entity activation through the shared live protocol. */
public final class SqliteProvisioningActivationOperations {
    public static final String FEATURE_SCOPE = "provisioning";

    private final SqliteLiveOperationCoordinator workflow;
    private final SqliteOperationEngine operations;
    private final LongSupplier clock;
    private final List<ProjectionConsumer> requiredConsumers;

    public SqliteProvisioningActivationOperations(
            @Nonnull SqliteOperationEngine operations,
            @Nonnull SqliteOperationPublisher publisher,
            @Nonnull LongSupplier clock,
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
        SqlitePopulationGroupTransitionParticipant groups =
                new SqlitePopulationGroupTransitionParticipant(
                        request.groupAdmission()
                );
        SqliteProvisioningActivationPreparation activation =
                new SqliteProvisioningActivationPreparation(request);
        SqliteLiveOperationCoordinator.Submission submission =
                workflow.execute(
                        ProvisioningActivationDefinition.INSTANCE,
                        new OperationRequest<>(
                                operationId,
                                request.origin().activationKey(
                                        request.spawnReceiptKey()
                                ),
                                request,
                                FEATURE_SCOPE,
                                request.groupAdmission()
                                        .before().revision(),
                                participants(request),
                                request.requestedAtMs()
                        ),
                        PreparedOperationDetail.compose(
                                groups, activation
                        ),
                        liveBoundary,
                        (transaction, operation, payload, committedAtMs) ->
                                groups.decorate((current, envelope) ->
                                        commit(
                                                current,
                                                envelope,
                                                payload,
                                                committedAtMs
                                        )).execute(
                                                transaction, operation
                                        ),
                        requiredConsumers,
                        "provisioning_activation"
                );
        CompletionStage<OperationWorkflowResult> completion =
                submission.completion().thenCompose(
                        result -> containUnknown(result, request)
                );
        return new Submission(
                submission.acceptance(), completion
        );
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
                    ),
                    committedAtMs
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

    private CompletionStage<OperationWorkflowResult> containUnknown(
            OperationWorkflowResult result,
            ProvisioningActivationRequest request
    ) {
        if (result.status() != OperationWorkflowResult.Status.LIVE_UNKNOWN
                || result.operation() == null) {
            return CompletableFuture.completedFuture(result);
        }
        OperationEnvelope operation = result.operation();
        return operations.containUnknown(
                operation,
                operation.failureCode() == null
                        ? "provisioning_activation_live_outcome_unknown"
                        : operation.failureCode(),
                "Provisioning activation could not prove the exact spawn receipt",
                containmentScopes(operation, request),
                clock.getAsLong()
        ).completion().thenApply(containment ->
                containment instanceof
                        com.alechilles.alecstamework.persistence.kernel
                        .PersistenceTransactionResult.Committed<?>
                        ? result
                        : new OperationWorkflowResult(
                                OperationWorkflowResult.Status.LIVE_UNKNOWN,
                                operation,
                                List.of(),
                                new IllegalStateException(
                                        "provisioning_activation_unknown_"
                                                + "containment_failed",
                                        result.failure()
                                )
                        ));
    }

    private List<OperationScope> participants(
            ProvisioningActivationRequest request
    ) {
        TreeSet<OperationScope> scopes = new TreeSet<>();
        scopes.add(OperationScope.profile(
                request.origin().profileId()
        ));
        scopes.add(OperationScope.owner(
                request.groupAdmission().before().ownerId()
        ));
        if (request.timedActivation() != null) {
            scopes.add(OperationScope.commandFamily(
                    request.timedActivation().familyKey()
            ));
        }
        return List.copyOf(scopes);
    }

    private List<OperationScope> containmentScopes(
            OperationEnvelope operation,
            ProvisioningActivationRequest request
    ) {
        ArrayList<OperationScope> scopes = new ArrayList<>();
        scopes.add(OperationScope.operation(operation.operationId()));
        scopes.addAll(participants(request));
        return List.copyOf(scopes);
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
