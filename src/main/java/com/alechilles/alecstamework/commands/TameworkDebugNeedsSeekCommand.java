package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import javax.annotation.Nonnull;

/**
 * Toggles needs seek diagnostics logging on the server.
 */
public final class TameworkDebugNeedsSeekCommand extends AbstractTameworkServerCommand {

    public TameworkDebugNeedsSeekCommand() {
        super("seek", "server.tamework.commands.debugNeedsSeek.description");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void executeServer(@Nonnull CommandContext commandContext) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.debugNeedsSeek.tamework.plugin.not.available"));
            return;
        }
        String raw = getFirstArg(commandContext);
        Boolean explicit = parseBoolean(raw);
        if (explicit == null && !TameworkCommandInput.isToggleRequest(raw)) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.invalidToggle"));
            return;
        }
        boolean enabled = explicit != null
                ? plugin.setDebugNeedsSeekDiagnosticsEnabled(explicit)
                : plugin.toggleDebugNeedsSeekDiagnosticsEnabled();
        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.debugNeedsSeek.tamework.needs.seek.diagnostics.logging").param("0", String.valueOf((enabled ? "enabled" : "disabled"))));
    }

    private static String getFirstArg(CommandContext commandContext) {
        return TameworkCommandInput.firstArgument(commandContext.getInputString(), "seek");
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
