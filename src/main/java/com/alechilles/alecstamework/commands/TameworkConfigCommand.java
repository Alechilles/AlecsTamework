package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.ui.TameworkConfigEditorPage;
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
 * Opens the in-world Tamework config editor page.
 */
public final class TameworkConfigCommand extends AbstractPlayerCommand {

    public TameworkConfigCommand() {
        super("config", "Open the Tamework config editor.");
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
        if (plugin == null || plugin.getConfigOverrideManager() == null) {
            commandContext.sender().sendMessage(Message.raw("Tamework config editor is not available."));
            return;
        }
        if (!TameworkConfigPermission.hasAccess(commandContext.sender())) {
            commandContext.sender().sendMessage(Message.raw("You do not have permission to use /tw config."));
            return;
        }

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null || player.getPageManager() == null) {
            commandContext.sender().sendMessage(Message.raw("Unable to open the config editor right now."));
            return;
        }
        PlayerRef uiPlayerRef = player.getPlayerRef();
        if (uiPlayerRef == null || !uiPlayerRef.isValid()) {
            commandContext.sender().sendMessage(Message.raw("Unable to open the config editor right now."));
            return;
        }

        try {
            TameworkConfigEditorPage page = new TameworkConfigEditorPage(uiPlayerRef, plugin, world);
            player.getPageManager().openCustomPage(ref, store, page);
            plugin.getTelemetryEvents().recordUsage("config_editor_opened", "Opened via /tw config.");
        } catch (Throwable throwable) {
            plugin.getTelemetryEvents().recordError(
                    "ui_page_open_failed",
                    throwable,
                    "page=TameworkConfigEditorPage action=/tw config"
            );
            commandContext.sender().sendMessage(Message.raw("Unable to open the config editor right now."));
        }
    }
}
