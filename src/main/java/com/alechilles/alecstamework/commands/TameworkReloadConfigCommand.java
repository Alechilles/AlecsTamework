package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.overrides.TwConfigOverrideManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * Reloads Tamework item feature configs from disk.
 */
public final class TameworkReloadConfigCommand extends AbstractPlayerCommand {

    public TameworkReloadConfigCommand() {
        super("reloadconfig", "Reload Tamework item feature configs.");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null) {
            commandContext.sender().sendMessage(Message.raw("Tamework plugin not available."));
            return;
        }
        TwConfigOverrideManager.ReloadResult reloadResult = plugin.reloadConfigOverrides(world);
        int loaded = plugin.reloadItemFeatureConfigs();
        int totalSpawners = plugin.getItemFeatureRegistry() != null
                ? plugin.getItemFeatureRegistry().snapshot().size()
                : 0;
        int totalNaming = plugin.getNameItemRegistry() != null
                ? plugin.getNameItemRegistry().snapshot().size()
                : 0;
        commandContext.sender().sendMessage(Message.raw(
                "Reloaded Tamework configs. OverridePacks=" + reloadResult.getLoadedPacks()
                        + " OverrideDirs=" + reloadResult.getLoadedDirectories()
                        + " ItemLoaded=" + loaded
                        + " Spawners=" + totalSpawners
                        + " Naming=" + totalNaming
        ));
        if (reloadResult.hasErrors()) {
            commandContext.sender().sendMessage(Message.raw(
                    "Override reload reported " + reloadResult.getErrors().size() + " error(s). See server log."
            ));
        }
    }
}
