package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.companion.revival.ReviveReadyDefinition;
import com.alechilles.alecstamework.companion.revival.ReviveReadyRequest;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.items.persistence.DeathSnapshotV2Codec;
import com.alechilles.alecstamework.items.persistence.DeathSnapshotV2Payload;
import com.alechilles.alecstamework.items.persistence.TameworkSnapshotCodecs;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import java.util.List;
import javax.annotation.Nonnull;

/** Commits the current death snapshot with its revive deadline set to the request time. */
public final class SqliteReviveReadyOperations {
    public static final String FEATURE_SCOPE = "companion_revive_ready";

    private final SqliteDatabaseOperationCoordinator coordinator;
    private final List<ProjectionConsumer> consumers;

    public SqliteReviveReadyOperations(
            @Nonnull SqliteDatabaseOperationCoordinator coordinator,
            @Nonnull List<? extends ProjectionConsumer> consumers
    ) {
        if (coordinator == null || consumers == null) {
            throw new IllegalArgumentException(
                    "Revive-ready operation dependencies are required"
            );
        }
        this.coordinator = coordinator;
        this.consumers = List.copyOf(consumers);
    }

    @Nonnull
    public SqliteDatabaseOperationCoordinator.Submission submit(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull ReviveReadyRequest request) {
        OperationRequest<ReviveReadyRequest> operation = new OperationRequest<>(
                operationId,
                idempotencyKey,
                request,
                FEATURE_SCOPE,
                null,
                List.of(OperationScope.profile(request.profileId())),
                request.requestedAtMs()
        );
        return coordinator.execute(
                ReviveReadyDefinition.INSTANCE,
                operation,
                (transaction, envelope) -> commit(
                        transaction,
                        envelope.operationId(),
                        request
                ),
                consumers
        );
    }

    private List<ProjectionEventDraft> commit(
            SqlitePersistenceTransactionContext transaction,
            OperationId operationId,
            ReviveReadyRequest request
    ) {
        ProfileId profileId = request.profileId();
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(profileId)
                .orElseThrow(() -> new IllegalStateException(
                        "revive_ready_profile_not_found"
                ));
        if (lifecycle.state() != LifecycleState.DEAD_REVIVABLE
                || lifecycle.activeOperationId() != null) {
            throw new IllegalStateException("revive_ready_profile_not_dead");
        }
        CompanionSnapshot current = transaction.snapshots()
                .findCurrent(profileId, TameworkSnapshotCodecs.DEATH)
                .orElseThrow(() -> new IllegalStateException(
                        "revive_ready_snapshot_not_found"
                ));
        DeathSnapshotV2Payload death = decode(current);
        CompanionProfileProjectionState before =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction,
                        profileId
                );
        DeathSnapshotV2Payload ready = new DeathSnapshotV2Payload(
                death.fullStateJson(),
                death.diedAtMs(),
                request.requestedAtMs(),
                death.deathCauseKind(),
                death.deathSourceName()
        );
        String payload = new DeathSnapshotV2Codec().encode(ready);
        CompanionSnapshot replacement = new CompanionSnapshot(
                SnapshotId.create(),
                profileId,
                current.kind(),
                current.payloadVersion(),
                payload,
                Sha256Hash.ofUtf8(payload),
                current.sourceLifecycleRevision(),
                true,
                request.requestedAtMs()
        );
        PersistenceMutationResult<CompanionSnapshot> saved = transaction
                .snapshots().replaceCurrent(replacement);
        if (saved == null || !saved.applied()) {
            throw new IllegalStateException(
                    "revive_ready_snapshot_replace_failed"
            );
        }
        CompanionProfileProjectionState after =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction,
                        profileId
                );
        CompanionProfileProjectionChange change = new CompanionProfileProjectionChange(
                CompanionProfileProjectionChange.Source.SNAPSHOT,
                profileId,
                lifecycle.revision().value(),
                before,
                after,
                request.requestedAtMs()
        );
        return List.of(
                SqliteCompanionProfileProjectionComposer.event(operationId, change)
        );
    }

    private DeathSnapshotV2Payload decode(CompanionSnapshot snapshot) {
        if (snapshot.payloadVersion() != 2) {
            throw new IllegalStateException("revive_ready_snapshot_unsupported");
        }
        try {
            return new DeathSnapshotV2Codec().decode(snapshot.payloadJson());
        } catch (RuntimeException invalid) {
            throw new IllegalStateException(
                    "revive_ready_snapshot_invalid",
                    invalid
            );
        }
    }
}
