package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseDefinition;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseEventCodec;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseLiveBoundary;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseOutcome;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseRequest;
import com.alechilles.alecstamework.companion.coop.CoopResidencyProjectionChange;
import com.alechilles.alecstamework.companion.coop.CoopResidencyProjectionCodec;
import com.alechilles.alecstamework.companion.coop.CoopSlot;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChangeCodec;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.operation.TimedDurableOperationWork;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** One alias-leased coop-to-live transition through the shared operation protocol. */
public final class SqliteCompanionCoopReleaseOperations {
    public static final String FEATURE_SCOPE = "companion_coop_release";
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("companion_coop_released");

    private final SqliteLiveOperationCoordinator workflow;
    @Nullable
    private final SqliteManagedCoopReleaseAdmission admission;
    private final SqliteLifecycleAdmissionSingleFlight singleFlight =
            new SqliteLifecycleAdmissionSingleFlight();
    private final SqliteCoopUnknownContainment containment;
    private final List<ProjectionConsumer> requiredConsumers;

    public SqliteCompanionCoopReleaseOperations(
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

    SqliteCompanionCoopReleaseOperations(
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
            throw new IllegalArgumentException("Coop release dependencies are required");
        }
        workflow = new SqliteLiveOperationCoordinator(operations, publisher, clock);
        admission = reader == null || lifecycleAdmission == null
                || sourceReader == null
                ? null
                : new SqliteManagedCoopReleaseAdmission(
                        reader, lifecycleAdmission, sourceReader
                );
        containment = new SqliteCoopUnknownContainment(operations, clock);
        this.requiredConsumers = List.copyOf(requiredConsumers);
    }

    /** Starts or resumes one exact spawn-receipt-correlated coop release. */
    @Nonnull
    public Submission submit(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CompanionCoopReleaseRequest release,
            @Nonnull CompanionCoopReleaseLiveBoundary liveBoundary
    ) {
        if (operationId == null || idempotencyKey == null
                || release == null || liveBoundary == null) {
            throw new IllegalArgumentException("Complete coop release is required");
        }
        if (admission == null) {
            return release.admissionEvidence() == null
                    ? execute(operationId, idempotencyKey, release, liveBoundary)
                    : rejected("coop_release_admission_not_wired");
        }
        CompletionStage<OperationWorkflowResult> admitted =
                singleFlight.submit(
                        CompanionCoopReleaseDefinition.KIND,
                        operationId,
                        idempotencyKey,
                        CompanionCoopReleaseDefinition.INSTANCE.encode(
                                release
                        ),
                        () -> admission.resolve(
                                        operationId, idempotencyKey, release
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
                admitted.exceptionally(failure ->
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
            CompanionCoopReleaseRequest release,
            CompanionCoopReleaseLiveBoundary liveBoundary
    ) {
        SqliteCompanionCoopReleasePreparation base =
                new SqliteCompanionCoopReleasePreparation(release);
        SqliteManagedAdmissionParticipant managed =
                release.admissionEvidence() != null
                        && release.admissionEvidence().status()
                        == LifecycleAdmissionEvidence.Status.MANAGED
                        ? SqliteManagedAdmissionParticipant.from(
                        operationId, release.admissionEvidence()
                ) : null;
        PreparedOperationDetail detail = managed == null
                ? base : PreparedOperationDetail.compose(base, managed);
        TimedDurableOperationWork<CompanionCoopReleaseRequest> durable =
                (transaction, operation, payload, committedAtMs) ->
                        commitRelease(
                                transaction,
                                operation,
                                payload,
                                committedAtMs
                        );
        if (managed != null) {
            TimedDurableOperationWork<CompanionCoopReleaseRequest> delegated =
                    durable;
            durable = (transaction, operation, payload, committedAtMs) ->
                    managed.decorate((current, envelope) -> delegated.execute(
                            current, envelope, payload, committedAtMs
                    )).execute(transaction, operation);
        }
        SqliteLiveOperationCoordinator.Submission submission =
                workflow.execute(
                        CompanionCoopReleaseDefinition.INSTANCE,
                        new OperationRequest<>(
                                operationId,
                                idempotencyKey,
                                release,
                                FEATURE_SCOPE,
                                release.expectedLifecycleRevision(),
                                participants(release),
                                release.requestedAtMs()
                        ),
                        detail,
                        liveBoundary,
                        durable,
                        requiredConsumers,
                        "companion_coop_release"
                );
        CompletionStage<OperationWorkflowResult> completion =
                submission.completion().thenCompose(result ->
                        containment.containIfUnknown(
                                result,
                                release.profileId(),
                                release.sourceResidency().slotKey(),
                                "Coop release could not prove whether the exact "
                                        + "entity insertion completed"
                        )
                );
        return new Submission(submission.acceptance(), completion);
    }

    private List<OperationScope> participants(
            CompanionCoopReleaseRequest release
    ) {
        TreeSet<OperationScope> scopes = new TreeSet<>();
        scopes.add(OperationScope.profile(release.profileId()));
        scopes.add(OperationScope.coop(
                release.sourceResidency().slotKey().toString()
        ));
        if (release.admissionEvidence() != null
                && release.admissionEvidence().status()
                == LifecycleAdmissionEvidence.Status.MANAGED
                && release.admissionEvidence().payload() != null) {
            OwnerId owner = release.admissionEvidence().payload().ownerId();
            if (owner != null) {
                scopes.add(OperationScope.owner(owner));
            }
            OwnerId sourceOwner = release.admissionEvidence().payload()
                    .sourceOwnerId();
            if (sourceOwner != null) {
                scopes.add(OperationScope.owner(sourceOwner));
            }
        }
        return List.copyOf(scopes);
    }

    private static Submission rejected(String code) {
        return new Submission(
                SqliteSingleWriter.WriteAcceptance.REJECTED,
                CompletableFuture.completedFuture(
                        SqliteOperationResults.failed(
                                OperationWorkflowResult.Status.PREPARE_FAILED,
                                null,
                                List.of(),
                                new IllegalStateException(code)
                        )
                )
        );
    }

    private List<ProjectionEventDraft> commitRelease(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionCoopReleaseRequest release,
            long releasedAtMs
    ) {
        CompanionLifecycle fenced = requireFencedLifecycle(
                transaction, operation, release
        );
        CompanionProfileProjectionState before =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction, release.profileId()
                );
        requireApplied(
                transaction.identities().promoteAlias(
                        release.targetAlias(),
                        operation.operationId(),
                        releasedAtMs
                ),
                "coop_release_alias_promotion"
        );
        requireApplied(
                transaction.snapshots().retireCurrent(
                        release.sourceSnapshot().snapshotId()
                ),
                "coop_release_snapshot_retirement"
        );
        CoopSlot slot = requireApplied(
                transaction.coops().commitRelease(
                        release.sourceResidency().slotKey(),
                        release.profileId(),
                        operation.operationId(),
                        releasedAtMs
                ),
                "coop_release_residency"
        );
        CompanionLifecycle active = new CompanionLifecycle(
                release.profileId(),
                fenced.ownerId(),
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(
                        release.targetAlias().toString(),
                        release.targetWorldKey()
                ),
                fenced.revision().next(),
                null,
                releasedAtMs,
                fenced.lastReconciledGeneration(),
                fenced.quarantineIncidentId(),
                fenced.ownerId() == null ? null : release.targetWorldKey()
        );
        requireApplied(
                transaction.lifecycles().transition(new LifecycleTransition(
                        fenced.revision(), operation.operationId(), active
                )),
                "coop_release_lifecycle"
        );
        CompanionProfileProjectionState after =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction, release.profileId()
                );
        return events(
                operation, release, fenced, active, slot, before, after,
                releasedAtMs
        );
    }

    private List<ProjectionEventDraft> events(
            OperationEnvelope operation,
            CompanionCoopReleaseRequest release,
            CompanionLifecycle fenced,
            CompanionLifecycle active,
            CoopSlot slot,
            CompanionProfileProjectionState before,
            CompanionProfileProjectionState after,
            long releasedAtMs
    ) {
        CompanionCoopReleaseOutcome outcome = new CompanionCoopReleaseOutcome(
                release.profileId(),
                release.sourceResidency().slotKey(),
                release.sourceSnapshot().snapshotId(),
                release.targetAlias(),
                release.targetWorldKey(),
                active.revision(),
                slot.residencyRevision(),
                release.spawnReceiptKey(),
                releasedAtMs
        );
        CoopResidencyProjectionChange coopChange =
                new CoopResidencyProjectionChange(
                        release.sourceResidency().slotKey(),
                        slot.residencyRevision(),
                        release.sourceResidency(),
                        null,
                        releasedAtMs
                );
        CompanionProfileProjectionChange profileChange =
                new CompanionProfileProjectionChange(
                        CompanionProfileProjectionChange.Source.LIFECYCLE,
                        release.profileId(),
                        active.revision().value(),
                        before,
                        after,
                        releasedAtMs
                );
        return List.of(
                new ProjectionEventDraft(
                        operation.operationId(),
                        EVENT_TYPE,
                        "coop-release-result:" + release.profileId(),
                        active.revision().value(),
                        CompanionCoopReleaseEventCodec.VERSION,
                        CompanionCoopReleaseEventCodec.encode(outcome),
                        releasedAtMs
                ),
                new ProjectionEventDraft(
                        operation.operationId(),
                        CoopResidencyProjectionCodec.EVENT_TYPE,
                        CoopResidencyProjectionCodec.aggregateId(
                                release.sourceResidency().slotKey()
                        ),
                        coopChange.slotRevision(),
                        CoopResidencyProjectionCodec.VERSION,
                        CoopResidencyProjectionCodec.encode(coopChange),
                        releasedAtMs
                ),
                SqliteCompanionProfileProjectionComposer.event(
                        operation.operationId(), profileChange
                ),
                CompanionLifecycleProjectionChangeCodec.draft(
                        operation.operationId(),
                        fenced,
                        active,
                        releasedAtMs
                )
        );
    }

    private CompanionLifecycle requireFencedLifecycle(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionCoopReleaseRequest release
    ) {
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(release.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "coop_release_lifecycle_missing"
                ));
        if (!lifecycle.revision().equals(
                release.expectedLifecycleRevision().next()
        )
                || lifecycle.state() != LifecycleState.COOP
                || !operation.operationId().equals(
                lifecycle.activeOperationId()
        )
                || lifecycle.quarantined()) {
            throw new IllegalStateException(
                    "coop_release_lifecycle_fence_mismatch"
            );
        }
        return lifecycle;
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

    /** Writer admission for preparation plus the eventual exact workflow result. */
    public record Submission(
            @Nonnull SqliteSingleWriter.WriteAcceptance acceptance,
            @Nonnull CompletionStage<OperationWorkflowResult> completion
    ) {
        public Submission {
            if (acceptance == null || completion == null) {
                throw new IllegalArgumentException("Coop release submission is incomplete");
            }
        }
    }
}
