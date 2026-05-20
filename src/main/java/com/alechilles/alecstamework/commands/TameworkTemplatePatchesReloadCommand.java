package com.alechilles.alecstamework.commands;

import java.util.logging.Level;

import javax.annotation.Nonnull;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.assets.patches.NpcTemplatePatchService;
import com.alechilles.alecstamework.assets.patches.NpcTemplatePatchStatus;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Regenerates optional NPC template patches and reloads the generated builders.
 */
public final class TameworkTemplatePatchesReloadCommand extends AbstractPlayerCommand {
    public TameworkTemplatePatchesReloadCommand() {
        super("reload", "Reload optional NPC template patches.");
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
        if (plugin == null || plugin.getNpcTemplatePatchService() == null) {
            commandContext.sender().sendMessage(Message.raw("Tamework template patch service is not available."));
            return;
        }
        if (!TameworkConfigPermission.hasAccess(commandContext.sender())) {
            commandContext.sender().sendMessage(Message.raw("You do not have permission to use /tw templatepatches."));
            return;
        }
        NpcTemplatePatchService service = plugin.getNpcTemplatePatchService();
        commandContext.sender().sendMessage(Message.raw("Reloading Tamework NPC template patches..."));
        try {
            NpcTemplatePatchStatus status = service.reload();
            commandContext.sender().sendMessage(Message.raw(status.summaryLine()));
            if (status.hasFailures()) {
                commandContext.sender().sendMessage(Message.raw("Template patch reload reported failures. See server log."));
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().at(Level.WARNING).withCause(ex).log("Tamework template patch reload command failed.");
            commandContext.sender().sendMessage(Message.raw("Template patch reload failed. See server log."));
        }
    }
}
