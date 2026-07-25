package com.alechilles.alecstamework.companion.provisioning.runtime;

import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationRequest;
import com.alechilles.alecstamework.companion.provisioning.runtime.ProvisioningActivationWorldAttempt.ProjectionAttempt;
import com.alechilles.alecstamework.companion.provisioning.runtime.ProvisioningActivationWorldAttempt.ProjectionProbe;
import com.alechilles.alecstamework.companion.provisioning.runtime.ProvisioningActivationWorldAttempt.ProjectionStatus;
import com.alechilles.alecstamework.items.HytaleCompanionProjectionSpawnExecutor;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nullable;

/** Exact activation projection insertion and same-chunk evidence. */
final class HytaleProvisioningActivationProjectionGateway {
    private final World world;
    private final Store<EntityStore> store;
    private final ProvisioningActivationRequest request;
    private final OperationEnvelope operation;
    private final ProvisioningActivationSnapshotResolver snapshots;
    private final HytaleCompanionProjectionSpawnExecutor projections;

    HytaleProvisioningActivationProjectionGateway(
            World world,
            Store<EntityStore> store,
            ProvisioningActivationRequest request,
            OperationEnvelope operation,
            ProvisioningActivationSnapshotResolver snapshots,
            HytaleCompanionProjectionSpawnExecutor projections
    ) {
        this.world = world;
        this.store = store;
        this.request = request;
        this.operation = operation;
        this.snapshots = snapshots;
        this.projections = projections;
    }

    ProjectionProbe probe() {
        return probe(null);
    }

    ProjectionProbe probeInChunk(long expectedChunkIndex) {
        return probe(expectedChunkIndex);
    }

    ProjectionAttempt applyOrResolve() {
        ProjectionProbe initial = probe();
        if (initial.status() == ProjectionStatus.EXACT) {
            return ProjectionAttempt.exact(initial.chunkIndex());
        }
        if (initial.status() == ProjectionStatus.UNAVAILABLE) {
            return ProjectionAttempt.retryable(initial.cause());
        }
        if (initial.status() == ProjectionStatus.CONFLICT) {
            return ProjectionAttempt.conflict(initial.cause());
        }
        LiveOperationResult result = projections.applyOrResolve(
                world,
                store,
                command(),
                () -> snapshots.resolve(request)
        );
        if (result.status() == LiveOperationResult.Status.RETRYABLE) {
            return ProjectionAttempt.retryable(result.cause());
        }
        if (result.status() != LiveOperationResult.Status.CONFIRMED) {
            return ProjectionAttempt.conflict(result.cause());
        }
        ProjectionProbe confirmed = probe();
        return switch (confirmed.status()) {
            case EXACT -> ProjectionAttempt.exact(
                    confirmed.chunkIndex()
            );
            case UNAVAILABLE -> ProjectionAttempt.retryable(
                    confirmed.cause()
            );
            case ABSENT, CONFLICT -> ProjectionAttempt.conflict(
                    confirmed.cause()
            );
        };
    }

    private HytaleCompanionProjectionSpawnExecutor.ProjectionCommand
    command() {
        return new HytaleCompanionProjectionSpawnExecutor.ProjectionCommand(
                "provisioning_activation",
                request.origin().profileId(),
                operation.operationId(),
                TameworkProjectionIdentityComponent
                        .KIND_PROVISIONING_ACTIVATION,
                request.targetAlias(),
                null,
                request.spawnReceiptKey(),
                request.groupAdmission().before().revision().value(),
                request.placement()
        );
    }

    private ProjectionProbe probe(@Nullable Long expectedChunkIndex) {
        try {
            store.assertThread();
            Ref<EntityStore> target =
                    world.getEntityRef(request.targetAlias().value());
            if (target == null || !target.isValid()) {
                return ProjectionProbe.absent();
            }
            Components components = components(target);
            if (components == null) {
                return ProjectionProbe.unavailable(null);
            }
            if (!exact(components)) {
                return ProjectionProbe.conflict(null);
            }
            WorldChunk chunk = currentChunk(components.transform());
            if (chunk == null) {
                return ProjectionProbe.unavailable(null);
            }
            if (expectedChunkIndex != null
                    && chunk.getIndex() != expectedChunkIndex) {
                return ProjectionProbe.conflict(null);
            }
            return ProjectionProbe.exact(chunk.getIndex());
        } catch (RuntimeException | LinkageError failure) {
            return ProjectionProbe.unavailable(failure);
        }
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
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType =
                TameworkOwnerComponent.getComponentType();
        ComponentType<EntityStore, TameworkTamedComponent> tamedType =
                TameworkTamedComponent.getComponentType();
        ComponentType<EntityStore, TransformComponent> transformType =
                TransformComponent.getComponentType();
        if (uuidType == null || npcType == null || markerType == null
                || ownerType == null || tamedType == null
                || transformType == null) {
            return null;
        }
        return new Components(
                store.getComponent(target, uuidType),
                store.getComponent(target, npcType),
                store.getComponent(target, markerType),
                store.getComponent(target, ownerType),
                store.getComponent(target, tamedType),
                store.getComponent(target, transformType)
        );
    }

    private boolean exact(Components components) {
        TameworkProjectionIdentityComponent marker = components.marker();
        return components.identity() != null
                && request.targetAlias().value().equals(
                        components.identity().getUuid()
                )
                && components.npc() != null
                && request.targetAlias().value().equals(
                        components.npc().getUuid()
                )
                && components.npc().getRoleName() != null
                && request.expectedRoleId().equals(
                        components.npc().getRoleName()
                )
                && marker != null
                && request.origin().profileId().toString().equals(
                        marker.getProfileId()
                )
                && operation.operationId().toString().equals(
                        marker.getOperationId()
                )
                && TameworkProjectionIdentityComponent
                        .KIND_PROVISIONING_ACTIVATION.equals(
                                marker.getProjectionKind()
                        )
                && request.spawnReceiptKey().equals(marker.getSlotKey())
                && marker.getSourceNpcUuid() == null
                && request.groupAdmission().before().revision().value()
                        == marker.getGeneration()
                && components.owner() != null
                && request.groupAdmission().before().ownerId().value()
                        .equals(components.owner().getOwnerId())
                && components.tamed() != null
                && components.tamed().isTamed();
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
            TameworkOwnerComponent owner,
            TameworkTamedComponent tamed,
            TransformComponent transform
    ) {
    }
}
