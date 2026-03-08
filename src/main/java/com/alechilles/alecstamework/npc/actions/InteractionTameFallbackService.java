package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionEntry;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.TameInteraction;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;

/**
 * Applies fallback tame feedback when a player interacts without valid tame food.
 *
 * <p>This mirrors vanilla's behavior where interacting with the wrong item shows a "wanted food"
 * thought bubble rather than silently doing nothing.
 */
final class InteractionTameFallbackService {
    private static final String ATTRACTIVE_ITEM_PARTICLE_PARAM = "AttractiveItemSetParticles";
    private static final double DEFAULT_Y_OFFSET = 1.0;

    private final ActionTameworkInteract owner;

    InteractionTameFallbackService(ActionTameworkInteract owner) {
        this.owner = owner;
    }

    boolean applyNoMatchTameFeedback(TwInteractionConfig config,
                                     Ref<EntityStore> npcRef,
                                     Role role,
                                     Store<EntityStore> store,
                                     Player player,
                                     InteractionContextSnapshot ctx) {
        if (config == null || npcRef == null || store == null || player == null) {
            return false;
        }
        if (owner.isTamed(npcRef, store)) {
            return false;
        }
        if (!hasMissingTameFoodCandidate(config, role, ctx)) {
            return false;
        }
        String particleSystem = owner.getRoleStringParam(role, ctx, ATTRACTIVE_ITEM_PARTICLE_PARAM);
        if (particleSystem == null || particleSystem.isBlank()) {
            return false;
        }
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        if (transform == null) {
            return false;
        }
        Vector3d position = new Vector3d(transform.getPosition());
        position.y += DEFAULT_Y_OFFSET;
        ParticleUtil.spawnParticleEffect(particleSystem, position, store);
        return true;
    }

    private boolean hasMissingTameFoodCandidate(TwInteractionConfig config,
                                                Role role,
                                                InteractionContextSnapshot ctx) {
        for (InteractionEntry entry : config.getInteractions()) {
            if (!(entry instanceof TameInteraction tame) || !entry.isEnabled()) {
                continue;
            }
            String[] requiredItems = resolveRequiredTameItems(tame, role, ctx);
            if (!hasItems(requiredItems)) {
                continue;
            }
            if (!owner.isHeldItemInList(requiredItems, ctx)) {
                return true;
            }
        }
        return false;
    }

    private String[] resolveRequiredTameItems(TameInteraction interaction,
                                              Role role,
                                              InteractionContextSnapshot ctx) {
        String[] paramItems = owner.resolveItemsParam(role, ctx, interaction.getItemsParam());
        if (hasItems(paramItems)) {
            return paramItems;
        }
        String[] explicitItems = interaction.getItemsInHand();
        if (hasItems(explicitItems)) {
            return explicitItems;
        }
        boolean useLovedItems = interaction.getUseLovedItems() == null || interaction.getUseLovedItems();
        if (useLovedItems) {
            return owner.resolveLovedItems(role, ctx);
        }
        return new String[0];
    }

    private boolean hasItems(String[] items) {
        if (items == null || items.length == 0) {
            return false;
        }
        for (String item : items) {
            if (item != null && !item.isBlank()) {
                return true;
            }
        }
        return false;
    }
}
