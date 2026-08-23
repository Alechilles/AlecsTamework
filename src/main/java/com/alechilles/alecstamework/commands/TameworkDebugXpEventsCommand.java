package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.debug.CompanionXpEventDebugLogService;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Toggles debug logging for public companion XP API events.
 */
public final class TameworkDebugXpEventsCommand extends AbstractTameworkServerCommand {
    public TameworkDebugXpEventsCommand() {
        super("xp-events", "Toggle Tamework companion XP API event debug logging.");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void executeServer(@Nonnull CommandContext commandContext) {
        Tamework plugin = Tamework.getInstance();
        CompanionXpEventDebugLogService service = plugin != null ? plugin.getCompanionXpEventDebugLogService() : null;
        if (service == null) {
            commandContext.sender().sendMessage(Message.raw("Tamework XP event debug service is not available."));
            return;
        }
        Boolean explicit = parseBoolean(getFirstArg(commandContext));
        boolean enabled = explicit != null ? service.setEnabled(explicit) : service.toggle();
        commandContext.sender().sendMessage(Message.raw(
                "Tamework XP event debug logging: "
                        + (enabled ? "enabled" : "disabled")
                        + " (events seen this session: "
                        + service.getEventCount()
                        + ")"
        ));
    }

    @Nullable
    private static String getFirstArg(@Nonnull CommandContext commandContext) {
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

    @Nullable
    private static Boolean parseBoolean(@Nullable String raw) {
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
