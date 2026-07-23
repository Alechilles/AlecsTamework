package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseDefinition;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseEventCodec;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseLiveBoundary;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseOutcome;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseRequest;
import com.alechilles.alecstamework.companion.coop.CoopResidencyProjectionChange;
import com.alechilles.alecstamework.companion.coop.CoopResidencyProjectionCodec;
import com.alechilles.alecstamework.companion.coop.CoopSlot;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
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
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** One alias-leased coop-to-live transition through the shared operation protocol. */
public final class SqliteCompanionCoopReleaseOperations {
    public static final String FEATURE_SCOPE = "companion_coop_release";
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("companion_coop_released");

    private final SqliteLiveOperationCoordinator workflow;
    private final SqliteCoopUnknownContainment containment;
    private final List<ProjectionConsumer> requiredConsumers;

    public SqliteCompanionCoopReleaseOperations(
            @Nonnull SqliteOperationEngine operations,
            @Nonnull SqliteOperationPublisher publisher,
            @Nonnull LongSupplier clock,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (operations == null || publisher == null || clock == null
                || requiredConsumers == null) {
            throw new IllegalArgumentException("Coop release dependencies are required");
        }
        workflow = new SqliteLiveOperationCoordinator(operations, publisher, clock);
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
        OperationRequest<CompanionCoopReleaseRequest> request =
                new OperationRequest<>(
                        operationId,
                        idempotencyKey,
                        release,
                        FEATURE_SCOPE,
                        release.expectedLifecycleRevision(),
                        List.of(
                                OperationScope.profile(release.profileId()),
                                OperationScope.coop(
                                        release.sourceResidency()
                                                .slotKey().toString()
                                )
                        ),
                        release.requestedAtMs()
                );
        SqliteLiveOperationCoordinator.Submission submission =
                workflow.execute(
                        CompanionCoopReleaseDefinition.INSTANCE,
                        request,
                        new SqliteCompanionCoopReleasePreparation(release),
                        liveBoundary,
                        this::commitRelease,
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
                operation, release, active, slot, before, after, releasedAtMs
        );
    }

    private List<ProjectionEventDraft> events(
            OperationEnvelope operation,
            CompanionCoopReleaseRequest release,
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
