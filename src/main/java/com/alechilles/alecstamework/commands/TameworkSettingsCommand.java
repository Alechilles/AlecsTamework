package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.ui.TameworkSettingsPage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
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
        setPermissionGroups("OP", "Admin", "Operator");
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
            commandContext.sender().sendMessage(Message.raw("Tamework settings are not available."));
            return;
        }
        if (!TameworkConfigPermission.hasAccess(commandContext.sender())) {
            commandContext.sender().sendMessage(Message.raw("You do not have permission to use /tw settings."));
            return;
        }

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null || player.getPageManager() == null) {
            commandContext.sender().sendMessage(Message.raw("Unable to open settings right now."));
            return;
        }
        PlayerRef uiPlayerRef = player.getPlayerRef();
        if (uiPlayerRef == null || !uiPlayerRef.isValid()) {
            commandContext.sender().sendMessage(Message.raw("Unable to open settings right now."));
            return;
        }

        TameworkSettingsPage page = new TameworkSettingsPage(uiPlayerRef, plugin, world);
        player.getPageManager().openCustomPage(ref, store, page);
    }
}
