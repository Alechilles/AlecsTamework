package com.alechilles.alecstamework.commands;

import java.util.logging.Level;

import javax.annotation.Nonnull;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.assets.patches.AssetPatchService;
import com.alechilles.alecstamework.assets.patches.AssetPatchStatus;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Regenerates optional patches and reloads the generated builders.
 */
public final class TameworkPatchesReloadCommand extends AbstractPlayerCommand {
    public TameworkPatchesReloadCommand() {
        super("reload", "Reload optional Tamework patches.");
        requirePermission(TameworkConfigPermission.NODE);
        setPermissionGroups("OP", "Admin", "Operator");
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null || plugin.getAssetPatchService() == null) {
            commandContext.sender().sendMessage(Message.raw("Tamework patch service is not available."));
            return;
        }
        if (!TameworkConfigPermission.hasAccess(commandContext.sender())) {
            commandContext.sender().sendMessage(Message.raw("You do not have permission to use /tw patches."));
            return;
        }
        AssetPatchService service = plugin.getAssetPatchService();
        commandContext.sender().sendMessage(Message.raw("Reloading Tamework patches..."));
        try {
            AssetPatchStatus status = service.reload();
            commandContext.sender().sendMessage(Message.raw(status.summaryLine()));
            if (status.hasFailures()) {
                commandContext.sender().sendMessage(Message.raw("Asset patch reload reported failures. See server log."));
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().at(Level.WARNING).withCause(ex).log("Tamework template patch reload command failed.");
            commandContext.sender().sendMessage(Message.raw("Asset patch reload failed. See server log."));
        }
    }
}
