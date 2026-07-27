package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.hypixel.hytale.server.core.entity.entities.Player;

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
}
