package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.companion.progression.SavedCompanionTalentDefinition;
import com.alechilles.alecstamework.companion.progression.SavedCompanionTalentRequest;
import com.alechilles.alecstamework.companion.progression.SavedCompanionTalentSnapshot;
import com.alechilles.alecstamework.companion.progression.SavedCompanionTalentOutcome;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionSettings;
import com.alechilles.alecstamework.npc.progression.CompanionTalentService;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import java.util.LinkedHashSet;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Applies validated talent changes to a current saved dead or lost companion snapshot. */
public final class SqliteSavedCompanionTalentOperations {
    public static final String FEATURE_SCOPE = "saved_companion_talent";

    private final SqliteDatabaseOperationCoordinator coordinator;
    private final List<ProjectionConsumer> consumers;

    public SqliteSavedCompanionTalentOperations(
            @Nonnull SqliteDatabaseOperationCoordinator coordinator,
            @Nonnull List<? extends ProjectionConsumer> consumers
    ) {
        if (coordinator == null || consumers == null) {
            throw new IllegalArgumentException(
                    "Saved companion talent operation dependencies are required"
            );
        }
        this.coordinator = coordinator;
        this.consumers = List.copyOf(consumers);
    }

    @Nonnull
    public SqliteDatabaseOperationCoordinator.Submission submit(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull SavedCompanionTalentRequest request
    ) {
        OperationRequest<SavedCompanionTalentRequest> operation =
                new OperationRequest<>(
                        operationId,
                        idempotencyKey,
                        request,
                        FEATURE_SCOPE,
                        request.expectedLifecycleRevision(),
                        List.of(
                                OperationScope.profile(request.profileId()),
                                OperationScope.owner(request.ownerId())
                        ),
                        request.requestedAtMs()
                );
        return coordinator.execute(
                SavedCompanionTalentDefinition.INSTANCE,
                operation,
                (transaction, envelope) -> commit(
                        transaction, envelope.operationId(), request),
                consumers
        );
    }

    /** Returns whether this published workflow carries an applied saved-talent outcome. */
    public static boolean isApplied(@Nullable OperationWorkflowResult result) {
        return SavedCompanionTalentOutcome.isApplied(result);
    }

    private List<ProjectionEventDraft> commit(
            SqlitePersistenceTransactionContext transaction,
            OperationId operationId,
            SavedCompanionTalentRequest request
    ) {
        ProfileId profileId = request.profileId();
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(profileId)
                .orElse(null);
        if (lifecycle == null) {
            return rejected(operationId, request,
                    SavedCompanionTalentOutcome.Status.PROFILE_NOT_FOUND,
                    request.expectedLifecycleRevision().value());
        }
        if (!request.ownerId().equals(lifecycle.ownerId())) {
            return rejected(operationId, request,
                    SavedCompanionTalentOutcome.Status.OWNER_MISMATCH,
                    lifecycle.revision().value());
        }
        if (lifecycle.activeOperationId() != null
                || (lifecycle.state() != LifecycleState.DEAD_REVIVABLE
                && lifecycle.state() != LifecycleState.LOST)) {
            return rejected(operationId, request,
                    SavedCompanionTalentOutcome.Status.PROFILE_NOT_OFFLINE,
                    lifecycle.revision().value());
        }
        if (!request.expectedLifecycleRevision().equals(
                lifecycle.revision())) {
            return rejected(operationId, request,
                    SavedCompanionTalentOutcome.Status.LIFECYCLE_STALE,
                    lifecycle.revision().value());
        }
        CompanionSnapshot snapshot = transaction.snapshots()
                .findById(request.expectedSnapshotId())
                .orElse(null);
        if (snapshot == null || !snapshot.current()
                || !profileId.equals(snapshot.profileId())
                || !request.expectedSnapshotHash().equals(
                        snapshot.payloadHash())) {
            return rejected(operationId, request,
                    SavedCompanionTalentOutcome.Status.SNAPSHOT_STALE,
                    lifecycle.revision().value());
        }
        SavedCompanionTalentSnapshot saved =
                SavedCompanionTalentSnapshot.decode(snapshot);
        if (saved == null || !matchesLifecycle(lifecycle, snapshot)) {
            return rejected(operationId, request,
                    SavedCompanionTalentOutcome.Status.SNAPSHOT_UNSUPPORTED,
                    lifecycle.revision().value());
        }
        if (!CompanionProgressionSettings.isTalentsEnabled()) {
            return rejected(operationId, request,
                    SavedCompanionTalentOutcome.Status.TALENTS_DISABLED,
                    lifecycle.revision().value());
        }
        CompanionIdentity identity = transaction.identities()
                .findProfile(profileId)
                .orElse(null);
        if (identity == null) {
            return rejected(operationId, request,
                    SavedCompanionTalentOutcome.Status.IDENTITY_NOT_FOUND,
                    lifecycle.revision().value());
        }
        TameworkLevelingComponent leveling = saved.leveling();
        if (leveling == null) {
            return rejected(operationId, request,
                    SavedCompanionTalentOutcome.Status.LEVEL_UNAVAILABLE,
                    lifecycle.revision().value());
        }
        TwTalentConfig config = resolveConfig(
                identity.roleId(), request.expectedTalentConfigId());
        if (config == null || config.getAllocationRevision() != request.expectedAllocationRevision()) {
            return rejected(operationId, request,
                    SavedCompanionTalentOutcome.Status.CONFIG_STALE,
                    lifecycle.revision().value());
        }
        TameworkTalentsComponent updated = request.action()
                == SavedCompanionTalentRequest.Action.PURCHASE
                ? purchase(saved.talents(), leveling, config,
                        request.talentId())
                : reset(saved.talents(), config);
        if (updated == null) {
            return rejected(operationId, request,
                    request.action() == SavedCompanionTalentRequest.Action.PURCHASE
                            ? SavedCompanionTalentOutcome.Status.PURCHASE_REJECTED
                            : SavedCompanionTalentOutcome.Status.RESET_REJECTED,
                    lifecycle.revision().value());
        }
        CompanionProfileProjectionState before =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction, profileId);
        CompanionSnapshot replacement = saved.replaceTalents(
                updated, lifecycle.revision(), request.requestedAtMs());
        PersistenceMutationResult<CompanionSnapshot> persisted = transaction
                .snapshots().replaceCurrent(replacement);
        if (persisted == null || !persisted.applied()) {
            return rejected(operationId, request,
                    SavedCompanionTalentOutcome.Status.SNAPSHOT_STALE,
                    lifecycle.revision().value());
        }
        CompanionProfileProjectionState after =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction, profileId);
        CompanionProfileProjectionChange change =
                new CompanionProfileProjectionChange(
                        CompanionProfileProjectionChange.Source.SNAPSHOT,
                        profileId,
                        lifecycle.revision().value(),
                        before,
                        after,
                        request.requestedAtMs()
                );
        SavedCompanionTalentOutcome outcome = new SavedCompanionTalentOutcome(
                SavedCompanionTalentOutcome.Status.APPLIED,
                profileId, request.action(), lifecycle.revision().value(),
                request.requestedAtMs());
        return List.of(
                SqliteCompanionProfileProjectionComposer.event(operationId, change),
                outcomeEvent(operationId, outcome)
        );
    }

    private List<ProjectionEventDraft> rejected(
            OperationId operationId,
            SavedCompanionTalentRequest request,
            SavedCompanionTalentOutcome.Status status,
            long lifecycleRevision
    ) {
        return List.of(outcomeEvent(operationId,
                new SavedCompanionTalentOutcome(
                        status, request.profileId(), request.action(),
                        lifecycleRevision, request.requestedAtMs())));
    }

    private ProjectionEventDraft outcomeEvent(
            OperationId operationId,
            SavedCompanionTalentOutcome outcome
    ) {
        return new ProjectionEventDraft(
                operationId,
                SavedCompanionTalentOutcome.EVENT_TYPE,
                outcome.profileId().toString(),
                outcome.lifecycleRevision(),
                SavedCompanionTalentOutcome.EVENT_VERSION,
                SavedCompanionTalentOutcome.encode(outcome),
                outcome.updatedAtMs()
        );
    }

    private boolean matchesLifecycle(
            CompanionLifecycle lifecycle,
            CompanionSnapshot snapshot
    ) {
        return (lifecycle.state() == LifecycleState.DEAD_REVIVABLE
                && com.alechilles.alecstamework.items.persistence
                .TameworkSnapshotCodecs.DEATH.equals(snapshot.kind()))
                || (lifecycle.state() == LifecycleState.LOST
                && com.alechilles.alecstamework.items.persistence
                .TameworkSnapshotCodecs.LOST.equals(snapshot.kind()));
    }

    private TwTalentConfig resolveConfig(
            String roleId,
            String expectedConfigId
    ) {
        TwTalentConfig config = TwTalentConfig.resolveForRole(roleId);
        if (config == null || !config.isEnabled() || config.getId() == null
                || !config.getId().equalsIgnoreCase(expectedConfigId)) {
            return null;
        }
        return config;
    }

    private TameworkTalentsComponent purchase(
            TameworkTalentsComponent existing,
            TameworkLevelingComponent leveling,
            TwTalentConfig config,
            String talentId
    ) {
        TwTalentConfig.TalentDefinition talent = config.findTalent(talentId);
        if (talent == null || leveling.getLevel() < talent.getMinLevel()) {
            return null;
        }
        TameworkTalentsComponent updated =
                CompanionTalentService.reconcileAllocation(existing, config);
        if (updated == null || updated.hasPurchasedTalent(talent.getId())
                || !hasPrerequisites(updated, talent)
                || availablePoints(leveling, updated) < talent.getPointCost()) {
            return null;
        }
        LinkedHashSet<String> purchased = new LinkedHashSet<>();
        for (String purchasedId : updated.getPurchasedTalentIds()) {
            purchased.add(purchasedId);
        }
        purchased.add(talent.getId());
        updated.setPurchasedTalentIds(purchased.toArray(new String[0]));
        updated.setSpentPoints(updated.getSpentPoints() + talent.getPointCost());
        return updated;
    }

    private TameworkTalentsComponent reset(
            TameworkTalentsComponent existing,
            TwTalentConfig config
    ) {
        if (existing == null || (existing.getSpentPoints() == 0
                && existing.getPurchasedTalentIds().length == 0)) {
            return null;
        }
        TameworkTalentsComponent updated =
                CompanionTalentService.reconcileAllocation(existing, config);
        if (updated == null) {
            return null;
        }
        updated.setConfigId(config.getId());
        updated.setAllocationRevision(config.getAllocationRevision());
        updated.setSpentPoints(0);
        updated.setPurchasedTalentIds(new String[0]);
        return updated;
    }

    private int availablePoints(
            TameworkLevelingComponent leveling,
            TameworkTalentsComponent talents
    ) {
        int earned = CompanionLevelingService.resolveEarnedTalentPoints(
                leveling.getLevel(), leveling.getConfigId());
        return Math.max(0, earned - talents.getSpentPoints());
    }

    private boolean hasPrerequisites(
            TameworkTalentsComponent talents,
            TwTalentConfig.TalentDefinition talent
    ) {
        for (String required : talent.getRequiresTalentIds()) {
            if (required != null && !required.isBlank()
                    && !talents.hasPurchasedTalent(required)) {
                return false;
            }
        }
        return true;
    }
}
