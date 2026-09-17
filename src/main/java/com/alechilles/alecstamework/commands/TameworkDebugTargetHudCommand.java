package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.items.CommandTargetHudDebugLog;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import javax.annotation.Nonnull;

/** Toggles command target HUD diagnostics logging on the server. */
public final class TameworkDebugTargetHudCommand extends AbstractTameworkServerCommand {
    public TameworkDebugTargetHudCommand() {
        super("target-hud", "server.tamework.commands.debugTargetHud.description");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void executeServer(@Nonnull CommandContext commandContext) {
        String raw = getFirstArg(commandContext.getInputString());
        Boolean explicit = parseBoolean(raw);
        if (explicit == null && !TameworkCommandInput.isToggleRequest(raw)) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.invalidToggle"));
            return;
        }
        boolean enabled = explicit != null
                ? CommandTargetHudDebugLog.setEnabled(explicit)
                : CommandTargetHudDebugLog.toggle();
        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.debugTargetHud.tamework.command.target.hud.diagnostics.logging").param("0", String.valueOf((enabled ? "enabled" : "disabled"))));
    }

    static String getFirstArg(String input) {
        return TameworkCommandInput.firstArgument(input, "target-hud");
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
