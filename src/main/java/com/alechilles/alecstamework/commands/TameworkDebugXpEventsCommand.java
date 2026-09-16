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
        super("xp-events", "server.tamework.commands.debugXpEvents.description");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void executeServer(@Nonnull CommandContext commandContext) {
        Tamework plugin = Tamework.getInstance();
        CompanionXpEventDebugLogService service = plugin != null ? plugin.getCompanionXpEventDebugLogService() : null;
        if (service == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.debugXpEvents.tamework.xp.event.debug.service.is.not"));
            return;
        }
        Boolean explicit = parseBoolean(getFirstArg(commandContext.getInputString()));
        boolean enabled = explicit != null ? service.setEnabled(explicit) : service.toggle();
        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.debugXpEvents.tamework.xp.event.debug.logging.events.seen").param("0", String.valueOf((enabled ? "enabled" : "disabled"))).param("1", String.valueOf(service.getEventCount())));
    }

    @Nullable
    static String getFirstArg(String input) {
        return TameworkCommandInput.firstArgument(input, "xp-events");
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
