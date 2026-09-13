package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import javax.annotation.Nonnull;

/**
 * Toggles needs damage diagnostics logging on the server.
 */
public final class TameworkDebugNeedsDamageCommand extends AbstractTameworkServerCommand {

    public TameworkDebugNeedsDamageCommand() {
        super("damage", "server.tamework.commands.debugNeedsDamage.description");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void executeServer(@Nonnull CommandContext commandContext) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.debugNeedsDamage.tamework.plugin.not.available"));
            return;
        }
        String raw = getFirstArg(commandContext);
        Boolean explicit = parseBoolean(raw);
        boolean enabled = explicit != null
                ? plugin.setDebugNeedsDamageDiagnosticsEnabled(explicit)
                : plugin.toggleDebugNeedsDamageDiagnosticsEnabled();
        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.debugNeedsDamage.tamework.needs.damage.diagnostics.logging").param("0", String.valueOf((enabled ? "enabled" : "disabled"))));
    }

    private static String getFirstArg(CommandContext commandContext) {
        return TameworkCommandInput.firstArgument(commandContext.getInputString(), "damage");
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
