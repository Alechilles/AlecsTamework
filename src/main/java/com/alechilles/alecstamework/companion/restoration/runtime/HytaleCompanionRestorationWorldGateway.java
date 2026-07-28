package com.alechilles.alecstamework.companion.restoration.runtime;

import com.alechilles.alecstamework.companion.restoration.CompanionRestorationDefinition;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationRequest;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotDecodeResult;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.HytaleCompanionProjectionSpawnExecutor;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Production receipt-first gateway for death and lost companion restoration. */
public final class HytaleCompanionRestorationWorldGateway
        implements CompanionRestorationWorldGateway {
    private final SnapshotCodecRegistry snapshotCodecs;
    private final HytaleCompanionProjectionSpawnExecutor projections;

    public HytaleCompanionRestorationWorldGateway(
            @Nonnull SnapshotCodecRegistry snapshotCodecs,
            @Nonnull HytaleCompanionProjectionSpawnExecutor projections
    ) {
        this.snapshotCodecs = Objects.requireNonNull(
                snapshotCodecs, "snapshotCodecs"
        );
        this.projections = Objects.requireNonNull(projections, "projections");
    }

    @Override
    @Nonnull
    public LiveOperationResult applyOrResolve(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull CompanionRestorationRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        if (!validOperation(request, operation)) {
            return LiveOperationResult.unknown(
                    "restoration_operation_invariant_mismatch",
                    null
            );
        }
        HytaleCompanionProjectionSpawnExecutor.ProjectionCommand command =
                new HytaleCompanionProjectionSpawnExecutor.ProjectionCommand(
                        "restoration",
                        request.profileId(),
                        operation.operationId(),
                        TameworkProjectionIdentityComponent.KIND_RECOVERY,
                        request.targetAlias(),
                        request.projection().sourceAlias().value(),
                        request.spawnReceiptKey(),
                        request.expectedLifecycleRevision().value(),
                        request.placement()
                );
        return projections.applyOrResolve(
                world,
                store,
                command,
                () -> decodeProjection(request)
        );
    }

    @Nonnull
    private SnapshotDecodeResult<CoopResidentStateSnapshot> decodeProjection(
            CompanionRestorationRequest request
    ) {
        SnapshotCodecRegistry.EncodedSnapshot projection =
                request.projection().fullState();
        if (!CompanionFullStateProjection.KIND.equals(
                projection.kind()
        )
                || projection.payloadVersion()
                != CompanionFullStateProjection.VERSION) {
            return new SnapshotDecodeResult.Failed<>(
                    SnapshotDecodeResult.Failure.TYPE_MISMATCH,
                    "projection_kind_mismatch",
                    null
            );
        }
        return snapshotCodecs.decode(
                projection,
                CoopResidentStateSnapshot.class
        );
    }

    private boolean validOperation(
            CompanionRestorationRequest request,
            OperationEnvelope operation
    ) {
        return request != null
                && operation != null
                && CompanionRestorationDefinition.KIND.equals(operation.kind())
                && request.expectedLifecycleRevision().equals(
                        operation.expectedLifecycleRevision()
                );
    }
}
