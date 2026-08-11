package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.dormant.CompanionDormantTransitionRequest;
import com.alechilles.alecstamework.companion.dormant.DormantSourceEvidence;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService
        .CoopResidentStateSnapshot;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PublicOperationSubmission;
import com.alechilles.alecstamework.persistence.operation.StablePersistenceIds;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Authors a dormant transition only from positive death or removal evidence.
 *
 * <p>The complete live state and all durable identities are frozen before the profile read.
 * Unload, absence, and timeout observations return without reading or mutating persistence.</p>
 */
public final class PositiveEvidenceDormantAuthor {
    private static final String OPERATION = "companion-dormant:v1";
    private static final String SNAPSHOT = "companion-dormant-snapshot:v1";

    private final PersistencePort persistence;
    private final TameworkFullStateSnapshotReader snapshots;
    private final SnapshotCodecRegistry codecs;
    private final LongSupplier clock;
    private final DormantCompanionEventFactsFreezer eventFacts;
    private final PublishedDormantEventPublisher eventPublisher;

    /** Creates the production author over the replacement persistence facades. */
    public PositiveEvidenceDormantAuthor(
            @Nonnull PersistenceDomainFacades persistence,
            @Nonnull TameworkFullStateSnapshotReader snapshots,
            @Nonnull LongSupplier clock,
            @Nonnull DormantCompanionEventSink events,
            @Nonnull DormantCompanionEventWarningSink warnings
    ) {
        this(
                new FacadeDormantPersistencePort(persistence),
                snapshots,
                TameworkSnapshotCodecs.create(),
                clock,
                new DormantCompanionEventFactsFreezer(),
                events,
                warnings
        );
    }

    PositiveEvidenceDormantAuthor(
            PersistencePort persistence,
            TameworkFullStateSnapshotReader snapshots,
            SnapshotCodecRegistry codecs,
            LongSupplier clock,
            DormantCompanionEventFactsFreezer eventFacts,
            DormantCompanionEventSink events,
            DormantCompanionEventWarningSink warnings
    ) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.codecs = Objects.requireNonNull(codecs, "codecs");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.eventFacts = Objects.requireNonNull(eventFacts, "eventFacts");
        this.eventPublisher = new PublishedDormantEventPublisher(
                persistence::findProfile,
                clock,
                events,
                warnings
        );
    }

    /** Freezes and submits one exact positive-evidence dormant transition. */
    @Nonnull
    public CompletionStage<CompanionLifecycleAuthorResult> makeDormant(
            @Nullable Intent intent
    ) {
        if (intent == null) {
            return completed(result(
                    CompanionLifecycleAuthorResult.Status.INVALID_CONTEXT,
                    null, null, "dormant_intent_missing", null
            ));
        }
        if (!intent.observation().authoritative()) {
            return completed(result(
                    CompanionLifecycleAuthorResult.Status.INVALID_EVIDENCE,
                    null, null, "dormant_positive_evidence_required", null
            ));
        }
        final FrozenDormant frozen;
        try {
            frozen = freeze(intent);
        } catch (RuntimeException | LinkageError failure) {
            return completed(result(
                    CompanionLifecycleAuthorResult.Status.EVIDENCE_FAILED,
                    null, null, evidenceDetail(failure), failure
            ));
        }
        try {
            return persistence.findProfile(
                            frozen.observation().profileId()
                    )
                    .thenCompose(read -> author(frozen, read))
                    .exceptionally(failure -> result(
                            CompanionLifecycleAuthorResult.Status
                                    .PROFILE_READ_FAILED,
                            frozen.operationId(), null,
                            "dormant_profile_read_failed", failure
                    ));
        } catch (RuntimeException | LinkageError failure) {
            return completed(result(
                    CompanionLifecycleAuthorResult.Status.PROFILE_READ_FAILED,
                    frozen.operationId(), null,
                    "dormant_profile_read_failed", failure
            ));
        }
    }

    private FrozenDormant freeze(Intent intent) {
        DormantCompanionObservation observation = intent.observation();
        TameworkFullStateSnapshotReader.ReadResult read = snapshots.read(
                intent.sourceRef(),
                intent.sourceStore(),
                observation.sourceAlias(),
                intent.roleId()
        );
        if (!read.successful() || read.snapshot() == null) {
            throw new EvidenceFailure(read.failure() == null
                    ? "snapshot_unavailable"
                    : read.failure().name().toLowerCase(Locale.ROOT));
        }
        CoopResidentStateSnapshot fullState = read.snapshot();
        if (!observation.sourceAlias().value().equals(fullState.npcUuid())) {
            throw new EvidenceFailure("snapshot_alias_mismatch");
        }
        String[] parts = intentParts(observation);
        return new FrozenDormant(
                observation,
                clock.getAsLong(),
                StablePersistenceIds.operationId(OPERATION, parts),
                StablePersistenceIds.idempotencyKey(OPERATION, parts),
                new SnapshotId(
                        StablePersistenceIds.operationId(SNAPSHOT, parts).value()
                ),
                encode(observation, fullState),
                eventFacts.freeze(fullState)
        );
    }

    private SnapshotCodecRegistry.EncodedSnapshot encode(
            DormantCompanionObservation observation,
            CoopResidentStateSnapshot state
    ) {
        if (observation.evidence()
                == DormantCompanionObservation.Evidence.SAVED_DEATH_COMPONENT) {
            DormantCompanionObservation.DeathObservation death =
                    Objects.requireNonNull(observation.death(), "death");
            return codecs.encode(
                    TameworkSnapshotCodecs.DEATH,
                    2,
                    DeathSnapshotV2Payload.class,
                    DeathSnapshotV2Payload.capture(
                            state,
                            death.diedAtMs(),
                            death.restorationAvailableAtMs(),
                            death.cause(),
                            death.sourceName()
                    )
            );
        }
        return codecs.encode(
                TameworkSnapshotCodecs.LOST,
                2,
                CoopResidentStateSnapshot.class,
                state
        );
    }

    private CompletionStage<CompanionLifecycleAuthorResult> author(
            FrozenDormant frozen,
            PersistenceReadResult<CompanionProfileReadModel> read
    ) {
        if (read instanceof PersistenceReadResult.Failed<?>
                || read instanceof PersistenceReadResult.Absent<?>) {
            return completed(result(
                    CompanionLifecycleAuthorResult.Status.PROFILE_READ_FAILED,
                    frozen.operationId(), null,
                    "dormant_profile_not_readable", null
            ));
        }
        CompanionProfileReadModel profile = ((PersistenceReadResult.Found<
                CompanionProfileReadModel>) read).value();
        String conflict = liveProfileConflict(frozen, profile);
        if (conflict != null) {
            return completed(result(
                    CompanionLifecycleAuthorResult.Status.PROFILE_CONFLICT,
                    frozen.operationId(), null,
                    conflict, null
            ));
        }
        return submit(
                frozen.observation(), frozen, profile.lifecycle()
        );
    }

    @Nullable
    private String liveProfileConflict(
            FrozenDormant frozen,
            CompanionProfileReadModel profile
    ) {
        DormantCompanionObservation observation = frozen.observation();
        CompanionLifecycle lifecycle = profile.lifecycle();
        CompanionAlias alias = profile.currentAlias();
        if (!profile.identity().profileId().equals(observation.profileId())) {
            return "dormant_profile_id_conflict";
        }
        if (alias == null || !alias.alias().equals(observation.sourceAlias())
                || alias.state() != CompanionAlias.State.CURRENT) {
            return "dormant_source_alias_conflict";
        }
        boolean removalBeforeReconciliation =
                observation.evidence()
                        == DormantCompanionObservation.Evidence
                        .DESTRUCTIVE_REMOVAL
                        && lifecycle.state() == LifecycleState.UNLOADED
                        && lifecycle.location().equals(LifecycleLocation.none());
        if (lifecycle.state() != LifecycleState.ACTIVE
                && !removalBeforeReconciliation) {
            return "dormant_lifecycle_not_active";
        }
        boolean exactAliasLocation =
                observation.sourceAlias().toString().equals(
                        lifecycle.location().key()
                );
        boolean exactWorld = Objects.equals(
                observation.sourceWorldKey(),
                lifecycle.location().worldKey()
        );
        boolean deletionMayCorrectWorld =
                observation.evidence()
                        == DormantCompanionObservation.Evidence.WORLD_DELETION;
        if (!removalBeforeReconciliation
                && (!exactAliasLocation
                || (!exactWorld && !deletionMayCorrectWorld))) {
            return "dormant_live_location_conflict";
        }
        if (lifecycle.activeOperationId() != null) {
            return "dormant_operation_in_progress";
        }
        if (lifecycle.quarantined()) {
            return "dormant_profile_quarantined";
        }
        /*
         * The exact profile, current alias, and live location already identify the source entity.
         * A role is mutable gameplay state (for example life-stage/variant changes), so a stale
         * profile role must not discard authoritative saved DeathComponent evidence.
         */
        return null;
    }

    private CompletionStage<CompanionLifecycleAuthorResult> submit(
            DormantCompanionObservation observation,
            FrozenDormant frozen,
            CompanionLifecycle lifecycle
    ) {
        CompanionSnapshot snapshot = companionSnapshot(
                observation, frozen, lifecycle
        );
        CompanionDormantTransitionRequest request =
                new CompanionDormantTransitionRequest(
                        observation.profileId(),
                        lifecycle.revision(),
                        snapshot,
                        sourceEvidence(
                                observation,
                                lifecycle.lastReconciledGeneration()
                        ),
                        frozen.requestedAtMs()
                );
        final PublicOperationSubmission submission;
        try {
            submission = persistence.makeDormant(
                    frozen.operationId(), frozen.idempotencyKey(), request
            );
        } catch (RuntimeException | LinkageError failure) {
            return workflowFailed(frozen.operationId(), failure);
        }
        if (submission == null || !submission.accepted()) {
            return completed(result(
                    CompanionLifecycleAuthorResult.Status.SUBMISSION_REJECTED,
                    frozen.operationId(), null,
                    "dormant_submission_rejected", null
            ));
        }
        return submission.completion()
                .handle(WorkflowCompletion::new)
                .thenCompose(completion -> afterWorkflow(
                        frozen, completion
                ));
    }

    private CompletionStage<CompanionLifecycleAuthorResult> afterWorkflow(
            FrozenDormant frozen,
            WorkflowCompletion completion
    ) {
        CompanionLifecycleAuthorResult outcome = workflowResult(
                frozen.operationId(),
                completion.workflow(),
                completion.failure()
        );
        return outcome.published()
                ? eventPublisher.publish(
                        frozen.observation(), frozen.eventFacts(), outcome
                )
                : completed(outcome);
    }

    private CompanionSnapshot companionSnapshot(
            DormantCompanionObservation observation,
            FrozenDormant frozen,
            CompanionLifecycle lifecycle
    ) {
        SnapshotCodecRegistry.EncodedSnapshot encoded = frozen.encoded();
        return new CompanionSnapshot(
                frozen.snapshotId(),
                observation.profileId(),
                encoded.kind(),
                encoded.payloadVersion(),
                encoded.payloadJson(),
                encoded.payloadHash(),
                lifecycle.revision(),
                true,
                observation.observedAtMs()
        );
    }

    private DormantSourceEvidence sourceEvidence(
            DormantCompanionObservation observation,
            com.alechilles.alecstamework.companion.lifecycle
                    .ReconciliationGeneration generation
    ) {
        DormantSourceEvidence.Kind kind = switch (observation.evidence()) {
            case SAVED_DEATH_COMPONENT ->
                    DormantSourceEvidence.Kind.DEATH_COMPONENT;
            case DESTRUCTIVE_REMOVAL ->
                    DormantSourceEvidence.Kind.DESTRUCTIVE_REMOVAL;
            case WORLD_DELETION -> DormantSourceEvidence.Kind.WORLD_DELETION;
            case UNLOAD, ABSENCE, TIMEOUT -> throw new IllegalStateException(
                    "Non-authoritative dormant evidence reached submission"
            );
        };
        return new DormantSourceEvidence(
                observation.sourceAlias(),
                observation.sourceWorldKey(),
                kind,
                generation,
                observation.receiptKey(),
                observation.observedAtMs()
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
                "dormant_workflow_not_published",
                failure != null
                        ? failure
                        : workflow == null ? null : workflow.failure()
        );
    }

    private CompletionStage<CompanionLifecycleAuthorResult> workflowFailed(
            OperationId operationId,
            Throwable failure
    ) {
        return completed(result(
                CompanionLifecycleAuthorResult.Status.WORKFLOW_FAILED,
                operationId, null, "dormant_submission_failed", failure
        ));
    }

    private CompanionLifecycleAuthorResult result(
            CompanionLifecycleAuthorResult.Status status,
            OperationId operationId,
            OperationWorkflowResult.Status workflow,
            String detail,
            Throwable failure
    ) {
        return new CompanionLifecycleAuthorResult(
                CompanionLifecycleAuthorResult.Kind.DORMANT,
                status, operationId, workflow, detail, failure
        );
    }

    private String[] intentParts(DormantCompanionObservation observation) {
        return new String[]{
                observation.observationKey(),
                observation.profileId().toString(),
                observation.sourceAlias().toString(),
                observation.sourceWorldKey(),
                observation.evidence().name(),
                observation.receiptKey()
        };
    }

    private String evidenceDetail(Throwable failure) {
        return failure instanceof EvidenceFailure evidence
                ? "dormant_" + evidence.code
                : "dormant_evidence_failed";
    }

    private static CompletionStage<CompanionLifecycleAuthorResult> completed(
            CompanionLifecycleAuthorResult result
    ) {
        return CompletableFuture.completedFuture(result);
    }

    /** Live evidence required only while the final complete snapshot is copied. */
    public record Intent(
            @Nonnull DormantCompanionObservation observation,
            @Nullable Ref<EntityStore> sourceRef,
            @Nullable Store<EntityStore> sourceStore,
            @Nullable String roleId
    ) {
        public Intent {
            Objects.requireNonNull(
                    observation, "Dormant observation is required"
            );
            roleId = roleId == null || roleId.isBlank()
                    ? null
                    : roleId.trim();
        }
    }

    interface PersistencePort {
        CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
        findProfile(ProfileId profileId);

        PublicOperationSubmission makeDormant(
                OperationId operationId,
                IdempotencyKey idempotencyKey,
                CompanionDormantTransitionRequest request
        );
    }

    private record FrozenDormant(
            DormantCompanionObservation observation,
            long requestedAtMs,
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            SnapshotId snapshotId,
            SnapshotCodecRegistry.EncodedSnapshot encoded,
            DormantCompanionEventFacts eventFacts
    ) {
    }

    private record WorkflowCompletion(
            OperationWorkflowResult workflow,
            Throwable failure
    ) {
    }

    private static final class EvidenceFailure
            extends IllegalArgumentException {
        private final String code;

        private EvidenceFailure(String code) {
            super(code);
            this.code = code;
        }
    }
}
