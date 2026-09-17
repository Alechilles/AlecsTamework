package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import javax.annotation.Nonnull;

/**
 * Toggles breeding debug logging on the server.
 */
public final class TameworkDebugBreedingCommand extends AbstractTameworkServerCommand {

    public TameworkDebugBreedingCommand() {
        super("breeding", "server.tamework.commands.debugBreeding.description");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void executeServer(@Nonnull CommandContext commandContext) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.debugBreeding.tamework.plugin.not.available"));
            return;
        }
        String raw = getFirstArg(commandContext);
        Boolean explicit = parseBoolean(raw);
        if (explicit == null && !TameworkCommandInput.isToggleRequest(raw)) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.invalidToggle"));
            return;
        }
        boolean enabled = explicit != null
                ? plugin.setDebugBreedingEnabled(explicit)
                : plugin.toggleDebugBreedingEnabled();
        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.debugBreeding.tamework.breeding.debug.logging").param("0", String.valueOf((enabled ? "enabled" : "disabled"))));
    }

    private static String getFirstArg(CommandContext commandContext) {
        return TameworkCommandInput.firstArgument(commandContext.getInputString(), "breeding");
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
