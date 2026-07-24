package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.capture.CaptureSourceEvidence;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.items.persistence.SpawnerCaptureEvidenceFreezer.FrozenCapture;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.StorageFailure;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PublicOperationSubmission;
import com.alechilles.alecstamework.persistence.operation.StablePersistenceIds;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Authors one receipt-first spawner capture after atomically adopting an absent live profile.
 *
 * <p>All mutable evidence is frozen before the first asynchronous read. Adoption must publish
 * before capture is submitted, keeping profile creation separate and restart-safe.</p>
 */
public final class SpawnerCaptureAuthor {
    private static final String ADOPTION = "spawner-live-adoption:v1";
    private final PersistencePort persistence;
    private final SpawnerCaptureEvidenceFreezer evidence;
    private final SpawnerCaptureAdoptionFactory adoptions;
    private final SpawnerCapturePublishedEventPublisher eventPublisher;
    private final ResultDispatcher dispatcher;

    /** Creates the production author over replacement facades and UUID-safe feedback. */
    public SpawnerCaptureAuthor(
            @Nonnull PersistenceDomainFacades persistence,
            @Nonnull TameworkFullStateSnapshotReader snapshots,
            @Nonnull HytaleCapturedArtifactAdapter artifacts,
            @Nonnull HytaleUuidCompletionDispatcher completions,
            @Nonnull LongSupplier clock,
            @Nonnull SpawnerPersistenceCompletionListener listener,
            @Nonnull SpawnerCapturePublishedEventSink events
    ) {
        this(
                new SpawnerCaptureFacadePersistencePort(persistence),
                new SpawnerCaptureEvidenceFreezer(
                        snapshots,
                        artifacts,
                        new SpawnerCaptureSnapshotMapper(),
                        clock
                ),
                new SpawnerCaptureAdoptionFactory(),
                events,
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

    SpawnerCaptureAuthor(
            PersistencePort persistence,
            SpawnerCaptureEvidenceFreezer evidence,
            SpawnerCaptureAdoptionFactory adoptions,
            SpawnerCapturePublishedEventSink events,
            ResultDispatcher dispatcher
    ) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.adoptions = Objects.requireNonNull(adoptions, "adoptions");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.eventPublisher = new SpawnerCapturePublishedEventPublisher(
                this::findProfile,
                Objects.requireNonNull(events, "events")
        );
    }

    /**
     * Freezes and submits one exact live-NPC capture intent.
     *
     * @return terminal authoring/workflow result; feedback is also UUID-dispatched
     */
    @Nonnull
    public CompletionStage<SpawnerPersistenceAuthorResult> capture(
            @Nullable SpawnerCaptureIntent intent
    ) {
        if (intent == null) {
            return completed(result(
                    SpawnerPersistenceAuthorResult.Status.INVALID_CONTEXT,
                    null, null, "capture_intent_missing", null
            ));
        }
        final SpawnerCaptureIntent canonicalIntent;
        final SpawnerCaptureContext context;
        try {
            canonicalIntent = canonicalize(intent);
            context = canonicalIntent.frozenContext();
        } catch (RuntimeException | LinkageError failure) {
            return completed(result(
                    SpawnerPersistenceAuthorResult.Status.EVIDENCE_FAILED,
                    null, null, evidenceDetail(failure), failure
            ));
        }
        CompletionStage<SpawnerPersistenceAuthorResult> authored;
        try {
            authored = resolveProfile(evidence.freeze(canonicalIntent));
        } catch (RuntimeException | LinkageError failure) {
            authored = completed(result(
                    SpawnerPersistenceAuthorResult.Status.EVIDENCE_FAILED,
                    null, null, evidenceDetail(failure), failure
            ));
        }
        return authored.handle((value, failure) -> failure == null
                        ? value
                        : result(
                                SpawnerPersistenceAuthorResult.Status
                                        .WORKFLOW_FAILED,
                                null, null,
                                "capture_author_failed", failure
                        ))
                .thenApply(value -> dispatch(context, value));
    }

    private SpawnerCaptureIntent canonicalize(
            SpawnerCaptureIntent intent
    ) {
        Optional<CompanionProfileProjectionState> projected =
                persistence.projectedProfile(intent.sourceAlias());
        return projected == null || projected.isEmpty()
                ? intent
                : intent.withProfileId(projected.orElseThrow().profileId());
    }

    private CompletionStage<SpawnerPersistenceAuthorResult> resolveProfile(
            FrozenCapture frozen
    ) {
        SpawnerCaptureContext context = frozen.context();
        return findProfile(context.profileId()).thenCompose(read -> {
            if (read instanceof PersistenceReadResult.Found<
                    CompanionProfileReadModel> found) {
                return submitCapture(context, frozen, found.value());
            }
            if (read instanceof PersistenceReadResult.Failed<?>) {
                return completed(result(
                        SpawnerPersistenceAuthorResult.Status
                                .PROFILE_READ_FAILED,
                        frozen.operationId(), null,
                        "capture_profile_read_failed", null
                ));
            }
            return adopt(context, frozen);
        });
    }

    private CompletionStage<SpawnerPersistenceAuthorResult> adopt(
            SpawnerCaptureContext context,
            FrozenCapture frozen
    ) {
        final CompanionProfileMutation.AdoptLive adoption;
        try {
            adoption = adoptions.create(context, frozen);
        } catch (RuntimeException failure) {
            return profileConflict(frozen.operationId(), failure);
        }
        String[] parts = {
                context.profileId().toString(),
                context.sourceAlias().toString(),
                context.worldKey()
        };
        final PublicOperationSubmission submitted;
        try {
            submitted = persistence.adopt(
                    StablePersistenceIds.operationId(ADOPTION, parts),
                    StablePersistenceIds.idempotencyKey(ADOPTION, parts),
                    adoption
            );
        } catch (RuntimeException | LinkageError failure) {
            return completed(result(
                    SpawnerPersistenceAuthorResult.Status.ADOPTION_FAILED,
                    frozen.operationId(), null,
                    "capture_adoption_failed", failure
            ));
        }
        if (submitted == null || !submitted.accepted()) {
            return completed(result(
                    SpawnerPersistenceAuthorResult.Status.ADOPTION_REJECTED,
                    frozen.operationId(), null,
                    "capture_adoption_rejected", null
            ));
        }
        return submitted.completion()
                .handle(WorkflowCompletion::new)
                .thenCompose(completion -> afterAdoption(
                        context, frozen, completion
                ));
    }

    private CompletionStage<SpawnerPersistenceAuthorResult> afterAdoption(
            SpawnerCaptureContext context,
            FrozenCapture frozen,
            WorkflowCompletion completion
    ) {
        OperationWorkflowResult workflow = completion.workflow();
        if (completion.failure() != null || workflow == null
                || workflow.status()
                != OperationWorkflowResult.Status.PUBLISHED) {
            return completed(result(
                    SpawnerPersistenceAuthorResult.Status.ADOPTION_FAILED,
                    frozen.operationId(),
                    workflow == null ? null : workflow.status(),
                    "capture_adoption_not_published",
                    completion.failure() != null
                            ? completion.failure()
                            : workflow.failure()
            ));
        }
        return findProfile(context.profileId()).thenCompose(read -> {
            if (read instanceof PersistenceReadResult.Found<
                    CompanionProfileReadModel> found) {
                return submitCapture(context, frozen, found.value());
            }
            return completed(result(
                    SpawnerPersistenceAuthorResult.Status.PROFILE_READ_FAILED,
                    frozen.operationId(), null,
                    "capture_adopted_profile_read_failed", null
            ));
        });
    }

    private CompletionStage<SpawnerPersistenceAuthorResult> submitCapture(
            SpawnerCaptureContext context,
            FrozenCapture frozen,
            CompanionProfileReadModel profile
    ) {
        CompanionLifecycle lifecycle = profile.lifecycle();
        if (!exactLiveProfile(context, profile, lifecycle)) {
            return profileConflict(frozen.operationId(), null);
        }
        CompanionSnapshot snapshot = new CompanionSnapshot(
                frozen.snapshotId(),
                context.profileId(),
                CompanionCaptureRequest.SNAPSHOT_KIND,
                frozen.encoded().payloadVersion(),
                frozen.encoded().payloadJson(),
                frozen.encoded().payloadHash(),
                lifecycle.revision().next(),
                true,
                frozen.requestedAt()
        );
        CompanionCaptureRequest request = new CompanionCaptureRequest(
                context.profileId(),
                lifecycle.revision(),
                context.resultingOwnerId(),
                context.sourceAlias(),
                context.worldKey(),
                snapshot,
                frozen.artifact(),
                new CaptureSourceEvidence(
                        context.actorUuid(),
                        context.worldKey(),
                        context.sourceSlot(),
                        frozen.source().itemId(),
                        frozen.source().quantity(),
                        frozen.source().artifactHash(),
                        frozen.snapshotId().toString()
                ),
                frozen.requestedAt()
        );
        final PublicOperationSubmission submitted;
        try {
            submitted = persistence.capture(
                    frozen.operationId(), frozen.idempotencyKey(), request
            );
        } catch (RuntimeException | LinkageError failure) {
            return workflowFailure(frozen.operationId(), null, failure);
        }
        if (submitted == null || !submitted.accepted()) {
            return completed(result(
                    SpawnerPersistenceAuthorResult.Status.SUBMISSION_REJECTED,
                    frozen.operationId(), null,
                    "capture_submission_rejected", null
            ));
        }
        return submitted.completion()
                .handle((workflow, failure) -> captureOutcome(
                        frozen.operationId(), workflow, failure
                ))
                .thenCompose(outcome -> eventPublisher.publishIfNeeded(
                        outcome, context, frozen
                ));
    }

    private SpawnerPersistenceAuthorResult captureOutcome(
            OperationId operationId,
            OperationWorkflowResult workflow,
            Throwable failure
    ) {
        if (failure != null || workflow == null) {
            return result(
                    SpawnerPersistenceAuthorResult.Status.WORKFLOW_FAILED,
                    operationId, null,
                    "capture_workflow_not_published", failure
            );
        }
        if (workflow.status() == OperationWorkflowResult.Status.PUBLISHED) {
            return result(
                    SpawnerPersistenceAuthorResult.Status.PUBLISHED,
                    operationId, workflow.status(), null, null
            );
        }
        if (workflow.status() == OperationWorkflowResult.Status.COMPENSATED) {
            return result(
                    SpawnerPersistenceAuthorResult.Status.COMPENSATED,
                    operationId, workflow.status(), null, null
            );
        }
        return result(
                SpawnerPersistenceAuthorResult.Status.WORKFLOW_FAILED,
                operationId, workflow.status(),
                "capture_workflow_not_published", workflow.failure()
        );
    }

    private boolean exactLiveProfile(
            SpawnerCaptureContext context,
            CompanionProfileReadModel profile,
            CompanionLifecycle lifecycle
    ) {
        CompanionAlias alias = profile.currentAlias();
        return profile.identity().profileId().equals(context.profileId())
                && alias != null
                && alias.alias().equals(context.sourceAlias())
                && alias.state() == CompanionAlias.State.CURRENT
                && lifecycle.state() == LifecycleState.ACTIVE
                && lifecycle.location().equals(LifecycleLocation.liveEntity(
                context.sourceAlias().toString(), context.worldKey()
        ))
                && lifecycle.activeOperationId() == null
                && !lifecycle.quarantined();
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
                "spawner_profile_read_failed",
                "spawner_profile_read",
                true,
                failure
        ));
    }

    private CompletionStage<SpawnerPersistenceAuthorResult> profileConflict(
            OperationId operationId,
            Throwable failure
    ) {
        return completed(result(
                SpawnerPersistenceAuthorResult.Status.PROFILE_CONFLICT,
                operationId, null,
                "capture_profile_not_exact_live", failure
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
                "capture_workflow_not_published", failure
        ));
    }

    private SpawnerPersistenceAuthorResult dispatch(
            SpawnerCaptureContext context,
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
                SpawnerPersistenceAuthorResult.Kind.CAPTURE,
                status, operationId, workflow, detail, failure
        );
    }

    private CompletionStage<SpawnerPersistenceAuthorResult> completed(
            SpawnerPersistenceAuthorResult value
    ) {
        return CompletableFuture.completedFuture(value);
    }

    private String evidenceDetail(Throwable failure) {
        return failure instanceof
                SpawnerCaptureEvidenceFreezer.EvidenceFailure
                ? failure.getMessage()
                : "capture_evidence_failed";
    }

    interface PersistencePort {
        Optional<CompanionProfileProjectionState> projectedProfile(
                com.alechilles.alecstamework.companion.identity.NpcAlias alias
        );

        CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
        findProfile(ProfileId profileId);

        PublicOperationSubmission adopt(
                OperationId operationId,
                IdempotencyKey idempotencyKey,
                CompanionProfileMutation.AdoptLive adoption
        );

        PublicOperationSubmission capture(
                OperationId operationId,
                IdempotencyKey idempotencyKey,
                CompanionCaptureRequest capture
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

    private record WorkflowCompletion(
            @Nullable OperationWorkflowResult workflow,
            @Nullable Throwable failure
    ) {
    }
}
