package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import javax.annotation.Nonnull;

/**
 * Toggles needs consume diagnostics logging on the server.
 */
public final class TameworkDebugNeedsConsumeCommand extends AbstractTameworkServerCommand {

    public TameworkDebugNeedsConsumeCommand() {
        super("consume", "server.tamework.commands.debugNeedsConsume.description");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void executeServer(@Nonnull CommandContext commandContext) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.debugNeedsConsume.tamework.plugin.not.available"));
            return;
        }
        String raw = getFirstArg(commandContext);
        Boolean explicit = parseBoolean(raw);
        boolean enabled = explicit != null
                ? plugin.setDebugNeedsConsumeDiagnosticsEnabled(explicit)
                : plugin.toggleDebugNeedsConsumeDiagnosticsEnabled();
        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.debugNeedsConsume.tamework.needs.consume.diagnostics.logging").param("0", String.valueOf((enabled ? "enabled" : "disabled"))));
    }

    private static String getFirstArg(CommandContext commandContext) {
        return TameworkCommandInput.firstArgument(commandContext.getInputString(), "consume");
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
