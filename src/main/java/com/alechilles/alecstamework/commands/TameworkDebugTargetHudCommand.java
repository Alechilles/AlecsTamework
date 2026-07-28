package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.items.CommandTargetHudDebugLog;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import javax.annotation.Nonnull;

/** Toggles command target HUD diagnostics logging on the server. */
public final class TameworkDebugTargetHudCommand extends AbstractTameworkServerCommand {
    public TameworkDebugTargetHudCommand() {
        super("debugtargethud", "Toggle Tamework command target HUD diagnostics logging.");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void executeServer(@Nonnull CommandContext commandContext) {
        String raw = getFirstArg(commandContext);
        Boolean explicit = parseBoolean(raw);
        boolean enabled = explicit != null
                ? CommandTargetHudDebugLog.setEnabled(explicit)
                : CommandTargetHudDebugLog.toggle();
        commandContext.sender().sendMessage(Message.raw(
                "Tamework command target HUD diagnostics logging: " + (enabled ? "enabled" : "disabled")
        ));
    }

    private static String getFirstArg(CommandContext commandContext) {
        String input = commandContext.getInputString();
        if (input == null) {
            return null;
        }
        String[] tokens = input.trim().split("\\s+");
        if (tokens.length < 3) {
            return null;
        }
        return tokens[2];
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
