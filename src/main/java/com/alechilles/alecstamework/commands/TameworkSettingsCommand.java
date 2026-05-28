package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.ui.TameworkSettingsPageService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * Opens the curated in-world Tamework settings page.
 */
public final class TameworkSettingsCommand extends AbstractPlayerCommand {

    public TameworkSettingsCommand() {
        super("settings", "Open the Tamework settings page.");
        requirePermission(TameworkConfigPermission.NODE);
        setPermissionGroups(TameworkConfigPermission.adminPermissionGroups());
        setAllowsExtraArguments(true);
    }

    @Override
    public boolean hasPermission(CommandSender sender) {
        return TameworkSettingsPageService.hasAccess(sender);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        if (!TameworkSettingsPageService.hasAccess(playerRef, commandContext.sender())) {
            commandContext.sender().sendMessage(Message.raw("You do not have permission to use /tw settings."));
            return;
        }
        String error = TameworkSettingsPageService.openSettingsPage(ref, store, world, "command", "/tw settings");
        if (error != null) {
            commandContext.sender().sendMessage(Message.raw(error));
        }
    }
}
