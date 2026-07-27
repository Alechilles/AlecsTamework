package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nullable;

/**
 * Physical authority checks shared by ordinary command-item boundaries.
 *
 * <p>Bonded projections and Horn stacks are authoritative to their roster
 * path. Generic pages may neither present nor mutate them, including when a
 * callback was created before a reload changed the physical tool's config.</p>
 */
final class CommandGenericTargetAuthority {
    private CommandGenericTargetAuthority() {
    }

    static boolean allowsNearbyPresentation(
            @Nullable Ref<EntityStore> reference,
            @Nullable Store<EntityStore> store
    ) {
        return allowsGenericTargetMutation(reference, store);
    }

    /**
     * Returns whether a generic command action may act on this loaded target.
     * Missing marker evidence is intentionally denied: destructive generic
     * actions must never win an authority race against a bonded projection.
     */
    static boolean allowsGenericTargetMutation(
            @Nullable Ref<EntityStore> reference,
            @Nullable Store<EntityStore> store
    ) {
        if (reference == null || !reference.isValid() || store == null) {
            return false;
        }
        ComponentType<EntityStore, TameworkProjectionIdentityComponent> type =
                TameworkProjectionIdentityComponent.getComponentType();
        if (type == null) {
            return false;
        }
        try {
            TameworkProjectionIdentityComponent marker =
                    store.getComponent(reference, type);
            return marker == null || !marker.isBondedCompanion();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean allowsCurrentGenericCallback(
            @Nullable ItemStack physicalStack,
            @Nullable TwCommandItemConfig openTimeConfig,
            @Nullable TwCommandItemConfig currentPhysicalConfig
    ) {
        return physicalStack != null && !physicalStack.isEmpty()
                && CommandRosterStorageBoundary.allowsGenericRosterActions(
                        openTimeConfig)
                && CommandRosterStorageBoundary.allowsGenericRosterActions(
                        currentPhysicalConfig);
    }

    /** Resolves exactly one current stack before accepting a generic callback. */
    static boolean allowsCurrentGenericCallback(
            @Nullable Player player,
            @Nullable String toolId,
            @Nullable TwCommandItemConfig openTimeConfig,
            @Nullable CommandToolInventoryService toolInventoryService,
            @Nullable CommandItemRegistry registry
    ) {
        ItemStack stack = toolInventoryService == null ? null
                : toolInventoryService.findUniqueToolStack(player, toolId);
        TwCommandItemConfig current = registry == null || stack == null
                ? null : registry.get(stack.getItemId());
        return allowsCurrentGenericCallback(stack, openTimeConfig, current);
    }

    static boolean allowsGenericCullRepair(
            @Nullable ItemStack physicalStack,
            @Nullable TwCommandItemConfig currentPhysicalConfig
    ) {
        return physicalStack != null && !physicalStack.isEmpty()
                && CommandRosterStorageBoundary.allowsGenericRosterActions(
                        currentPhysicalConfig);
    }
}
