package com.alechilles.alecstamework.companion.command.timed.runtime;

import com.alechilles.alecstamework.companion.command.timed.runtime
        .TimedSummonWorldAttempt.MutationAttempt;
import com.alechilles.alecstamework.companion.command.timed.runtime
        .TimedSummonWorldAttempt.ProjectionProbe;
import com.alechilles.alecstamework.companion.placement
        .CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.snapshot
        .SnapshotCodecRegistry;
import com.alechilles.alecstamework.items
        .CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items
        .HytaleCompanionProjectionSpawnExecutor;
import com.alechilles.alecstamework.npc.components
        .TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.NonTicking;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nullable;

/** Frozen snapshot, placement, session-receipt, and movement-hold bridge for START. */
final class HytaleTimedSummonProjectionGateway {
    private final World world;
    private final Store<EntityStore> store;
    private final SnapshotCodecRegistry snapshotCodecs;
    private final HytaleCompanionProjectionSpawnExecutor projections;
    private boolean holdReleased;

    HytaleTimedSummonProjectionGateway(
            World world,
            Store<EntityStore> store,
            SnapshotCodecRegistry snapshotCodecs,
            HytaleCompanionProjectionSpawnExecutor projections
    ) {
        this.world = world;
        this.store = store;
        this.snapshotCodecs = snapshotCodecs;
        this.projections = projections;
    }

    ProjectionProbe probe(TimedSummonWorldAuthority.Start authority) {
        try {
            store.assertThread();
            Ref<EntityStore> target =
                    world.getEntityRef(authority.liveAlias().value());
            if (target == null || !target.isValid()) {
                return ProjectionProbe.absent();
            }
            Components components = components(target);
            if (components == null) {
                return ProjectionProbe.retryable(null);
            }
            if (!exact(authority, components)) {
                return ProjectionProbe.conflict(null);
            }
            WorldChunk chunk = currentChunk(components.transform());
            if (chunk == null
                    || chunk.getIndex() != placementChunk(authority)) {
                return ProjectionProbe.conflict(null);
            }
            if (!holdReleased) {
                store.ensureComponent(
                        target,
                        EntityStore.REGISTRY
                                .getNonTickingComponentType()
                );
            }
            return ProjectionProbe.exact(chunk.getIndex());
        } catch (RuntimeException | LinkageError failure) {
            return ProjectionProbe.retryable(failure);
        }
    }

    MutationAttempt spawnExact(
            TimedSummonWorldAuthority.Start authority
    ) {
        ProjectionProbe before = probe(authority);
        if (before.status()
                == TimedSummonWorldAttempt.EvidenceStatus.EXACT) {
            return MutationAttempt.exact(before.chunkIndex());
        }
        if (before.status()
                == TimedSummonWorldAttempt.EvidenceStatus.CONFLICT) {
            return MutationAttempt.conflict(before.cause());
        }
        if (before.status()
                == TimedSummonWorldAttempt.EvidenceStatus.RETRYABLE) {
            return MutationAttempt.retryable(before.cause());
        }
        LiveOperationResult result = projections.applyOrResolve(
                world,
                store,
                command(authority),
                () -> snapshotCodecs.decode(
                        authority.snapshot(),
                        CoopResidentStateSnapshot.class
                )
        );
        if (result.status() == LiveOperationResult.Status.RETRYABLE) {
            return MutationAttempt.retryable(result.cause());
        }
        if (result.status() != LiveOperationResult.Status.CONFIRMED) {
            return MutationAttempt.conflict(result.cause());
        }
        ProjectionProbe after = probe(authority);
        return switch (after.status()) {
            case EXACT -> MutationAttempt.exact(after.chunkIndex());
            case RETRYABLE -> MutationAttempt.retryable(after.cause());
            case ABSENT, CONFLICT ->
                    MutationAttempt.conflict(after.cause());
        };
    }

    MutationAttempt releaseHold(
            TimedSummonWorldAuthority.Start authority
    ) {
        ProjectionProbe exact = probe(authority);
        if (exact.status()
                == TimedSummonWorldAttempt.EvidenceStatus.ABSENT) {
            return MutationAttempt.conflict(null);
        }
        if (exact.status()
                == TimedSummonWorldAttempt.EvidenceStatus.RETRYABLE) {
            return MutationAttempt.retryable(exact.cause());
        }
        if (exact.status()
                == TimedSummonWorldAttempt.EvidenceStatus.CONFLICT) {
            return MutationAttempt.conflict(exact.cause());
        }
        try {
            Ref<EntityStore> target =
                    world.getEntityRef(authority.liveAlias().value());
            if (target == null || !target.isValid()) {
                return MutationAttempt.conflict(null);
            }
            ComponentType<EntityStore, NonTicking<EntityStore>> type =
                    EntityStore.REGISTRY.getNonTickingComponentType();
            store.tryRemoveComponent(target, type);
            holdReleased = true;
            return MutationAttempt.exact(exact.chunkIndex());
        } catch (RuntimeException | LinkageError failure) {
            return MutationAttempt.retryable(failure);
        }
    }

    private HytaleCompanionProjectionSpawnExecutor.ProjectionCommand
    command(TimedSummonWorldAuthority.Start authority) {
        return new HytaleCompanionProjectionSpawnExecutor.ProjectionCommand(
                "timed_summon",
                authority.profileId(),
                authority.operationId(),
                TameworkProjectionIdentityComponent.KIND_COMMAND_ROSTER,
                authority.liveAlias(),
                null,
                authority.receiptKey(),
                authority.snapshot().sourceLifecycleRevision().value(),
                authority.placement()
        );
    }

    @Nullable
    private Components components(Ref<EntityStore> target) {
        ComponentType<EntityStore, UUIDComponent> uuidType =
                UUIDComponent.getComponentType();
        ComponentType<EntityStore, NPCEntity> npcType =
                NPCEntity.getComponentType();
        ComponentType<EntityStore, TameworkProjectionIdentityComponent>
                markerType =
                TameworkProjectionIdentityComponent.getComponentType();
        ComponentType<EntityStore, TransformComponent> transformType =
                TransformComponent.getComponentType();
        if (uuidType == null || npcType == null
                || markerType == null || transformType == null) {
            return null;
        }
        return new Components(
                store.getComponent(target, uuidType),
                store.getComponent(target, npcType),
                store.getComponent(target, markerType),
                store.getComponent(target, transformType)
        );
    }

    private boolean exact(
            TimedSummonWorldAuthority.Start authority,
            Components components
    ) {
        TameworkProjectionIdentityComponent marker = components.marker();
        return components.identity() != null
                && authority.liveAlias().value().equals(
                components.identity().getUuid()
        )
                && components.npc() != null
                && authority.liveAlias().value().equals(
                components.npc().getUuid()
        )
                && marker != null
                && authority.profileId().toString().equals(
                marker.getProfileId()
        )
                && authority.operationId().toString().equals(
                marker.getOperationId()
        )
                && TameworkProjectionIdentityComponent.KIND_COMMAND_ROSTER
                .equals(marker.getProjectionKind())
                && authority.receiptKey().equals(marker.getSlotKey())
                && marker.getSourceNpcUuid() == null
                && authority.snapshot().sourceLifecycleRevision().value()
                == marker.getGeneration()
                && exactPlacement(
                authority.placement(),
                components.transform()
        );
    }

    private boolean exactPlacement(
            CompanionSpawnPlacement placement,
            @Nullable TransformComponent transform
    ) {
        if (transform == null || transform.getPosition() == null
                || transform.getRotation() == null) {
            return false;
        }
        Rotation3f rotation = transform.getRotation();
        return Double.compare(
                placement.x(), transform.getPosition().x
        ) == 0
                && Double.compare(
                placement.y(), transform.getPosition().y
        ) == 0
                && Double.compare(
                placement.z(), transform.getPosition().z
        ) == 0
                && Float.compare(
                placement.pitchRadians(), rotation.pitch()
        ) == 0
                && Float.compare(
                placement.yawRadians(), rotation.yaw()
        ) == 0
                && Float.compare(
                placement.rollRadians(), rotation.roll()
        ) == 0;
    }

    private long placementChunk(
            TimedSummonWorldAuthority.Start authority
    ) {
        int x = com.hypixel.hytale.math.util.ChunkUtil.chunkCoordinate(
                authority.placement().x()
        );
        int z = com.hypixel.hytale.math.util.ChunkUtil.chunkCoordinate(
                authority.placement().z()
        );
        return com.hypixel.hytale.math.util.ChunkUtil.indexChunk(x, z);
    }

    @Nullable
    private WorldChunk currentChunk(
            @Nullable TransformComponent transform
    ) {
        Ref<ChunkStore> chunkRef =
                transform == null ? null : transform.getChunkRef();
        ChunkStore chunkStore = world.getChunkStore();
        Store<ChunkStore> chunks =
                chunkStore == null ? null : chunkStore.getStore();
        if (chunkRef == null || !chunkRef.isValid() || chunks == null) {
            return null;
        }
        WorldChunk chunk = chunks.getComponent(
                chunkRef, WorldChunk.getComponentType()
        );
        return chunk != null && chunk.getWorld() == world ? chunk : null;
    }

    private record Components(
            UUIDComponent identity,
            NPCEntity npc,
            TameworkProjectionIdentityComponent marker,
            TransformComponent transform
    ) {
    }
}
