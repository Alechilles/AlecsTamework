package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.capture.CaptureReleaseSourceEvidence;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseRequest;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.items.persistence.SpawnerCaptureReleaseEvidenceFreezer.FrozenRelease;
import com.alechilles.alecstamework.items.persistence.SpawnerCapturedArtifactReleaseProfilePreparer.Prepared;
import com.alechilles.alecstamework.items.persistence.SpawnerCapturedArtifactReleaseProfilePreparer.Rejected;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.StorageFailure;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PublicOperationSubmission;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Authors one exact filled-spawner release from the canonical current capture snapshot.
 *
 * <p>The capture payload is decoded before placement or operation submission and re-encoded as
 * the source-neutral full-state projection. Placement is resolved exactly once.</p>
 */
public final class SpawnerCapturedArtifactReleaseAuthor {
    private final PersistencePort persistence;
    private final SpawnerCaptureReleaseEvidenceFreezer evidence;
    private final SpawnerCapturedArtifactReleaseProfilePreparer
            profilePreparer;
    private final ResultDispatcher dispatcher;

    /** Creates the production author over replacement facades and UUID-safe feedback. */
    public SpawnerCapturedArtifactReleaseAuthor(
            @Nonnull PersistenceDomainFacades persistence,
            @Nonnull HytaleCapturedArtifactAdapter artifacts,
            @Nonnull HytaleUuidCompletionDispatcher completions,
            @Nonnull LongSupplier clock,
            @Nonnull SpawnerPersistenceCompletionListener listener
    ) {
        this(
                new FacadePersistencePort(persistence),
                new SpawnerCaptureReleaseEvidenceFreezer(
                        artifacts, clock
                ),
                new SpawnerCaptureSnapshotMapper(),
                (worldKey, actorUuid, publishedEffect, result) ->
                        completions.dispatch(
                                worldKey,
                                actorUuid,
                                (world, store, actorRef, player) ->
                                        listener.complete(
                                                result,
                                                publishedEffect,
                                                world,
                                                store,
                                                actorRef,
                                                player
                                        )
                        )
        );
    }

    SpawnerCapturedArtifactReleaseAuthor(
            PersistencePort persistence,
            SpawnerCaptureReleaseEvidenceFreezer evidence,
            SpawnerCaptureSnapshotMapper snapshots,
            ResultDispatcher dispatcher
    ) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.profilePreparer =
                new SpawnerCapturedArtifactReleaseProfilePreparer(
                        snapshots
                );
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    /**
     * Validates, freezes, and submits one captured-artifact release.
     *
     * @return terminal authoring/workflow result; feedback is also UUID-dispatched
     */
    @Nonnull
    public CompletionStage<SpawnerPersistenceAuthorResult> release(
            @Nullable SpawnerCapturedArtifactReleaseIntent intent,
            @Nullable SpawnerReleasePlacementResolver placementResolver
    ) {
        if (intent == null || placementResolver == null) {
            return completed(result(
                    SpawnerPersistenceAuthorResult.Status.INVALID_CONTEXT,
                    null, null, "capture_release_context_missing", null
            ));
        }
        final SpawnerCaptureReleaseContext context;
        try {
            context = intent.frozenContext();
        } catch (RuntimeException | LinkageError failure) {
            return completed(result(
                    SpawnerPersistenceAuthorResult.Status.INVALID_CONTEXT,
                    null, null, contextDetail(failure), failure
            ));
        }
        CompletionStage<SpawnerPersistenceAuthorResult> authored;
        try {
            FrozenRelease frozen = evidence.freeze(intent);
            authored = freezePlacement(
                    intent, placementResolver, frozen
            );
        } catch (RuntimeException | LinkageError failure) {
            authored = completed(result(
                    SpawnerPersistenceAuthorResult.Status.INVALID_CONTEXT,
                    null, null, contextDetail(failure), failure
            ));
        }
        return authored.handle((value, failure) -> failure == null
                        ? value
                        : result(
                                SpawnerPersistenceAuthorResult.Status
                                        .WORKFLOW_FAILED,
                                null, null,
                                "capture_release_author_failed", failure
                        ))
                .thenApply(value -> dispatch(context, value));
    }

    private CompletionStage<SpawnerPersistenceAuthorResult> freezePlacement(
            SpawnerCapturedArtifactReleaseIntent intent,
            SpawnerReleasePlacementResolver placementResolver,
            FrozenRelease frozen
    ) {
        final CompanionSpawnPlacement placement;
        try {
            placement = placementResolver.freeze(intent);
        } catch (RuntimeException | LinkageError failure) {
            return placementFailed(frozen.operationId(), failure);
        }
        if (placement == null || !frozen.context().worldKey().equals(
                placement.worldKey()
        )) {
            return placementFailed(frozen.operationId(), null);
        }
        return resolveProfile(frozen, placement);
    }

    private CompletionStage<SpawnerPersistenceAuthorResult> resolveProfile(
            FrozenRelease frozen,
            CompanionSpawnPlacement placement
    ) {
        return findProfile(frozen.profileId()).thenCompose(read -> {
            if (read instanceof PersistenceReadResult.Failed<?>) {
                return completed(result(
                        SpawnerPersistenceAuthorResult.Status
                                .PROFILE_READ_FAILED,
                        frozen.operationId(), null,
                        "capture_release_profile_read_failed", null
                ));
            }
            if (!(read instanceof PersistenceReadResult.Found<
                    CompanionProfileReadModel> found)) {
                return profileConflict(frozen.operationId());
            }
            return decodeAndSubmit(
                    frozen, found.value(), placement
            );
        });
    }

    private CompletionStage<SpawnerPersistenceAuthorResult> decodeAndSubmit(
            FrozenRelease frozen,
            CompanionProfileReadModel profile,
            CompanionSpawnPlacement placement
    ) {
        SpawnerCapturedArtifactReleaseProfilePreparer.Result prepared =
                profilePreparer.prepare(profile, frozen);
        if (prepared instanceof Rejected rejected) {
            return completed(result(
                    rejected.status(), frozen.operationId(), null,
                    rejected.detail(), rejected.failure()
            ));
        }
        Prepared release = (Prepared) prepared;
        return submit(
                frozen, profile, release.sourceSnapshot(),
                release.projection(), placement
        );
    }

    private CompletionStage<SpawnerPersistenceAuthorResult> submit(
            FrozenRelease frozen,
            CompanionProfileReadModel profile,
            CompanionSnapshot sourceSnapshot,
            SnapshotCodecRegistry.EncodedSnapshot projection,
            CompanionSpawnPlacement placement
    ) {
        CompanionCaptureReleaseRequest request;
        try {
            request = new CompanionCaptureReleaseRequest(
                    frozen.profileId(),
                    profile.lifecycle().revision(),
                    sourceSnapshot,
                    frozen.sourceAlias(),
                    projection,
                    new CaptureReleaseSourceEvidence(
                            frozen.context().actorUuid(),
                            frozen.context().worldKey(),
                            frozen.context().sourceSlot(),
                            frozen.sourceArtifact(),
                            frozen.receiptArtifact()
                    ),
                    frozen.targetAlias(),
                    frozen.ownerAssignment(),
                    placement,
                    frozen.inventoryReceipt(),
                    frozen.spawnReceipt(),
                    frozen.requestedAt()
            );
        } catch (RuntimeException failure) {
            return profileConflict(frozen.operationId());
        }
        final PublicOperationSubmission submitted;
        try {
            submitted = persistence.release(
                    frozen.operationId(), frozen.idempotencyKey(), request
            );
        } catch (RuntimeException | LinkageError failure) {
            return workflowFailure(
                    frozen.operationId(), null, failure
            );
        }
        if (submitted == null || !submitted.accepted()) {
            return completed(result(
                    SpawnerPersistenceAuthorResult.Status.SUBMISSION_REJECTED,
                    frozen.operationId(), null,
                    "capture_release_submission_rejected", null
            ));
        }
        return submitted.completion().handle((workflow, failure) ->
                releaseOutcome(frozen.operationId(), workflow, failure)
        );
    }

    private CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
    findProfile(ProfileId profileId) {
        try {
            CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
                    stage = persistence.findProfile(profileId);
            return stage == null
                    ? completedReadFailure(null)
                    : stage.exceptionally(this::readFailure);
        } catch (RuntimeException | LinkageError failure) {
            return completedReadFailure(failure);
        }
    }

    private CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
    completedReadFailure(Throwable failure) {
        return CompletableFuture.completedFuture(readFailure(failure));
    }

    private PersistenceReadResult<CompanionProfileReadModel> readFailure(
            Throwable failure
    ) {
        return PersistenceReadResult.failed(new StorageFailure(
                StorageFailureKind.UNKNOWN,
                "spawner_capture_release_profile_read_failed",
                "spawner_capture_release_profile_read",
                true,
                failure
        ));
    }

    private SpawnerPersistenceAuthorResult releaseOutcome(
            OperationId operationId,
            OperationWorkflowResult workflow,
            Throwable failure
    ) {
        if (failure == null && workflow != null
                && workflow.status()
                == OperationWorkflowResult.Status.PUBLISHED) {
            return result(
                    SpawnerPersistenceAuthorResult.Status.PUBLISHED,
                    operationId, workflow.status(), null, null
            );
        }
        return result(
                SpawnerPersistenceAuthorResult.Status.WORKFLOW_FAILED,
                operationId,
                workflow == null ? null : workflow.status(),
                "capture_release_workflow_not_published",
                failure != null
                        ? failure
                        : workflow == null ? null : workflow.failure()
        );
    }

    private CompletionStage<SpawnerPersistenceAuthorResult> profileConflict(
            OperationId operationId
    ) {
        return completed(result(
                SpawnerPersistenceAuthorResult.Status.PROFILE_CONFLICT,
                operationId, null,
                "capture_release_profile_not_exact_captured", null
        ));
    }

    private CompletionStage<SpawnerPersistenceAuthorResult> placementFailed(
            OperationId operationId,
            Throwable failure
    ) {
        return completed(result(
                SpawnerPersistenceAuthorResult.Status.PLACEMENT_FAILED,
                operationId, null,
                "capture_release_placement_failed", failure
        ));
    }

    private CompletionStage<SpawnerPersistenceAuthorResult> workflowFailure(
            OperationId operationId,
            OperationWorkflowResult.Status workflow,
            Throwable failure
    ) {
        return completed(result(
                SpawnerPersistenceAuthorResult.Status.WORKFLOW_FAILED,
                operationId, workflow,
                "capture_release_workflow_not_published", failure
        ));
    }

    private String contextDetail(Throwable failure) {
        return failure instanceof
                SpawnerCaptureReleaseEvidenceFreezer.ContextFailure
                ? failure.getMessage()
                : "capture_release_context_invalid";
    }

    private SpawnerPersistenceAuthorResult dispatch(
            SpawnerCaptureReleaseContext context,
            SpawnerPersistenceAuthorResult value
    ) {
        try {
            dispatcher.dispatch(
                    context.worldKey(),
                    context.actorUuid(),
                    context.publishedEffect(),
                    value
            );
        } catch (RuntimeException | LinkageError ignored) {
            // Feedback cannot alter an already resolved durable workflow.
        }
        return value;
    }

    private SpawnerPersistenceAuthorResult result(
            SpawnerPersistenceAuthorResult.Status status,
            OperationId operationId,
            OperationWorkflowResult.Status workflow,
            String detail,
            Throwable failure
    ) {
        return SpawnerPersistenceAuthorResult.of(
                SpawnerPersistenceAuthorResult.Kind.CAPTURE_RELEASE,
                status, operationId, workflow, detail, failure
        );
    }

    private CompletionStage<SpawnerPersistenceAuthorResult> completed(
            SpawnerPersistenceAuthorResult value
    ) {
        return CompletableFuture.completedFuture(value);
    }

    interface PersistencePort {
        CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
        findProfile(ProfileId profileId);

        PublicOperationSubmission release(
                OperationId operationId,
                IdempotencyKey idempotencyKey,
                CompanionCaptureReleaseRequest release
        );
    }

    @FunctionalInterface
    interface ResultDispatcher {
        void dispatch(
                String worldKey,
                java.util.UUID actorUuid,
                SpawnerPublishedEffect publishedEffect,
                SpawnerPersistenceAuthorResult result
        );
    }

    private static final class FacadePersistencePort
            implements PersistencePort {
        private final PersistenceDomainFacades persistence;

        private FacadePersistencePort(PersistenceDomainFacades persistence) {
            this.persistence = Objects.requireNonNull(
                    persistence, "persistence"
            );
        }

        @Override
        public CompletionStage<PersistenceReadResult<
                CompanionProfileReadModel>> findProfile(ProfileId profileId) {
            return persistence.queries().findProfile(profileId);
        }

        @Override
        public PublicOperationSubmission release(
                OperationId operationId,
                IdempotencyKey idempotencyKey,
                CompanionCaptureReleaseRequest release
        ) {
            return persistence.operations().releaseCapturedCompanion(
                    operationId, idempotencyKey, release
            );
        }
    }
}
