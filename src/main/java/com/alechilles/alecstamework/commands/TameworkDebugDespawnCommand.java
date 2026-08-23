package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import javax.annotation.Nonnull;

/**
 * Toggles companion despawn diagnostics logging on the server.
 */
public final class TameworkDebugDespawnCommand extends AbstractTameworkServerCommand {

    public TameworkDebugDespawnCommand() {
        super("despawn", "Toggle Tamework companion despawn diagnostics logging (optional role filter).");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void executeServer(@Nonnull CommandContext commandContext) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null) {
            commandContext.sender().sendMessage(Message.raw("Tamework plugin not available."));
            return;
        }
        String[] args = getArgs(commandContext);
        String firstArg = args.length > 0 ? args[0] : null;
        String secondArg = args.length > 1 ? args[1] : null;
        Boolean explicit = parseBoolean(firstArg);
        boolean enabled;
        if (explicit != null) {
            enabled = plugin.setDebugDespawnEnabled(explicit);
            if (secondArg != null) {
                applyRoleFilter(plugin, secondArg);
            }
        } else if (firstArg == null) {
            enabled = plugin.toggleDebugDespawnEnabled();
        } else if (isClearRoleKeyword(firstArg)) {
            plugin.clearDebugDespawnRoleFilter();
            enabled = plugin.isDebugDespawnEnabled();
        } else {
            plugin.setDebugDespawnRoleFilter(firstArg);
            enabled = plugin.setDebugDespawnEnabled(true);
        }
        String roleFilter = plugin.getDebugDespawnRoleFilter();
        String roleSummary = roleFilter == null || roleFilter.isBlank() ? "all tamed roles" : roleFilter;
        commandContext.sender().sendMessage(Message.raw(
                "Tamework despawn diagnostics logging: " + (enabled ? "enabled" : "disabled")
                        + " (role filter: " + roleSummary + ")"
        ));
    }

    private static String[] getArgs(CommandContext commandContext) {
        String input = commandContext.getInputString();
        if (input == null) {
            return new String[0];
        }
        String[] tokens = input.trim().split("\\s+");
        if (tokens.length <= 2) {
            return new String[0];
        }
        String[] args = new String[tokens.length - 2];
        System.arraycopy(tokens, 2, args, 0, args.length);
        return args;
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

    private static void applyRoleFilter(Tamework plugin, String arg) {
        if (isClearRoleKeyword(arg)) {
            plugin.clearDebugDespawnRoleFilter();
        } else {
            plugin.setDebugDespawnRoleFilter(arg);
        }
    }

    private static boolean isClearRoleKeyword(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return "all".equals(normalized)
                || "*".equals(normalized)
                || "none".equals(normalized)
                || "clear".equals(normalized);
    }
}
