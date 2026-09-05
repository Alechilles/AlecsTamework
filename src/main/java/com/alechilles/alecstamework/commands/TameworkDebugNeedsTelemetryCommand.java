package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import javax.annotation.Nonnull;

/**
 * Toggles needs failure telemetry context events on the server.
 */
public final class TameworkDebugNeedsTelemetryCommand extends AbstractTameworkServerCommand {

    public TameworkDebugNeedsTelemetryCommand() {
        super("needs", "Toggle Tamework needs telemetry context events.");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void executeServer(@Nonnull CommandContext commandContext) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null) {
            commandContext.sender().sendMessage(Message.raw("Tamework plugin not available."));
            return;
        }
        String raw = getFirstArg(commandContext.getInputString());
        Boolean explicit = parseBoolean(raw);
        boolean enabled = explicit != null
                ? plugin.setDebugNeedsTelemetryDiagnosticsEnabled(explicit)
                : plugin.toggleDebugNeedsTelemetryDiagnosticsEnabled();
        commandContext.sender().sendMessage(Message.raw(
                "Tamework needs telemetry context events: " + (enabled ? "enabled" : "disabled")
        ));
    }

    static String getFirstArg(String input) {
        return TameworkCommandInput.firstArgument(input, "needs");
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
