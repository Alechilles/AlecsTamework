package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Resolves the NPC under a command-item user's reticle and whether its link action can apply.
 */
final class CommandTargetInspector {
    static final float TARGET_DISTANCE = 15.0f;

    private final CommandLinkPolicyService linkPolicyService = new CommandLinkPolicyService();

    @Nullable
    Target resolveTarget(@Nullable Ref<EntityStore> playerRef,
                         @Nullable Store<EntityStore> store) {
        if (playerRef == null || !playerRef.isValid() || store == null) {
            return null;
        }
        Ref<EntityStore> targetRef = TargetUtil.getTargetEntity(playerRef, TARGET_DISTANCE, store);
        if (targetRef == null || !targetRef.isValid() || targetRef.equals(playerRef)) {
            return null;
        }
        NPCEntity npc = store.getComponent(targetRef, NPCEntity.getComponentType());
        return npc == null || npc.getUuid() == null ? null : new Target(targetRef, npc);
    }

    boolean isLinkable(@Nullable Player player,
                       @Nullable Ref<EntityStore> playerRef,
                       @Nullable TwCommandItemConfig config,
                       @Nullable Store<EntityStore> store) {
        if (player == null || config == null || store == null
                || config.usesBondedCompanionRoster()
                || !config.isLinkEnabled()
                || !config.isLinkUseTogglesMembership()) {
            return false;
        }
        Target target = resolveTarget(playerRef, store);
        if (target == null || !CommandGenericTargetAuthority.allowsGenericTargetMutation(
                target.reference(), store)) {
            return false;
        }
        boolean tamed = TamedStateResolver.isTamed(target.reference(), store);
        if (config.isRequireTamed() && !tamed) {
            return false;
        }
        if (!linkPolicyService.isRoleAllowed(
                linkPolicyService.resolveRoleId(target.npc()), config, tamed)) {
            return false;
        }
        if (!CommandLinkMutationService.resolveLinkingRequireOwner(
                com.alechilles.alecstamework.config.assets.TwGlobalConfig.resolveActive())) {
            return true;
        }
        UUID playerUuid = player.getUuid();
        UUID ownerUuid = linkPolicyService.resolveOwnerId(target.reference(), store);
        return playerUuid != null && (ownerUuid == null || ownerUuid.equals(playerUuid));
    }

    record Target(Ref<EntityStore> reference, NPCEntity npc) {
    }
}
