package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.snapshot.SnapshotDecodeResult;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Adapts a bonded lease spawn to the generic projection executor without
 * leaking generic operation authorities into the isolated bonded domain.
 */
public final class BondedCompanionProjectionSpawnBoundary {
    private final HytaleCompanionProjectionSpawnExecutor executor;

    public BondedCompanionProjectionSpawnBoundary() {
        this(new HytaleCompanionProjectionSpawnExecutor());
    }

    BondedCompanionProjectionSpawnBoundary(
            HytaleCompanionProjectionSpawnExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** Applies or resolves the exact lease projection on the current world thread. */
    @Nonnull
    public Outcome spawn(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull String profileId,
            @Nonnull String leaseToken,
            @Nonnull UUID plannedNpcUuid,
            UUID sourceNpcUuid,
            @Nonnull CompanionSpawnPlacement placement,
            @Nonnull BondedCompanionSnapshot snapshot
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        var command = new HytaleCompanionProjectionSpawnExecutor
                .ProjectionCommand(
                "bonded_companion_summon",
                ProfileId.parse(profileId), OperationId.parse(leaseToken),
                TameworkProjectionIdentityComponent.KIND_BONDED_COMPANION,
                new NpcAlias(plannedNpcUuid), sourceNpcUuid,
                leaseToken, 0L, placement);
        LiveOperationResult result = executor.applyOrResolve(
                world, store, command,
                () -> new SnapshotDecodeResult.Decoded<>(
                        snapshot.fullState()));
        return switch (result.status()) {
            case CONFIRMED -> Outcome.CONFIRMED;
            case RETRYABLE -> Outcome.RETRYABLE;
            case COMPENSATE, UNKNOWN -> Outcome.FAILED;
        };
    }

    /** Minimal result vocabulary understood by the bonded world gateway. */
    public enum Outcome { CONFIRMED, RETRYABLE, FAILED }
}
