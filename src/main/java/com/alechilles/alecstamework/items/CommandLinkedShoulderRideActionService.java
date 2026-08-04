package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.config.assets.TwCompanionShoulderRideSettings;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;

/** Revalidates a linked-card shoulder-ride request before toggling its mount. */
final class CommandLinkedShoulderRideActionService {
    private final CommandLinkPolicyService links = new CommandLinkPolicyService();
    private final BondedCompanionShoulderRideActionService.MountOperator mounts =
            new BondedCompanionShoulderRideActionService.HytaleMountOperator();

    boolean toggle(UUID ownerUuid, Ref<EntityStore> playerRef,
                   Store<EntityStore> store, String itemId, UUID npcUuid) {
        Player player = BondedCompanionPanelActionRouter.resolvePlayerFromEvent(
                ownerUuid, playerRef, store);
        if (player == null || player.getWorld() == null || npcUuid == null) return false;
        Ref<EntityStore> npcRef = player.getWorld().getEntityRef(npcUuid);
        if (npcRef == null || !npcRef.isValid() || npcRef.getStore() != store
                || !links.isLinkedToTool(npcRef, ownerUuid, itemId, store)) return false;
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        TwCompanionShoulderRideSettings settings = roleId == null ? null
                : TwCompanionConfig.resolveEffectiveForRole(roleId).getShoulderRide();
        if (!mounts.isAttached(npcRef, playerRef, store)
                && (settings == null || !settings.isConfigured())) return false;
        return mounts.toggle(npcRef, playerRef, store, settings, ownerUuid);
    }
}
