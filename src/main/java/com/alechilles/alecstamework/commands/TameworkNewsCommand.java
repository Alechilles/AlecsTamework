package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.alechilles.alecstamework.ui.TameworkSettingsAnnouncementService;
import com.alechilles.alecstamework.ui.TameworkSettingsPageService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * Opens the current Tamework settings announcement on demand.
 */
public final class TameworkNewsCommand extends AbstractPlayerCommand {

    public TameworkNewsCommand() {
        super("news", "Open the current Tamework settings announcement.");
        requirePermission(TameworkConfigPermission.NODE);
        setPermissionGroups("OP", "Admin", "Operator");
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
        Tamework plugin = Tamework.getInstance();
        if (plugin == null) {
            commandContext.sender().sendMessage(Message.raw("Tamework news is not available."));
            return;
        }
        if (!TameworkConfigPermission.hasAccess(commandContext.sender())) {
            commandContext.sender().sendMessage(Message.raw("You do not have permission to use /tw news."));
            return;
        }

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            commandContext.sender().sendMessage(Message.raw("Unable to open Tamework news right now."));
            return;
        }
        TameworkSettingsAnnouncementService service = plugin.getSettingsAnnouncementService();
        if (service == null) {
            commandContext.sender().sendMessage(Message.raw("Tamework news is not available."));
            return;
        }

        String error = service.openAnnouncementNow(ref, store, player);
        if (error != null) {
            plugin.getTelemetryEvents().recordError(
                    "news_command_open_failed",
                    null,
                    TameworkTelemetryEvents.commandContext("/tw news", "news", "news")
                            .operation("open")
                            .detail(error)
                            .detail("source", "command")
                            .build()
            );
            commandContext.sender().sendMessage(Message.raw(error));
            return;
        }
        plugin.getTelemetryEvents().recordUsage(
                "news_command_opened",
                TameworkTelemetryEvents.commandContext("/tw news", "news", "news")
                        .operation("open")
                        .detail("Opened via /tw news.")
                        .detail("source", "command")
                        .build()
        );
    }
}
