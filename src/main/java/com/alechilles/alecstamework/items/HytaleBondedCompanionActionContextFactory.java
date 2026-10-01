package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedCompanionActionContext;
import com.alechilles.alecstamework.api.BondedCompanionPlacement;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nullable;

/**
 * Freezes the panel placement of a bonded action and gives it the player's inventory to pay from
 * (plan 6 R19: charge, then refund on failure; no escrow). Call on the player's world thread.
 */
final class HytaleBondedCompanionActionContextFactory {
    private static final double DEFAULT_DISTANCE = 5D;
    private final CommandCompanionPlacementService placements =
            new CommandCompanionPlacementService();

    @Nullable
    BondedCompanionActionContext create(
            Player player, Store<EntityStore> store, String roleId,
            boolean placementRequired) {
        Ref<EntityStore> playerRef = player == null ? null : player.getReference();
        World world = player == null ? null : player.getWorld();
        if (playerRef == null || !playerRef.isValid() || store == null
                || playerRef.getStore() != store || player.getUuid() == null
                || world == null) {
            return null;
        }
        TwCompanionConfig.EffectiveSettings settings =
                TwCompanionConfig.resolveEffectiveForRole(roleId);
        double distance = settings == null
                || !Double.isFinite(settings.getRecallSafeSpawnDistance())
                || settings.getRecallSafeSpawnDistance() <= 0D
                ? DEFAULT_DISTANCE : settings.getRecallSafeSpawnDistance();
        CompanionSpawnPlacement placement = null;
        if (placementRequired) {
            try {
                placement = placements.computeRestorationPlacement(
                        playerRef, store, distance, roleId, null);
            } catch (RuntimeException | LinkageError unavailable) {
                // No placement: the panel shows the summon as unavailable here and the API
                // refuses it, while the rest of the card (and the inventory) still works.
            }
        }
        return new BondedCompanionActionContext(
                placement == null ? null : new BondedCompanionPlacement(
                        placement.worldKey(), placement.x(), placement.y(),
                        placement.z(), placement.pitchRadians(),
                        placement.yawRadians(), placement.rollRadians()),
                new BondedCompanionChargeInventory(world, store, playerRef, player.getUuid()));
    }
}
