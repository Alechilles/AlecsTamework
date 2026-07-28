package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.snapshot.SnapshotDecodeResult;
import com.alechilles.alecstamework.effects.TameworkEntityEffectService;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
            @Nonnull BondedCompanionSnapshot snapshot,
            @Nullable String summonAuraEffectId
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        boolean applySummonAura = summonAuraEffectId != null
                && !summonAuraEffectId.isBlank()
                && projectionMissing(world, store, plannedNpcUuid);
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
        if (result.status() == LiveOperationResult.Status.CONFIRMED
                && applySummonAura) {
            applySummonAura(world, store, plannedNpcUuid, summonAuraEffectId);
        }
        return switch (result.status()) {
            case CONFIRMED -> Outcome.CONFIRMED;
            case RETRYABLE -> Outcome.RETRYABLE;
            case COMPENSATE, UNKNOWN -> Outcome.FAILED;
        };
    }

    /** Visual feedback is best-effort and never changes summon durability. */
    private static void applySummonAura(
            World world,
            Store<EntityStore> store,
            UUID plannedNpcUuid,
            String effectId
    ) {
        try {
            TameworkEntityEffectService.applyEffect(
                    world.getEntityRef(plannedNpcUuid), effectId, store);
        } catch (RuntimeException | LinkageError ignored) {
            // The durable projection was already confirmed; a cosmetic failure is safe.
        }
    }

    private static boolean projectionMissing(
            World world,
            Store<EntityStore> store,
            UUID plannedNpcUuid
    ) {
        Ref<EntityStore> reference = world.getEntityRef(plannedNpcUuid);
        return reference == null || !reference.isValid()
                || reference.getStore() != store;
    }

    /** Minimal result vocabulary understood by the bonded world gateway. */
    public enum Outcome { CONFIRMED, RETRYABLE, FAILED }
}
