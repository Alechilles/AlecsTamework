package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.ownership.OwnerMessageUtil;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
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
 * Handles capture attempts by non-owners and denies if restricted.
 */
public final class ActionTameworkCaptureStranger extends TameworkActionBase {

    public ActionTameworkCaptureStranger(BuilderActionTameworkCaptureStranger builder, BuilderSupport support) {
        super(builder);
    }

    @Override
    public boolean canExecute(Ref<EntityStore> npcRef,
                              Role role,
                              InfoProvider infoProvider,
                              double dt,
                              Store<EntityStore> store) {
        if (!resolveCaptureRequiresOwner()) {
            return false;
        }
        // Require empty spawner and a different owner before denying capture.
        Player player = resolveInteractionPlayer(role, infoProvider, store);
        if (player == null) {
            return false;
        }
        ItemStack itemStack = getActiveItem(player);
        ItemFeatureConfig config = resolveSpawnerConfig(itemStack);
        if (config == null || !isEmptySpawnerItem(config)) {
            com.alechilles.alecstamework.Tamework instance = com.alechilles.alecstamework.Tamework.getInstance();
            if (instance != null) {
                instance.getLogger().at(java.util.logging.Level.FINE).log(
                        "Tamework action: stranger canExecute=false (no config) item="
                                + (itemStack != null ? itemStack.getItemId() : "<null>")
                );
            }
            return false;
        }
        UUID ownerUuid = resolveOwnerUuid(npcRef, store);
        UUID playerUuid = getPlayerUuid(player);
        com.alechilles.alecstamework.Tamework instance = com.alechilles.alecstamework.Tamework.getInstance();
        if (instance != null) {
            instance.getLogger().at(java.util.logging.Level.FINE).log(
                    "Tamework action: stranger canExecute item="
                            + (itemStack != null ? itemStack.getItemId() : "<null>")
                            + " owner=" + ownerUuid
                            + " player=" + playerUuid
            );
        }
        return ownerUuid != null && playerUuid != null && !ownerUuid.equals(playerUuid);
    }

    @Override
    public boolean execute(Ref<EntityStore> npcRef,
                           Role role,
                           InfoProvider infoProvider,
                           double dt,
                           Store<EntityStore> store) {
        if (!canExecute(npcRef, role, infoProvider, dt, store)) {
            return false;
        }
        com.alechilles.alecstamework.Tamework instance = com.alechilles.alecstamework.Tamework.getInstance();
        Player player = resolveInteractionPlayer(role, infoProvider, store);
        if (player != null) {
            UUID ownerUuid = resolveOwnerUuid(npcRef, store);
            String ownerName = resolveOwnerName(npcRef, store);
            String npcName = resolveNpcName(resolveNpcEntity(npcRef, store));
            OwnerMessageUtil.sendDenied(player, npcName, ownerName, ownerUuid, "capture");
        }
        if (instance != null) {
            instance.getLogger().at(java.util.logging.Level.FINE).log(
                    "Tamework action: stranger capture blocked"
            );
        }
        // Block capture by strangers. Returning true prevents later capture actions from firing.
        return true;
    }

    private boolean resolveCaptureRequiresOwner() {
        TwGlobalConfig global = TwGlobalConfig.resolveActive();
        TwGlobalConfig resolved = global != null ? global : TwGlobalConfig.defaultConfig();
        return TameworkRuntimeSettings.captureRequiresOwner(resolved.isOwnershipCaptureRequiresOwner());
    }
}
