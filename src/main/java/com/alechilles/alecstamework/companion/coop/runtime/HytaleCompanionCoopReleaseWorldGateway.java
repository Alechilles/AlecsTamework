package com.alechilles.alecstamework.companion.coop.runtime;

import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseDefinition;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseRequest;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotDecodeResult;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.HytaleCompanionProjectionSpawnExecutor;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Production receipt-first gateway for one exact coop-to-live projection. */
public final class HytaleCompanionCoopReleaseWorldGateway
        implements CompanionCoopReleaseWorldGateway {
    private final SnapshotCodecRegistry snapshotCodecs;
    private final HytaleCompanionProjectionSpawnExecutor projections;
    private final CoopTransitionEffectSink effects;

    public HytaleCompanionCoopReleaseWorldGateway(
            @Nonnull SnapshotCodecRegistry snapshotCodecs,
            @Nonnull HytaleCompanionProjectionSpawnExecutor projections
    ) {
        this(snapshotCodecs, projections, CoopTransitionEffectSink.NONE);
    }

    public HytaleCompanionCoopReleaseWorldGateway(
            @Nonnull SnapshotCodecRegistry snapshotCodecs,
            @Nonnull HytaleCompanionProjectionSpawnExecutor projections,
            @Nonnull CoopTransitionEffectSink effects
    ) {
        this.snapshotCodecs = Objects.requireNonNull(
                snapshotCodecs, "snapshotCodecs"
        );
        this.projections = Objects.requireNonNull(projections, "projections");
        this.effects = Objects.requireNonNull(effects, "effects");
    }

    @Override
    @Nonnull
    public LiveOperationResult applyOrResolve(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull CompanionCoopReleaseRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        if (!validOperation(request, operation)) {
            return LiveOperationResult.unknown(
                    "coop_release_operation_invariant_mismatch",
                    null
            );
        }
        UUID sourceNpcUuid = request.sourceResidency().housedNpcAlias() == null
                ? null
                : request.sourceResidency().housedNpcAlias().value();
        HytaleCompanionProjectionSpawnExecutor.ProjectionCommand command =
                new HytaleCompanionProjectionSpawnExecutor.ProjectionCommand(
                        "coop_release",
                        request.profileId(),
                        operation.operationId(),
                        TameworkProjectionIdentityComponent
                                .KIND_MANAGED_COOP_RELEASE,
                        request.targetAlias(),
                        sourceNpcUuid,
                        request.spawnReceiptKey(),
                        request.expectedLifecycleRevision().value(),
                        request.placement()
                );
        LiveOperationResult result = projections.applyOrResolve(
                world,
                store,
                command,
                () -> decodeProjection(request)
        );
        if (result.status() == LiveOperationResult.Status.CONFIRMED
                && "coop_release_spawned".equals(result.code())) {
            playTransitionEffect(world, request);
        }
        return result;
    }

    private void playTransitionEffect(
            World world,
            CompanionCoopReleaseRequest request
    ) {
        try {
            effects.play(
                    world,
                    request.placement().x(),
                    request.placement().y(),
                    request.placement().z(),
                    request.sourceResidency().slotKey().coopId()
            );
        } catch (RuntimeException | LinkageError ignored) {
            // Presentation cannot change an exact spawn result.
        }
    }

    @Nonnull
    private SnapshotDecodeResult<CoopResidentStateSnapshot> decodeProjection(
            CompanionCoopReleaseRequest request
    ) {
        SnapshotDecodeResult<CoopResidentStateSnapshot> decoded =
                snapshotCodecs.decode(
                        request.sourceSnapshot(),
                        CoopResidentStateSnapshot.class
                );
        if (!(decoded instanceof SnapshotDecodeResult.Decoded<?> found)) {
            return decoded;
        }
        CoopResidentStateSnapshot snapshot =
                (CoopResidentStateSnapshot) found.value();
        if (!request.sourceResidency().slotKey().coopId().equalsIgnoreCase(
                snapshot.coopId()
        )
                || request.sourceResidency().slotKey().residentSlot()
                != snapshot.residentSlot()) {
            return new SnapshotDecodeResult.Failed<>(
                    SnapshotDecodeResult.Failure.DECODE_FAILED,
                    "projection_slot_mismatch",
                    null
            );
        }
        return decoded;
    }

    private boolean validOperation(
            CompanionCoopReleaseRequest request,
            OperationEnvelope operation
    ) {
        return request != null
                && operation != null
                && CompanionCoopReleaseDefinition.KIND.equals(operation.kind())
                && request.expectedLifecycleRevision().equals(
                        operation.expectedLifecycleRevision()
                );
    }
}
