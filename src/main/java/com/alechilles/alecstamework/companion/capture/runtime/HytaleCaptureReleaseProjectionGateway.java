package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseRequest;
import com.alechilles.alecstamework.compat.HytaleChunkAccess;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.ProjectionProbe;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.ProjectionStatus;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotDecodeResult;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.CompanionReturnStateNormalizer;
import com.alechilles.alecstamework.items.HytaleCompanionProjectionSpawnExecutor;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.NonTicking;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Exact target projection receipt, runtime movement hold, and full-state projection bridge.
 */
final class HytaleCaptureReleaseProjectionGateway {
    private final World world;
    private final Store<EntityStore> store;
    private final CompanionCaptureReleaseRequest request;
    private final OperationEnvelope operation;
    private final SnapshotCodecRegistry snapshotCodecs;
    private final HytaleCompanionProjectionSpawnExecutor projections;

    HytaleCaptureReleaseProjectionGateway(
            World world,
            Store<EntityStore> store,
            CompanionCaptureReleaseRequest request,
            OperationEnvelope operation,
            SnapshotCodecRegistry snapshotCodecs,
            HytaleCompanionProjectionSpawnExecutor projections
    ) {
        this.world = world;
        this.store = store;
        this.request = request;
        this.operation = operation;
        this.snapshotCodecs = snapshotCodecs;
        this.projections = projections;
    }

    LiveOperationResult applyOrResolve() {
        if (!CompanionFullStateProjection.KIND.equals(
                request.projection().kind()
        ) || request.projection().payloadVersion()
                != CompanionFullStateProjection.VERSION) {
            return LiveOperationResult.unknown(
                    "capture_release_projection_codec_mismatch",
                    null
            );
        }
        LiveOperationResult result = projections.applyOrResolve(
                world,
                store,
                command(),
                () -> decodeProjection(snapshotCodecs, request.projection())
        );
        return result.status() == LiveOperationResult.Status.CONFIRMED
                ? ensureHold(result)
                : result;
    }

    static SnapshotDecodeResult<CoopResidentStateSnapshot> decodeProjection(
            SnapshotCodecRegistry codecs,
            SnapshotCodecRegistry.EncodedSnapshot projection
    ) {
        SnapshotDecodeResult<CoopResidentStateSnapshot> decoded =
                codecs.decode(
                        projection,
                        CoopResidentStateSnapshot.class
                );
        if (decoded instanceof SnapshotDecodeResult.Decoded<
                CoopResidentStateSnapshot> found) {
            return new SnapshotDecodeResult.Decoded<>(
                    CompanionReturnStateNormalizer.forCaptureRelease(
                            found.value()
                    )
            );
        }
        return decoded;
    }

    ProjectionProbe probe() {
        return probe(null);
    }

    ProjectionProbe probeInChunk(long expectedChunkIndex) {
        return probe(expectedChunkIndex);
    }

    long receiptChunkIndex() {
        return HytaleCompanionCaptureReleaseBoundary.receiptChunkIndex(
                request.placement()
        );
    }

    void releaseHold() {
        Ref<EntityStore> target =
                world.getEntityRef(request.targetAlias().value());
        if (target == null || !target.isValid()) {
            return;
        }
        if (!hasExactMarker(target)) {
            throw new IllegalStateException(
                    "Capture release projection receipt changed before hold release"
            );
        }
        ComponentType<EntityStore, NonTicking<EntityStore>> nonTicking =
                EntityStore.REGISTRY.getNonTickingComponentType();
        releaseRuntimeHold(
                store,
                target,
                EntityTrackerSystems.Visible.getComponentType(),
                nonTicking
        );
    }

    static void releaseRuntimeHold(
            Store<EntityStore> store,
            Ref<EntityStore> target,
            ComponentType<EntityStore, EntityTrackerSystems.Visible>
                    visibleType,
            ComponentType<EntityStore, NonTicking<EntityStore>> nonTickingType
    ) {
        if (store.getComponent(target, nonTickingType) == null) {
            return;
        }
        // While held, viewer-side tracking can advance even though this
        // entity's ticking update systems cannot send their initial state.
        // Reset visibility before resuming so the tracker treats every
        // current viewer as newly visible and sends the full component set.
        store.tryRemoveComponent(target, visibleType);
        store.tryRemoveComponent(target, nonTickingType);
    }

    private boolean hasExactMarker(Ref<EntityStore> target) {
        ComponentType<EntityStore, UUIDComponent> uuidType =
                UUIDComponent.getComponentType();
        ComponentType<EntityStore, NPCEntity> npcType =
                NPCEntity.getComponentType();
        ComponentType<EntityStore, TameworkProjectionIdentityComponent>
                receiptType =
                TameworkProjectionIdentityComponent.getComponentType();
        return uuidType != null
                && npcType != null
                && receiptType != null
                && exact(
                store.getComponent(target, uuidType),
                store.getComponent(target, npcType),
                store.getComponent(target, receiptType)
        );
    }

    private HytaleCompanionProjectionSpawnExecutor.ProjectionCommand
    command() {
        return new HytaleCompanionProjectionSpawnExecutor.ProjectionCommand(
                "capture_release",
                request.profileId(),
                operation.operationId(),
                TameworkProjectionIdentityComponent.KIND_CAPTURE_RELEASE,
                request.targetAlias(),
                request.sourceAlias().value(),
                request.spawnReceiptKey(),
                request.expectedLifecycleRevision().value(),
                request.placement()
        );
    }

    private LiveOperationResult ensureHold(
            LiveOperationResult confirmed
    ) {
        ProjectionProbe projection = probe();
        if (projection.status() == ProjectionStatus.UNAVAILABLE) {
            return LiveOperationResult.retryable(
                    "capture_release_projection_hold_unavailable",
                    projection.cause()
            );
        }
        if (projection.status() != ProjectionStatus.EXACT) {
            return LiveOperationResult.unknown(
                    "capture_release_projection_hold_conflict",
                    projection.cause()
            );
        }
        try {
            Ref<EntityStore> target =
                    world.getEntityRef(request.targetAlias().value());
            if (target == null || !target.isValid()) {
                return LiveOperationResult.retryable(
                        "capture_release_projection_hold_unavailable",
                        null
                );
            }
            store.ensureComponent(
                    target,
                    EntityStore.REGISTRY.getNonTickingComponentType()
            );
            return confirmed;
        } catch (RuntimeException | LinkageError failure) {
            return LiveOperationResult.retryable(
                    "capture_release_projection_hold_failed",
                    failure
            );
        }
    }

    private ProjectionProbe probe(@Nullable Long expectedChunkIndex) {
        Ref<EntityStore> target =
                world.getEntityRef(request.targetAlias().value());
        if (target == null || !target.isValid()) {
            return ProjectionProbe.absent();
        }
        ComponentType<EntityStore, UUIDComponent> uuidType =
                UUIDComponent.getComponentType();
        ComponentType<EntityStore, NPCEntity> npcType =
                NPCEntity.getComponentType();
        ComponentType<EntityStore, TameworkProjectionIdentityComponent>
                receiptType =
                TameworkProjectionIdentityComponent.getComponentType();
        ComponentType<EntityStore, TransformComponent> transformType =
                TransformComponent.getComponentType();
        if (uuidType == null || npcType == null || receiptType == null
                || transformType == null) {
            return ProjectionProbe.unavailable(null);
        }
        try {
            UUIDComponent identity = store.getComponent(target, uuidType);
            NPCEntity npc = store.getComponent(target, npcType);
            TameworkProjectionIdentityComponent receipt =
                    store.getComponent(target, receiptType);
            TransformComponent transform =
                    store.getComponent(target, transformType);
            if (!exact(identity, npc, receipt)) {
                return ProjectionProbe.conflict(null);
            }
            WorldChunk chunk = currentChunk(transform);
            if (chunk == null) {
                return ProjectionProbe.unavailable(null);
            }
            if (expectedChunkIndex != null
                    && chunk.getIndex() != expectedChunkIndex) {
                return ProjectionProbe.moved(chunk.getIndex());
            }
            return ProjectionProbe.exact(chunk.getIndex());
        } catch (RuntimeException | LinkageError failure) {
            return ProjectionProbe.unavailable(failure);
        }
    }

    @Nullable
    private WorldChunk currentChunk(
            @Nullable TransformComponent transform
    ) {
        return HytaleChunkAccess.currentWorldChunk(transform, world);
    }

    private boolean exact(
            @Nullable UUIDComponent identity,
            @Nullable NPCEntity npc,
            @Nullable TameworkProjectionIdentityComponent receipt
    ) {
        return identity != null
                && request.targetAlias().value().equals(identity.getUuid())
                && npc != null
                && request.targetAlias().value().equals(npc.getUuid())
                && receipt != null
                && request.profileId().toString().equals(
                receipt.getProfileId()
        )
                && operation.operationId().toString().equals(
                receipt.getOperationId()
        )
                && TameworkProjectionIdentityComponent
                .KIND_CAPTURE_RELEASE.equals(receipt.getProjectionKind())
                && request.spawnReceiptKey().equals(receipt.getSlotKey())
                && Objects.equals(
                request.sourceAlias().value(),
                receipt.getSourceNpcUuid()
        )
                && request.expectedLifecycleRevision().value()
                == receipt.getGeneration();
    }
}
