package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.BondedCompanionActionContext;
import com.alechilles.alecstamework.api.BondedCompanionPlacement;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.items.components
        .TameworkBondedReviveEscrowComponent;
import com.alechilles.alecstamework.persistence.runtime.player
        .TameworkInventoryOperationReceiptsComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** Freezes panel placement and exposes a durable bonded-payment escrow. */
final class HytaleBondedCompanionActionContextFactory {
    private static final double DEFAULT_DISTANCE = 5D;
    private final CommandCompanionPlacementService placements =
            new CommandCompanionPlacementService();
    private final Supplier<ComponentType<EntityStore,
            TameworkBondedReviveEscrowComponent>> escrowTypes;
    private final Supplier<ComponentType<EntityStore,
            TameworkInventoryOperationReceiptsComponent>> receiptTypes;

    HytaleBondedCompanionActionContextFactory() {
        this(HytaleBondedCompanionActionContextFactory::resolveEscrowType,
                HytaleBondedCompanionActionContextFactory::resolveReceiptType);
    }

    HytaleBondedCompanionActionContextFactory(
            @Nullable ComponentType<EntityStore,
                    TameworkBondedReviveEscrowComponent> escrowType,
            @Nullable ComponentType<EntityStore,
                    TameworkInventoryOperationReceiptsComponent> receiptType) {
        this(() -> escrowType, () -> receiptType);
    }

    private HytaleBondedCompanionActionContextFactory(
            Supplier<ComponentType<EntityStore,
                    TameworkBondedReviveEscrowComponent>> escrowTypes,
            Supplier<ComponentType<EntityStore,
                    TameworkInventoryOperationReceiptsComponent>> receiptTypes) {
        this.escrowTypes = escrowTypes;
        this.receiptTypes = receiptTypes;
    }

    @Nullable
    BondedCompanionActionContext create(
            Player player, Store<EntityStore> store, String roleId,
            boolean placementRequired) {
        Ref<EntityStore> playerRef = player == null ? null : player.getReference();
        World world = player == null ? null : player.getWorld();
        ComponentType<EntityStore, TameworkBondedReviveEscrowComponent>
                escrowType = escrowTypes.get();
        if (playerRef == null || !playerRef.isValid() || store == null
                || playerRef.getStore() != store || player.getUuid() == null
                || world == null || escrowType == null) {
            return null;
        }
        TwCompanionConfig.EffectiveSettings settings =
                TwCompanionConfig.resolveEffectiveForRole(roleId);
        double distance = settings == null
                || !Double.isFinite(settings.getRecallSafeSpawnDistance())
                || settings.getRecallSafeSpawnDistance() <= 0D
                ? DEFAULT_DISTANCE : settings.getRecallSafeSpawnDistance();
        var placement = placementRequired
                ? placements.computeRestorationPlacement(
                playerRef, store, distance, roleId, null)
                : null;
        String worldKey = world.getName();
        return new BondedCompanionActionContext(
                placement == null ? null : new BondedCompanionPlacement(
                        placement.worldKey(), placement.x(), placement.y(),
                        placement.z(), placement.pitchRadians(),
                        placement.yawRadians(), placement.rollRadians()),
                new HytaleBondedCompanionEscrowInventory(
                world, store, player.getUuid(), worldKey, escrowType,
                receiptTypes.get()));
    }

    @Nullable
    private static ComponentType<EntityStore,
            TameworkBondedReviveEscrowComponent> resolveEscrowType() {
        try {
            Tamework plugin = Tamework.getInstance();
            return plugin == null
                    ? null : plugin.getBondedReviveEscrowComponentType();
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    @Nullable
    private static ComponentType<EntityStore,
            TameworkInventoryOperationReceiptsComponent> resolveReceiptType() {
        try {
            Tamework plugin = Tamework.getInstance();
            return plugin == null
                    ? null : plugin.getInventoryOperationReceiptsComponentType();
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }
}
