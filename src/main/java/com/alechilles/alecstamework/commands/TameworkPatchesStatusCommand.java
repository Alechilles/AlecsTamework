package com.alechilles.alecstamework.commands;

import java.util.List;

import javax.annotation.Nonnull;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.assets.patches.AssetPatchService;
import com.alechilles.alecstamework.assets.patches.AssetPatchStatus;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;

/**
 * Prints the last optional patch run.
 */
public final class TameworkPatchesStatusCommand extends AbstractTameworkServerCommand {
    private static final int MAX_ROWS = 8;

    public TameworkPatchesStatusCommand() {
        super("status", "Show optional Tamework patch status.");
        requirePermission(TameworkConfigPermission.NODE);
        setPermissionGroups("OP", "Admin", "Operator");
    }

    @Override
    protected void executeServer(@Nonnull CommandContext commandContext) {
        AssetPatchService service = resolveService(commandContext);
        if (service == null) {
            return;
        }
        AssetPatchStatus status = service.getLastStatus();
        commandContext.sender().sendMessage(Message.raw(status.summaryLine()));
        sendRows(commandContext, "Generated", status.getGeneratedTargets());
        sendRows(commandContext, "Removed generated", status.getRemovedGeneratedTargets());
        sendRows(commandContext, "Hot reloaded", status.getHotReloadedTargets());
        sendRows(commandContext, "Restart required", status.getRestartRequiredTargets());
        sendRows(commandContext, "Failed", status.getFailed());
        sendRows(commandContext, "Skipped", status.getSkipped());
    }

    private static AssetPatchService resolveService(@Nonnull CommandContext commandContext) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null || plugin.getAssetPatchService() == null) {
            commandContext.sender().sendMessage(Message.raw("Tamework patch service is not available."));
            return null;
        }
        if (!TameworkConfigPermission.hasAccess(commandContext.sender())) {
            commandContext.sender().sendMessage(Message.raw("You do not have permission to use /tw patches."));
            return null;
        }
        return plugin.getAssetPatchService();
    }

    private static void sendRows(@Nonnull CommandContext commandContext,
                                 @Nonnull String label,
                                 @Nonnull List<String> rows) {
        if (rows.isEmpty()) {
            return;
        }
        int limit = Math.min(rows.size(), MAX_ROWS);
        for (int i = 0; i < limit; i++) {
            commandContext.sender().sendMessage(Message.raw(label + ": " + rows.get(i)));
        }
        if (rows.size() > limit) {
            commandContext.sender().sendMessage(Message.raw(label + ": +" + (rows.size() - limit) + " more."));
        }
    }
}
