package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationRequest;
import com.alechilles.alecstamework.companion.restoration.RestorationProjection;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PublicOperationSubmission;
import com.alechilles.alecstamework.persistence.operation.StablePersistenceIds;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Authors free death revival and lost recovery from one exact current dormant snapshot.
 *
 * <p>Placement, alias, receipt, IDs, and signed request time are frozen before the asynchronous
 * profile read. The source snapshot is normalized to a complete projection before submission.
 * Terminal feedback crosses the asynchronous boundary using only actor UUID and world key.</p>
 */
public final class FreeCompanionRestorationAuthor {
    private static final String RESTORATION = "companion-restoration:v1";

    private final PersistencePort persistence;
    private final TameworkDormantSnapshotFactsReader factsReader;
    private final TameworkRestorationSnapshotResolver resolver;
    private final LongSupplier clock;
    private final ReleasedRestorationPolicy policy;
    private final ResultDispatcher dispatcher;

    /** Creates the production author over replacement facades and UUID-safe feedback. */
    public FreeCompanionRestorationAuthor(
            @Nonnull PersistenceDomainFacades persistence,
            @Nonnull TameworkDormantSnapshotFactsReader factsReader,
            @Nonnull TameworkRestorationSnapshotResolver resolver,
            @Nonnull HytaleUuidCompletionDispatcher completions,
            @Nonnull LongSupplier clock,
            @Nonnull ReleasedRestorationPolicy policy,
            @Nonnull CompanionRestorationCompletionListener listener
    ) {
        this(
                new FacadeRestorationPersistencePort(persistence),
                factsReader,
                resolver,
                clock,
                policy,
                (worldKey, actorUuid, result) -> completions.dispatch(
                        worldKey,
                        actorUuid,
                        (world, store, actorRef, player) -> listener.complete(
                                result, world, store, actorRef, player
                        )
                )
        );
    }

    FreeCompanionRestorationAuthor(
            PersistencePort persistence,
            TameworkDormantSnapshotFactsReader factsReader,
            TameworkRestorationSnapshotResolver resolver,
            LongSupplier clock,
            ReleasedRestorationPolicy policy,
            ResultDispatcher dispatcher
    ) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.factsReader = Objects.requireNonNull(
                factsReader, "factsReader"
        );
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    /** Freezes and submits one exact free restoration intent. */
    @Nonnull
    public CompletionStage<CompanionLifecycleAuthorResult> restore(
            @Nullable Intent intent
    ) {
        if (intent == null) {
            return completed(result(
                    CompanionLifecycleAuthorResult.Status.INVALID_CONTEXT,
                    null, null, "restoration_intent_missing", null
            ));
        }
        final FrozenRestoration frozen;
        try {
            frozen = freeze(intent);
        } catch (RuntimeException | LinkageError failure) {
            return dispatchCompleted(intent, result(
                    CompanionLifecycleAuthorResult.Status.INVALID_CONTEXT,
                    null, null, "restoration_intent_invalid", failure
            ));
        }
        CompletionStage<CompanionLifecycleAuthorResult> authored;
        try {
            authored = persistence.findProfile(frozen.profileId())
                    .thenCompose(read -> author(frozen, read))
                    .exceptionally(failure -> result(
                            CompanionLifecycleAuthorResult.Status
                                    .PROFILE_READ_FAILED,
                            frozen.operationId(), null,
                            "restoration_profile_read_failed", failure
                    ));
        } catch (RuntimeException | LinkageError failure) {
            authored = completed(result(
                    CompanionLifecycleAuthorResult.Status.PROFILE_READ_FAILED,
                    frozen.operationId(), null,
                    "restoration_profile_read_failed", failure
            ));
        }
        return authored.thenApply(value -> dispatch(frozen, value));
    }

    private FrozenRestoration freeze(Intent intent) {
        CompanionSpawnPlacement source = intent.placement();
        CompanionSpawnPlacement placement = new CompanionSpawnPlacement(
                source.worldKey(),
                source.x(), source.y(), source.z(),
                source.pitchRadians(), source.yawRadians(), source.rollRadians()
        );
        long requestedAt = clock.getAsLong();
        String[] parts = intentParts(intent, placement);
        return new FrozenRestoration(
                intent.profileId(),
                intent.actorUuid(),
                intent.actorWorldKey(),
                requestedAt,
                StablePersistenceIds.operationId(RESTORATION, parts),
                StablePersistenceIds.idempotencyKey(RESTORATION, parts),
                StablePersistenceIds.targetAlias(RESTORATION, parts),
                placement,
                StablePersistenceIds.receipt(RESTORATION, parts)
        );
    }

    private CompletionStage<CompanionLifecycleAuthorResult> author(
            FrozenRestoration frozen,
            PersistenceReadResult<CompanionProfileReadModel> read
    ) {
        if (read instanceof PersistenceReadResult.Failed<?>
                || read instanceof PersistenceReadResult.Absent<?>) {
            return completed(result(
                    CompanionLifecycleAuthorResult.Status.PROFILE_READ_FAILED,
                    frozen.operationId(), null,
                    "restoration_profile_not_readable", null
            ));
        }
        CompanionProfileReadModel profile = ((PersistenceReadResult.Found<
                CompanionProfileReadModel>) read).value();
        CompanionSnapshot source = exactSource(profile);
        if (source == null) {
            return completed(result(
                    CompanionLifecycleAuthorResult.Status.PROFILE_CONFLICT,
                    frozen.operationId(), null,
                    "restoration_profile_conflict", null
            ));
        }
        return resolveAndSubmit(profile, source, frozen);
    }

    private CompanionSnapshot exactSource(
            CompanionProfileReadModel profile
    ) {
        CompanionLifecycle lifecycle = profile.lifecycle();
        SnapshotKind expectedKind = expectedKind(lifecycle.state());
        if (expectedKind == null
                || !lifecycle.location().equals(LifecycleLocation.none())
                || lifecycle.activeOperationId() != null
                || lifecycle.quarantined()) {
            return null;
        }
        CompanionSnapshot found = null;
        for (CompanionSnapshot snapshot : profile.currentSnapshots()) {
            if (!snapshot.kind().equals(expectedKind)) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = snapshot;
        }
        return found;
    }

    private CompletionStage<CompanionLifecycleAuthorResult> resolveAndSubmit(
            CompanionProfileReadModel profile,
            CompanionSnapshot source,
            FrozenRestoration frozen
    ) {
        TameworkDormantSnapshotFactsReader.ReadResult facts =
                factsReader.read(source);
        if (!facts.successful() || facts.facts() == null
                || facts.facts().state() != profile.lifecycle().state()) {
            return snapshotFailed(frozen.operationId());
        }
        CompanionLifecycleAuthorResult denied =
                policyDenial(profile, facts.facts(), frozen);
        if (denied != null) {
            return completed(denied);
        }
        TameworkRestorationSnapshotResolver.Resolution resolution =
                resolver.resolve(profile, source);
        if (!(resolution instanceof
                TameworkRestorationSnapshotResolver.Resolution.Resolved
                resolved)) {
            return snapshotFailed(frozen.operationId());
        }
        if (resolved.projection().sourceAlias().equals(frozen.targetAlias())) {
            return completed(result(
                    CompanionLifecycleAuthorResult.Status.PROFILE_CONFLICT,
                    frozen.operationId(), null,
                    "restoration_target_alias_not_distinct", null
            ));
        }
        return submit(profile, source, resolved.projection(), frozen);
    }

    @Nullable
    private CompanionLifecycleAuthorResult policyDenial(
            CompanionProfileReadModel profile,
            TameworkDormantSnapshotFactsReader.Facts facts,
            FrozenRestoration frozen
    ) {
        if (facts.state() != LifecycleState.DEAD_REVIVABLE) {
            return null;
        }
        if (!policy.deadRestorationEnabled(profile)) {
            return result(
                    CompanionLifecycleAuthorResult.Status.RESTORATION_DISABLED,
                    frozen.operationId(), null,
                    "restoration_disabled", null
            );
        }
        long availableAt = facts.restorationAvailableAtMs();
        if (availableAt != 0L && frozen.requestedAtMs() < availableAt) {
            return result(
                    CompanionLifecycleAuthorResult.Status.COOLDOWN_ACTIVE,
                    frozen.operationId(), null,
                    "restoration_cooldown_active", null
            );
        }
        return null;
    }

    private CompletionStage<CompanionLifecycleAuthorResult> submit(
            CompanionProfileReadModel profile,
            CompanionSnapshot source,
            RestorationProjection projection,
            FrozenRestoration frozen
    ) {
        CompanionRestorationRequest request =
                new CompanionRestorationRequest(
                        profile.identity().profileId(),
                        profile.lifecycle().revision(),
                        profile.lifecycle().state(),
                        source,
                        projection,
                        frozen.targetAlias(),
                        frozen.placement(),
                        frozen.spawnReceipt(),
                        frozen.requestedAtMs()
                );
        final PublicOperationSubmission submission;
        try {
            submission = persistence.restore(
                    frozen.operationId(), frozen.idempotencyKey(), request
            );
        } catch (RuntimeException | LinkageError failure) {
            return workflowFailed(frozen.operationId(), failure);
        }
        if (submission == null || !submission.accepted()) {
            return completed(result(
                    CompanionLifecycleAuthorResult.Status.SUBMISSION_REJECTED,
                    frozen.operationId(), null,
                    "restoration_submission_rejected", null
            ));
        }
        return submission.completion().handle((workflow, failure) ->
                workflowResult(frozen.operationId(), workflow, failure)
        );
    }

    private CompanionLifecycleAuthorResult workflowResult(
            OperationId operationId,
            OperationWorkflowResult workflow,
            Throwable failure
    ) {
        if (failure == null && workflow != null
                && workflow.status()
                == OperationWorkflowResult.Status.PUBLISHED) {
            return result(
                    CompanionLifecycleAuthorResult.Status.PUBLISHED,
                    operationId, workflow.status(), null, null
            );
        }
        return result(
                CompanionLifecycleAuthorResult.Status.WORKFLOW_FAILED,
                operationId,
                workflow == null ? null : workflow.status(),
                "restoration_workflow_not_published",
                failure != null
                        ? failure
                        : workflow == null ? null : workflow.failure()
        );
    }

    private CompletionStage<CompanionLifecycleAuthorResult> snapshotFailed(
            OperationId operationId
    ) {
        return completed(result(
                CompanionLifecycleAuthorResult.Status.SNAPSHOT_DECODE_FAILED,
                operationId, null, "restoration_snapshot_unreadable", null
        ));
    }

    private CompletionStage<CompanionLifecycleAuthorResult> workflowFailed(
            OperationId operationId,
            Throwable failure
    ) {
        return completed(result(
                CompanionLifecycleAuthorResult.Status.WORKFLOW_FAILED,
                operationId, null, "restoration_submission_failed", failure
        ));
    }

    private CompanionLifecycleAuthorResult dispatch(
            FrozenRestoration frozen,
            CompanionLifecycleAuthorResult value
    ) {
        try {
            dispatcher.dispatch(
                    frozen.actorWorldKey(), frozen.actorUuid(), value
            );
        } catch (RuntimeException | LinkageError ignored) {
            // Feedback is best-effort after the durable result is known.
        }
        return value;
    }

    private CompletionStage<CompanionLifecycleAuthorResult> dispatchCompleted(
            Intent intent,
            CompanionLifecycleAuthorResult value
    ) {
        try {
            dispatcher.dispatch(
                    intent.actorWorldKey(), intent.actorUuid(), value
            );
        } catch (RuntimeException | LinkageError ignored) {
            // Invalid synchronous intent feedback is still best-effort.
        }
        return completed(value);
    }

    private CompanionLifecycleAuthorResult result(
            CompanionLifecycleAuthorResult.Status status,
            OperationId operationId,
            OperationWorkflowResult.Status workflow,
            String detail,
            Throwable failure
    ) {
        return new CompanionLifecycleAuthorResult(
                CompanionLifecycleAuthorResult.Kind.RESTORATION,
                status, operationId, workflow, detail, failure
        );
    }

    @Nullable
    private SnapshotKind expectedKind(LifecycleState state) {
        if (state == LifecycleState.DEAD_REVIVABLE) {
            return TameworkSnapshotCodecs.DEATH;
        }
        return state == LifecycleState.LOST
                ? TameworkSnapshotCodecs.LOST
                : null;
    }

    private String[] intentParts(
            Intent intent,
            CompanionSpawnPlacement placement
    ) {
        return new String[]{
                intent.intentKey(),
                intent.profileId().toString(),
                intent.actorUuid().toString(),
                placement.worldKey(),
                Double.toHexString(placement.x()),
                Double.toHexString(placement.y()),
                Double.toHexString(placement.z()),
                Float.toHexString(placement.pitchRadians()),
                Float.toHexString(placement.yawRadians()),
                Float.toHexString(placement.rollRadians())
        };
    }

    private static CompletionStage<CompanionLifecycleAuthorResult> completed(
            CompanionLifecycleAuthorResult result
    ) {
        return CompletableFuture.completedFuture(result);
    }

    /** Immutable player intent; no live player or world object crosses persistence threads. */
    public record Intent(
            @Nonnull String intentKey,
            @Nonnull UUID actorUuid,
            @Nonnull String actorWorldKey,
            @Nonnull ProfileId profileId,
            @Nonnull CompanionSpawnPlacement placement
    ) {
        public Intent {
            intentKey = requireText(intentKey, "Restoration intent key");
            Objects.requireNonNull(actorUuid, "Restoration actor is required");
            actorWorldKey = requireText(
                    actorWorldKey, "Restoration actor world is required"
            );
            Objects.requireNonNull(profileId, "Restoration profile is required");
            Objects.requireNonNull(placement, "Restoration placement is required");
        }

        private static String requireText(String value, String label) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(label + " is required");
            }
            return value.trim();
        }
    }

    interface PersistencePort {
        CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
        findProfile(ProfileId profileId);

        PublicOperationSubmission restore(
                OperationId operationId,
                IdempotencyKey idempotencyKey,
                CompanionRestorationRequest request
        );
    }

    @FunctionalInterface
    interface ResultDispatcher {
        boolean dispatch(
                String worldKey,
                UUID actorUuid,
                CompanionLifecycleAuthorResult result
        );
    }

    private record FrozenRestoration(
            ProfileId profileId,
            UUID actorUuid,
            String actorWorldKey,
            long requestedAtMs,
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            NpcAlias targetAlias,
            CompanionSpawnPlacement placement,
            String spawnReceipt
    ) {
    }

}
