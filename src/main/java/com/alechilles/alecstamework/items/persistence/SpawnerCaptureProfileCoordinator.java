package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.items.persistence.SpawnerCaptureEvidenceFreezer.FrozenCapture;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PublicOperationSubmission;
import com.alechilles.alecstamework.persistence.operation.StablePersistenceIds;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * Gates live spawner capture against canonical profile identity and repairs
 * only safe current-alias location drift.
 *
 * <p>The coordinator deliberately refuses to adopt or promote historical
 * aliases. Reconciliation uses the same durable profile operation as other
 * lifecycle writers, then rereads and revalidates before capture continues.</p>
 */
final class SpawnerCaptureProfileCoordinator {
    private static final String LIVE_RECONCILIATION =
            "spawner-live-reconciliation:v1";

    private final SpawnerCaptureAuthor.PersistencePort persistence;
    private final Function<ProfileId, CompletionStage<
            PersistenceReadResult<CompanionProfileReadModel>>> profiles;
    private final SpawnerCaptureProfileGate gate =
            new SpawnerCaptureProfileGate();

    SpawnerCaptureProfileCoordinator(
            SpawnerCaptureAuthor.PersistencePort persistence,
            Function<ProfileId, CompletionStage<
                    PersistenceReadResult<CompanionProfileReadModel>>> profiles
    ) {
        this.persistence = Objects.requireNonNull(
                persistence, "persistence"
        );
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    CompletionStage<SpawnerPersistenceAuthorResult> authorize(
            SpawnerCaptureContext context,
            FrozenCapture frozen,
            CompanionProfileReadModel profile,
            Function<CompanionProfileReadModel, CompletionStage<
                    SpawnerPersistenceAuthorResult>> capture
    ) {
        SpawnerCaptureProfileGate.Decision decision = gate.evaluate(
                context, profile, frozen.requestedAt()
        );
        if (decision.status() == SpawnerCaptureProfileGate.Status.EXACT) {
            return capture.apply(profile);
        }
        if (decision.status() == SpawnerCaptureProfileGate.Status.CONFLICT) {
            return completed(conflict(frozen, decision.detail()));
        }
        return reconcile(context, frozen, decision.reconciliation(), capture);
    }

    private CompletionStage<SpawnerPersistenceAuthorResult> reconcile(
            SpawnerCaptureContext context,
            FrozenCapture frozen,
            CompanionProfileMutation.ReconcileLoaded reconciliation,
            Function<CompanionProfileReadModel, CompletionStage<
                    SpawnerPersistenceAuthorResult>> capture
    ) {
        String[] parts = {
                context.profileId().toString(),
                Long.toString(
                        reconciliation.expectedLifecycleRevision().value()
                ),
                context.sourceAlias().toString(),
                context.worldKey()
        };
        final PublicOperationSubmission submitted;
        try {
            submitted = persistence.reconcile(
                    StablePersistenceIds.operationId(
                            LIVE_RECONCILIATION, parts
                    ),
                    StablePersistenceIds.idempotencyKey(
                            LIVE_RECONCILIATION, parts
                    ),
                    reconciliation
            );
        } catch (RuntimeException | LinkageError failure) {
            return completed(result(
                    SpawnerPersistenceAuthorResult.Status.WORKFLOW_FAILED,
                    frozen,
                    null,
                    "capture_live_reconciliation_failed",
                    failure
            ));
        }
        if (submitted == null || !submitted.accepted()) {
            return completed(result(
                    SpawnerPersistenceAuthorResult.Status.SUBMISSION_REJECTED,
                    frozen,
                    null,
                    "capture_live_reconciliation_rejected",
                    null
            ));
        }
        return submitted.completion()
                .handle(ReconciliationCompletion::new)
                .thenCompose(completion -> afterPublished(
                        context, frozen, completion, capture
                ));
    }

    private CompletionStage<SpawnerPersistenceAuthorResult> afterPublished(
            SpawnerCaptureContext context,
            FrozenCapture frozen,
            ReconciliationCompletion completion,
            Function<CompanionProfileReadModel, CompletionStage<
                    SpawnerPersistenceAuthorResult>> capture
    ) {
        OperationWorkflowResult workflow = completion.workflow();
        if (completion.failure() != null || workflow == null
                || workflow.status()
                != OperationWorkflowResult.Status.PUBLISHED) {
            return completed(result(
                    SpawnerPersistenceAuthorResult.Status.WORKFLOW_FAILED,
                    frozen,
                    workflow == null ? null : workflow.status(),
                    "capture_live_reconciliation_not_published",
                    completion.failure() != null
                            ? completion.failure()
                            : workflow.failure()
            ));
        }
        return profiles.apply(context.profileId()).thenCompose(read -> {
            if (!(read instanceof PersistenceReadResult.Found<
                    CompanionProfileReadModel> found)) {
                return completed(result(
                        SpawnerPersistenceAuthorResult.Status
                                .PROFILE_READ_FAILED,
                        frozen,
                        workflow.status(),
                        "capture_reconciled_profile_read_failed",
                        null
                ));
            }
            SpawnerCaptureProfileGate.Decision decision = gate.evaluate(
                    context, found.value(), frozen.requestedAt()
            );
            if (decision.status() == SpawnerCaptureProfileGate.Status.EXACT) {
                return capture.apply(found.value());
            }
            String detail = decision.status()
                    == SpawnerCaptureProfileGate.Status.CONFLICT
                    ? decision.detail()
                    : "capture_live_reconciliation_not_applied";
            return completed(conflict(frozen, detail));
        });
    }

    private SpawnerPersistenceAuthorResult conflict(
            FrozenCapture frozen,
            String detail
    ) {
        return result(
                SpawnerPersistenceAuthorResult.Status.PROFILE_CONFLICT,
                frozen, null, detail, null
        );
    }

    private SpawnerPersistenceAuthorResult result(
            SpawnerPersistenceAuthorResult.Status status,
            FrozenCapture frozen,
            @Nullable OperationWorkflowResult.Status workflow,
            @Nullable String detail,
            @Nullable Throwable failure
    ) {
        return SpawnerPersistenceAuthorResult.of(
                SpawnerPersistenceAuthorResult.Kind.CAPTURE,
                status,
                frozen.operationId(),
                workflow,
                detail,
                failure
        );
    }

    private CompletionStage<SpawnerPersistenceAuthorResult> completed(
            SpawnerPersistenceAuthorResult result
    ) {
        return CompletableFuture.completedFuture(result);
    }

    private record ReconciliationCompletion(
            @Nullable OperationWorkflowResult workflow,
            @Nullable Throwable failure
    ) {
    }
}
