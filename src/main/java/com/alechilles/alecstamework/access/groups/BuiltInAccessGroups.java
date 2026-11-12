package com.alechilles.alecstamework.access.groups;

import com.alechilles.alecstamework.access.SharedAccessService;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Tamework's own sharing groups for popular party and claim mods. Each one is offered only when
 * its mod is installed, and like any group it grants nothing until an owner turns it on. They
 * end with the sharing service, which drops every provider when it closes.
 */
public final class BuiltInAccessGroups {
    private static final System.Logger LOGGER = System.getLogger(BuiltInAccessGroups.class.getName());

    private BuiltInAccessGroups() {
    }

    /** Registers the built-in group of every supported mod that is installed on this server. */
    public static void registerInstalled(@Nonnull SharedAccessService service) {
        PluginManager manager = PluginManager.get();
        for (ModGroupProvider group : List.of(
                new SimpleClaimsPartyGroup(),
                new PartyProPartyGroup(),
                new QuestLinesClaimMembersGroup(),
                new OrbisGuardRegionMembersGroup())) {
            if (!installed(manager, group.pluginId())) {
                continue;
            }
            try {
                service.registerGroup(group);
            } catch (IllegalStateException taken) {
                // The mod, or another one, registered this id itself; its own provider wins.
                LOGGER.log(System.Logger.Level.INFO, "Skipped Tamework's built-in sharing group " + group.id()
                        + ": another provider already registered it.");
            }
        }
    }

    private static boolean installed(@Nullable PluginManager manager, @Nonnull PluginIdentifier pluginId) {
        if (manager == null) {
            return false;
        }
        // Mods load in dependency order, so a supported mod may not be set up yet: its manifest is enough.
        return manager.getPlugin(pluginId) != null || manager.getAvailablePlugins().containsKey(pluginId);
    }
}
