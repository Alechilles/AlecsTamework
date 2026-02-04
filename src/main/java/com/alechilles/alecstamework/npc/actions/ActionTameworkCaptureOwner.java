package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.items.SpawnerFeatureHandler;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.UUID;

/**
 * Captures NPCs when the interacting player is the owner.
 */
public final class ActionTameworkCaptureOwner extends TameworkActionBase {
    public ActionTameworkCaptureOwner(BuilderActionTameworkCaptureOwner builder, BuilderSupport support) {
        super(builder);
    }

    @Override
    public boolean canExecute(Ref<EntityStore> npcRef,
                              Role role,
                              InfoProvider infoProvider,
                              double dt,
                              Store<EntityStore> store) {
        Player player = resolveInteractionPlayer(role, infoProvider, store);
        if (player == null) {
            return false;
        }
        ItemStack itemStack = getActiveItem(player);
        ItemFeatureConfig config = resolveSpawnerConfig(itemStack);
        if (config == null || !isEmptySpawnerItem(config)) {
            return false;
        }
        UUID ownerUuid = resolveOwnerUuid(npcRef, store);
        UUID playerUuid = getPlayerUuid(player);
        return ownerUuid != null && ownerUuid.equals(playerUuid);
    }

    @Override
    public boolean execute(Ref<EntityStore> npcRef,
                           Role role,
                           InfoProvider infoProvider,
                           double dt,
                           Store<EntityStore> store) {
        Player player = resolveInteractionPlayer(role, infoProvider, store);
        if (player == null) {
            return false;
        }
        ItemStack itemStack = getActiveItem(player);
        ItemFeatureConfig config = resolveSpawnerConfig(itemStack);
        if (config == null || !isEmptySpawnerItem(config)) {
            return false;
        }
        UUID ownerUuid = resolveOwnerUuid(npcRef, store);
        UUID playerUuid = getPlayerUuid(player);
        if (ownerUuid == null || !ownerUuid.equals(playerUuid)) {
            return false;
        }
        Tamework instance = Tamework.getInstance();
        SpawnerFeatureHandler handler = instance != null ? instance.getSpawnerFeatureHandler() : null;
        if (handler == null) {
            return false;
        }
        return handler.captureFromNpcAction(player, npcRef, itemStack, config);
    }
}
