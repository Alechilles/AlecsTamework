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
import java.util.Objects;

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
            return marker == null || isRecognizedGenericMarker(marker);
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

    /**
     * Rechecks a callback against the registry generation captured when its
     * page opened. The revision check before and after lookup closes a reload
     * race without relying on config object identity or config-id overrides.
     */
    static boolean allowsCurrentGenericCallback(
            @Nullable ItemStack physicalStack,
            @Nullable TwCommandItemConfig openTimeConfig,
            long openedRegistryRevision,
            @Nullable CommandItemRegistry registry
    ) {
        if (registry == null || registry.revision() != openedRegistryRevision
                || physicalStack == null || physicalStack.isEmpty()) {
            return false;
        }
        TwCommandItemConfig current = registry.get(physicalStack.getItemId());
        return registry.revision() == openedRegistryRevision
                && allowsCurrentGenericCallback(
                        physicalStack, openTimeConfig, current);
    }

    /**
     * Revalidates a bonded page against one current tool and one unchanged
     * registry generation. Stable config IDs remain valid overrides; config
     * object identity is deliberately irrelevant across resolution paths.
     */
    static boolean allowsCurrentBondedCallback(
            @Nullable Player player,
            @Nullable String toolId,
            @Nullable String openedPhysicalItemId,
            @Nullable TwCommandItemConfig openTimeConfig,
            long openedRegistryRevision,
            @Nullable CommandToolInventoryService toolInventoryService,
            @Nullable CommandItemRegistry registry
    ) {
        ItemStack stack = toolInventoryService == null ? null
                : toolInventoryService.findUniqueToolStack(player, toolId);
        return allowsCurrentBondedCallback(
                stack, openedPhysicalItemId, openTimeConfig,
                openedRegistryRevision, registry);
    }

    static boolean allowsCurrentBondedCallback(
            @Nullable ItemStack physicalStack,
            @Nullable String openedPhysicalItemId,
            @Nullable TwCommandItemConfig openTimeConfig,
            long openedRegistryRevision,
            @Nullable CommandItemRegistry registry
    ) {
        if (registry == null || registry.revision() != openedRegistryRevision
                || physicalStack == null || physicalStack.isEmpty()
                || !Objects.equals(openedPhysicalItemId,
                        physicalStack.getItemId())
                || openTimeConfig == null
                || !openTimeConfig.usesBondedCompanionRoster()) {
            return false;
        }
        TwCommandItemConfig current = resolveCurrentConfig(
                physicalStack, openTimeConfig, registry);
        return registry.revision() == openedRegistryRevision
                && allowsCurrentBondedCallback(
                        physicalStack, openedPhysicalItemId,
                        openTimeConfig, current);
    }

    static boolean allowsCurrentBondedCallback(
            @Nullable ItemStack physicalStack,
            @Nullable String openedPhysicalItemId,
            @Nullable TwCommandItemConfig openTimeConfig,
            @Nullable TwCommandItemConfig currentPhysicalConfig
    ) {
        return physicalStack != null && !physicalStack.isEmpty()
                && Objects.equals(openedPhysicalItemId,
                        physicalStack.getItemId())
                && openTimeConfig != null
                && openTimeConfig.usesBondedCompanionRoster()
                && currentPhysicalConfig != null
                && currentPhysicalConfig.isEnabled()
                && currentPhysicalConfig.usesBondedCompanionRoster()
                && Objects.equals(openTimeConfig.getBondedRosterId(),
                        currentPhysicalConfig.getBondedRosterId());
    }

    /** Resolves exactly one current stack before accepting a generic callback. */
    static boolean allowsCurrentGenericCallback(
            @Nullable Player player,
            @Nullable String toolId,
            @Nullable TwCommandItemConfig openTimeConfig,
            long openedRegistryRevision,
            @Nullable CommandToolInventoryService toolInventoryService,
            @Nullable CommandItemRegistry registry
    ) {
        ItemStack stack = toolInventoryService == null ? null
                : toolInventoryService.findUniqueToolStack(player, toolId);
        return allowsCurrentGenericCallback(
                stack, openTimeConfig, openedRegistryRevision, registry);
    }

    static boolean allowsGenericCullRepair(
            @Nullable ItemStack physicalStack,
            @Nullable TwCommandItemConfig currentPhysicalConfig
    ) {
        return physicalStack != null && !physicalStack.isEmpty()
                && CommandRosterStorageBoundary.allowsGenericRosterActions(
                        currentPhysicalConfig);
    }

    private static boolean isRecognizedGenericMarker(
            TameworkProjectionIdentityComponent marker
    ) {
        return switch (marker.getProjectionKind()) {
            case TameworkProjectionIdentityComponent.KIND_RECOVERY,
                    TameworkProjectionIdentityComponent.KIND_CAPTURE_RELEASE,
                    TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE,
                    TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_CAPTURE_SOURCE,
                    TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_IMPORT_ADOPTION,
                    TameworkProjectionIdentityComponent.KIND_BREEDING_CHILD,
                    TameworkProjectionIdentityComponent.KIND_COMMAND_ROSTER,
                    TameworkProjectionIdentityComponent.KIND_PROVISIONING_ACTIVATION -> true;
            default -> false;
        };
    }

    private static TwCommandItemConfig resolveCurrentConfig(
            ItemStack physicalStack,
            TwCommandItemConfig openTimeConfig,
            CommandItemRegistry registry
    ) {
        String configId = openTimeConfig == null ? null : openTimeConfig.getId();
        TwCommandItemConfig override = configId == null || configId.isBlank()
                ? null : registry.getByConfigId(configId);
        return override != null ? override : registry.get(physicalStack.getItemId());
    }
}
