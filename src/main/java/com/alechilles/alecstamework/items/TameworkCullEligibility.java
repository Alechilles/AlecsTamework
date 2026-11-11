package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.access.AccessAction;
import com.alechilles.alecstamework.api.access.AccessLevel;
import com.alechilles.alecstamework.access.CompanionAccess;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nullable;

/** Validates whether a player may cull one live generic companion target. */
final class TameworkCullEligibility {
    private final CommandLinkPolicyService linkPolicyService;

    TameworkCullEligibility(CommandLinkPolicyService linkPolicyService) {
        this.linkPolicyService = linkPolicyService;
    }

    /**
     * The command menu, companion card and item interaction culls all ask here. A player the
     * owner shares with needs {@link AccessAction#CULL} even when {@code requireOwner} is off:
     * commanding a shared companion does not include culling it. Players with no access keep
     * the owner and tamed rules alone.
     */
    boolean allows(@Nullable UUID playerUuid,
                   boolean requireOwner,
                   boolean requireTamed,
                   @Nullable Ref<EntityStore> target,
                   @Nullable ComponentAccessor<EntityStore> components) {
        if (playerUuid == null
                || linkPolicyService == null
                || !CommandGenericTargetAuthority.allowsGenericTargetMutation(target, components)
                || !linkPolicyService.passesOwnerAndTamed(
                        requireOwner, requireTamed, target, playerUuid, components)) {
            return false;
        }
        AccessLevel level = CompanionAccess.level(target, components, playerUuid);
        return level == AccessLevel.NONE || AccessAction.CULL.allowedAt(level);
    }
}
