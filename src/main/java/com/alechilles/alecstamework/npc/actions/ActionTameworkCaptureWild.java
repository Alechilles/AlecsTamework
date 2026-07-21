package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.items.SpawnerFeatureHandler;
import com.alechilles.alecstamework.items.CaptureAttemptHandle;
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
 * Captures untamed or wild NPCs when allowed by config.
 */
public final class ActionTameworkCaptureWild extends TameworkActionBase {

    public ActionTameworkCaptureWild(BuilderActionTameworkCaptureWild builder, BuilderSupport support) {
        super(builder);
    }

    @Override
    public boolean canExecute(Ref<EntityStore> npcRef,
                              Role role,
                              InfoProvider infoProvider,
                              double dt,
                              Store<EntityStore> store) {
        // Only allow capture with an empty spawner when no owner is set.
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
        return ownerUuid == null;
    }

    @Override
    public boolean execute(Ref<EntityStore> npcRef,
                           Role role,
                           InfoProvider infoProvider,
                           double dt,
                           Store<EntityStore> store) {
        // Re-check ownership and delegate to the spawner handler.
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
        if (ownerUuid != null) {
            return false;
        }
        Tamework instance = Tamework.getInstance();
        SpawnerFeatureHandler handler = instance != null ? instance.getSpawnerFeatureHandler() : null;
        if (handler == null) {
            return false;
        }
        CaptureAttemptHandle attempt = handler.prepareCaptureAttempt(player, itemStack, null);
        return attempt != null
                && handler.captureFromNpcAction(player, npcRef, itemStack, config, attempt);
    }
}
