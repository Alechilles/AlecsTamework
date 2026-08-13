package com.alechilles.alecstamework.companion.revival.runtime;

import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.ProjectionAttempt;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.SpawnProbe;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.SpawnStatus;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotDecodeResult;
import com.alechilles.alecstamework.compat.HytaleChunkAccess;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.CompanionReturnStateNormalizer;
import com.alechilles.alecstamework.items.HytaleCompanionProjectionSpawnExecutor;
import com.alechilles.alecstamework.items.RecentRespawnTraceService;
import com.alechilles.alecstamework.items.RespawnTraceLogSupport;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
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
import java.util.Objects;
import javax.annotation.Nullable;

/** Same-profile projection application and exact target-chunk evidence. */
final class HytalePaidRevivalProjectionGateway {
    private static final String PROJECTION_KIND = "PAID_REVIVAL";

    private final World world;
    private final Store<EntityStore> store;
    private final PaidRevivalRequest request;
    private final OperationEnvelope operation;
    private final SnapshotCodecRegistry snapshotCodecs;
    private final HytaleCompanionProjectionSpawnExecutor projections;

    HytalePaidRevivalProjectionGateway(
            World world,
            Store<EntityStore> store,
            PaidRevivalRequest request,
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

    SpawnProbe probe() {
        return probe(null);
    }

    SpawnProbe probeInChunk(long expectedChunkIndex) {
        return probe(expectedChunkIndex);
    }

    ProjectionAttempt applyOrResolve() {
        RecentRespawnTraceService.Trace trace = startTrace();
        RespawnTraceLogSupport.log(
                trace,
                "start profile=" + request.sourceSnapshot().profileId()
                        + " operation=" + operation.operationId()
                        + " source=" + request.projection().sourceAlias()
                        + " target=" + request.targetAlias()
        );
        SpawnProbe initial = probe();
        if (initial.status() == SpawnStatus.EXACT) {
            logProjection(trace, "initial_probe", "EXACT", true);
            return ProjectionAttempt.exact(initial.chunkIndex());
        }
        if (initial.status() == SpawnStatus.UNAVAILABLE) {
            logProjection(trace, "initial_probe", "UNAVAILABLE", false);
            return ProjectionAttempt.retryable(initial.cause());
        }
        if (initial.status() == SpawnStatus.CONFLICT) {
            logProjection(trace, "initial_probe", "CONFLICT", false);
            return ProjectionAttempt.conflict(initial.cause());
        }
        LiveOperationResult result = projections.applyOrResolve(
                world,
                store,
                command(),
                () -> decodeProjection(trace)
        );
        logProjection(
                trace,
                "apply",
                result.status() + ":" + result.code(),
                result.status() == LiveOperationResult.Status.CONFIRMED
        );
        if (result.status() == LiveOperationResult.Status.RETRYABLE) {
            return ProjectionAttempt.retryable(result.cause());
        }
        if (result.status() != LiveOperationResult.Status.CONFIRMED) {
            return ProjectionAttempt.conflict(result.cause());
        }
        SpawnProbe confirmed = probe();
        return confirmed.status() == SpawnStatus.EXACT
                ? ProjectionAttempt.exact(confirmed.chunkIndex())
                : confirmed.status() == SpawnStatus.UNAVAILABLE
                ? ProjectionAttempt.retryable(confirmed.cause())
                : ProjectionAttempt.conflict(confirmed.cause());
    }

    private SnapshotDecodeResult<CoopResidentStateSnapshot>
    decodeProjection(@Nullable RecentRespawnTraceService.Trace trace) {
        SnapshotCodecRegistry.EncodedSnapshot projection =
                request.projection().fullState();
        if (!CompanionFullStateProjection.KIND.equals(
                projection.kind()
        )
                || projection.payloadVersion()
                != CompanionFullStateProjection.VERSION) {
            return new SnapshotDecodeResult.Failed<>(
                    SnapshotDecodeResult.Failure.TYPE_MISMATCH,
                    "paid_revival_projection_kind_mismatch",
                    null
            );
        }
        SnapshotDecodeResult<CoopResidentStateSnapshot> decoded =
                snapshotCodecs.decode(
                        projection, CoopResidentStateSnapshot.class
                );
        RespawnTraceLogSupport.logDecodedProjection(
                trace, "paid_revival_stored", decoded
        );
        SnapshotDecodeResult<CoopResidentStateSnapshot> normalized =
                normalizeProjection(decoded);
        RespawnTraceLogSupport.logDecodedProjection(
                trace, "paid_revival_normalized", normalized
        );
        return normalized;
    }

    private void logProjection(
            @Nullable RecentRespawnTraceService.Trace trace,
            String stage,
            String result,
            boolean confirmed
    ) {
        RespawnTraceLogSupport.logProjectionResult(
                world,
                request.targetAlias().value(),
                trace,
                "paid_revival_" + stage,
                result,
                confirmed
        );
    }

    @Nullable
    private RecentRespawnTraceService.Trace startTrace() {
        if (!RespawnTraceLogSupport.isEnabled()) {
            return null;
        }
        return RespawnTraceLogSupport.startTrace(
                "paid_revival",
                request.projection().sourceAlias().value(),
                request.familyKey().ownerId().value(),
                null,
                request.familyKey().familyId()
        );
    }

    static SnapshotDecodeResult<CoopResidentStateSnapshot> normalizeProjection(
            SnapshotDecodeResult<CoopResidentStateSnapshot> decoded
    ) {
        if (decoded instanceof SnapshotDecodeResult.Decoded<
                CoopResidentStateSnapshot> found) {
            return new SnapshotDecodeResult.Decoded<>(
                    CompanionReturnStateNormalizer.forDeathRestoration(
                            found.value()
                    )
            );
        }
        return decoded;
    }

    private HytaleCompanionProjectionSpawnExecutor.ProjectionCommand
    command() {
        return new HytaleCompanionProjectionSpawnExecutor.ProjectionCommand(
                "paid_revival",
                request.sourceSnapshot().profileId(),
                operation.operationId(),
                PROJECTION_KIND,
                request.targetAlias(),
                request.projection().sourceAlias().value(),
                request.spawnReceiptKey(),
                request.groupAdmission().before().revision().value(),
                request.placement()
        );
    }

    private SpawnProbe probe(@Nullable Long expectedChunkIndex) {
        try {
            store.assertThread();
            Ref<EntityStore> target =
                    world.getEntityRef(request.targetAlias().value());
            if (target == null || !target.isValid()) {
                return SpawnProbe.absent();
            }
            Components components = components(target);
            if (components == null) {
                return SpawnProbe.unavailable(null);
            }
            if (!exact(components)) {
                return SpawnProbe.conflict(null);
            }
            WorldChunk chunk = currentChunk(components.transform());
            if (chunk == null) {
                return SpawnProbe.unavailable(null);
            }
            if (expectedChunkIndex != null
                    && chunk.getIndex() != expectedChunkIndex) {
                return SpawnProbe.conflict(null);
            }
            return SpawnProbe.exact(chunk.getIndex());
        } catch (RuntimeException | LinkageError failure) {
            return SpawnProbe.unavailable(failure);
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

    private boolean exact(Components components) {
        return components.identity() != null
                && request.targetAlias().value().equals(
                components.identity().getUuid()
        )
                && components.npc() != null
                && components.marker() != null
                && request.sourceSnapshot().profileId().toString().equals(
                components.marker().getProfileId()
        )
                && operation.operationId().toString().equals(
                components.marker().getOperationId()
        )
                && PROJECTION_KIND.equals(
                components.marker().getProjectionKind()
        )
                && request.spawnReceiptKey().equals(
                components.marker().getSlotKey()
        )
                && Objects.equals(
                request.projection().sourceAlias().value(),
                components.marker().getSourceNpcUuid()
        )
                && request.groupAdmission().before().revision().value()
                == components.marker().getGeneration();
    }

    @Nullable
    private WorldChunk currentChunk(
            @Nullable TransformComponent transform
    ) {
        return HytaleChunkAccess.currentWorldChunk(transform, world);
    }

    private record Components(
            UUIDComponent identity,
            NPCEntity npc,
            TameworkProjectionIdentityComponent marker,
            TransformComponent transform
    ) {
    }
}
