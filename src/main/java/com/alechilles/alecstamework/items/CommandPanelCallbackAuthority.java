package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import javax.annotation.Nullable;

/** Revalidates delayed command-panel callbacks against current tool authority. */
final class CommandPanelCallbackAuthority {
    private final CommandItemRegistry registry;
    private final CommandToolInventoryService tools;

    CommandPanelCallbackAuthority(
            CommandItemRegistry registry,
            CommandToolInventoryService tools
    ) {
        this.registry = registry;
        this.tools = tools;
    }

    long revision() {
        return registry == null ? -1L : registry.revision();
    }

    @Nullable
    GenericBinding bindGeneric(
            @Nullable ItemStack openedStack,
            @Nullable TwCommandItemConfig openedConfig
    ) {
        long openedRevision = revision();
        String openedItemId = openedStack == null
                ? null : openedStack.getItemId();
        if (!CommandGenericTargetAuthority.allowsCurrentGenericCallback(
                openedStack, openedItemId, openedConfig, openedRevision,
                registry)) {
            return null;
        }
        return new GenericBinding(
                openedItemId, openedConfig, openedRevision);
    }

    boolean allowsGeneric(
            Player player, String toolId, @Nullable GenericBinding binding
    ) {
        if (binding == null) {
            return false;
        }
        ItemStack stack = tools == null ? null
                : tools.findUniqueToolStack(player, toolId);
        return CommandGenericTargetAuthority.allowsCurrentGenericCallback(
                stack, binding.openedItemId(), binding.openedConfig(),
                binding.openedRevision(), registry);
    }

    boolean allowsGeneric(
            Player player, String toolId, TwCommandItemConfig openedConfig
    ) {
        return allowsGeneric(player, toolId, openedConfig, revision());
    }

    boolean allowsGeneric(
            Player player, String toolId, TwCommandItemConfig openedConfig,
            long openedRevision
    ) {
        return CommandGenericTargetAuthority.allowsCurrentGenericCallback(
                player, toolId, openedConfig, openedRevision, tools, registry);
    }

    boolean allowsBonded(
            Player player, String toolId, String openedItemId,
            TwCommandItemConfig openedConfig, long openedRevision
    ) {
        return CommandGenericTargetAuthority.allowsCurrentBondedCallback(
                player, toolId, openedItemId, openedConfig, openedRevision,
                tools, registry);
    }

    /** Immutable authority captured only after the opened config is proven. */
    record GenericBinding(
            String openedItemId,
            TwCommandItemConfig openedConfig,
            long openedRevision
    ) {
    }
}
