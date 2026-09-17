package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import javax.annotation.Nonnull;

/**
 * Toggles coop identity/remap debug logging on the server.
 */
public final class TameworkDebugCoopCommand extends AbstractTameworkServerCommand {

    public TameworkDebugCoopCommand() {
        super("coop", "server.tamework.commands.debugCoop.description");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void executeServer(@Nonnull CommandContext commandContext) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.debugCoop.tamework.plugin.not.available"));
            return;
        }
        String raw = getFirstArg(commandContext);
        Boolean explicit = parseBoolean(raw);
        if (explicit == null && !TameworkCommandInput.isToggleRequest(raw)) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.invalidToggle"));
            return;
        }
        boolean enabled = explicit != null
                ? plugin.setDebugCoopEnabled(explicit)
                : plugin.toggleDebugCoopEnabled();
        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.debugCoop.tamework.coop.debug.logging").param("0", String.valueOf((enabled ? "enabled" : "disabled"))));
    }

    private static String getFirstArg(CommandContext commandContext) {
        return TameworkCommandInput.firstArgument(commandContext.getInputString(), "coop");
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
