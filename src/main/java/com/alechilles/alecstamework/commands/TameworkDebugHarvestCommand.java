package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import javax.annotation.Nonnull;

/**
 * Toggles optimized harvest cooldown and container diagnostics logging on the server.
 */
public final class TameworkDebugHarvestCommand extends AbstractTameworkServerCommand {

    public TameworkDebugHarvestCommand() {
        super("harvest", "server.tamework.commands.debugHarvest.description");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void executeServer(@Nonnull CommandContext commandContext) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.debugHarvest.tamework.plugin.not.available"));
            return;
        }
        String raw = getFirstArg(commandContext);
        Boolean explicit = parseBoolean(raw);
        if (explicit == null && !TameworkCommandInput.isToggleRequest(raw)) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.invalidToggle"));
            return;
        }
        boolean enabled = explicit != null
                ? plugin.setDebugHarvestEnabled(explicit)
                : plugin.toggleDebugHarvestEnabled();
        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.debugHarvest.tamework.harvest.diagnostics.logging").param("0", String.valueOf((enabled ? "enabled" : "disabled"))));
    }

    private static String getFirstArg(CommandContext commandContext) {
        return TameworkCommandInput.firstArgument(commandContext.getInputString(), "harvest");
    }

    private static Boolean parseBoolean(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toLowerCase();
        if ("on".equals(value) || "true".equals(value) || "1".equals(value)) {
            return true;
        }
        if ("off".equals(value) || "false".equals(value) || "0".equals(value)) {
            return false;
        }
        return null;
    }
}
