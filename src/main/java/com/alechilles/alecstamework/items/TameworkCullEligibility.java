package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.access.AccessAction;
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

    boolean allows(@Nullable UUID playerUuid,
                   boolean requireOwner,
                   boolean requireTamed,
                   @Nullable Ref<EntityStore> target,
                   @Nullable ComponentAccessor<EntityStore> components) {
        return playerUuid != null
                && linkPolicyService != null
                && CommandGenericTargetAuthority.allowsGenericTargetMutation(
                        target, components)
                && linkPolicyService.passesOwnerAndTamed(
                        requireOwner, requireTamed, target, playerUuid,
                        components)
                // Commanding a shared companion does not include culling it.
                && (!requireOwner || CompanionAccess.allows(target, components, playerUuid, AccessAction.CULL));
    }
}
