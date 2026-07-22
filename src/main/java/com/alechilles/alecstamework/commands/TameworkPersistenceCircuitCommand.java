package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFeatureCircuitCatalog;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFeatureCircuitRegistry;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Lists or changes locally persisted persistence feature-circuit overrides. */
public final class TameworkPersistenceCircuitCommand extends AbstractTameworkServerCommand {
    public TameworkPersistenceCircuitCommand() {
        super("persistencecircuit", "List, enable, or disable a persistence feature circuit.");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void executeServer(@Nonnull CommandContext context) {
        if (!TameworkConfigPermission.hasAccess(context.sender())) {
            context.sender().sendMessage(Message.raw(
                    "You do not have permission to change persistence circuits."));
            return;
        }
        TameworkPersistenceRuntime runtime = runtime();
        if (runtime == null) {
            context.sender().sendMessage(Message.raw("Tamework persistence is not available."));
            return;
        }
        String[] arguments = arguments(context);
        if (arguments.length == 0 || "list".equalsIgnoreCase(arguments[0])) {
            list(context, runtime.getFeatureCircuitRegistry());
            return;
        }
        change(context, runtime, arguments);
    }

    private void change(CommandContext context,
                        TameworkPersistenceRuntime runtime,
                        String[] arguments) {
        Boolean enabled = parseEnabled(arguments[0]);
        PersistenceDomain domain = arguments.length > 1
                ? PersistenceFeatureCircuitCatalog.resolve(arguments[1]) : null;
        if (enabled == null || domain == null) {
            usage(context);
            return;
        }
        String reason = arguments.length > 2 ? normalizeReason(arguments[2]) : "operator_override";
        UUID senderUuid = context.sender().getUuid();
        String actor = senderUuid == null
                ? "command:server-console"
                : "command:" + runtime.getPersistenceScopeFactory()
                .ownerGlobal(senderUuid).scopeHash();
        var submission = runtime.getFeatureCircuitRepository().set(
                domain, enabled, reason, System.currentTimeMillis(), actor,
                runtime.getFeatureCircuitRegistry());
        submission.completion().whenComplete(
                (outcome, failure) -> reportChange(context, domain, enabled, outcome, failure));
    }

    @Nullable
    private Boolean parseEnabled(String action) {
        if ("enable".equalsIgnoreCase(action)) return true;
        if ("disable".equalsIgnoreCase(action)) return false;
        return null;
    }

    private void reportChange(CommandContext context,
                              PersistenceDomain domain,
                              boolean enabled,
                              PersistenceWriteQueue.WriteOutcome<Void> outcome,
                              Throwable failure) {
        boolean committed = failure == null && outcome != null && outcome.isCommitted();
        String key = PersistenceFeatureCircuitCatalog.key(domain);
        context.sender().sendMessage(Message.raw(committed
                ? "Persistence circuit '" + key + "' is now "
                + (enabled ? "enabled" : "disabled") + "."
                : "Persistence circuit change was not committed; its prior state is unchanged."));
    }

    private void list(CommandContext context, PersistenceFeatureCircuitRegistry registry) {
        Map<PersistenceDomain, PersistenceFeatureCircuitRegistry.CircuitState> states = registry.snapshot();
        context.sender().sendMessage(Message.raw("Persistence feature circuits:"));
        for (String key : PersistenceFeatureCircuitCatalog.keys()) {
            PersistenceDomain domain = PersistenceFeatureCircuitCatalog.resolve(key);
            PersistenceFeatureCircuitRegistry.CircuitState state = states.get(domain);
            boolean configured = state == null || state.enabled();
            boolean effective = domain != null && registry.isEnabled(domain);
            context.sender().sendMessage(Message.raw("- " + key + ": "
                    + (configured ? "enabled" : "disabled")
                    + (configured != effective ? " (blocked by all)" : "")));
        }
    }

    @Nullable
    private TameworkPersistenceRuntime runtime() {
        Tamework plugin = Tamework.getInstance();
        return plugin != null ? plugin.getPersistenceRuntime() : null;
    }

    private String[] arguments(CommandContext context) {
        String input = context.getInputString();
        if (input == null || input.isBlank()) return new String[0];
        String[] tokens = input.trim().split("\\s+");
        return tokens.length <= 2 ? new String[0]
                : Arrays.copyOfRange(tokens, 2, Math.min(tokens.length, 5));
    }

    private String normalizeReason(String raw) {
        String normalized = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        if (normalized.isBlank()) return "operator_override";
        return normalized.substring(0, Math.min(normalized.length(), 80));
    }

    private void usage(CommandContext context) {
        context.sender().sendMessage(Message.raw(
                "Usage: /tw persistencecircuit [list|enable <feature>|disable <feature> [reason]]"));
        context.sender().sendMessage(Message.raw(
                "Features: " + String.join(", ", PersistenceFeatureCircuitCatalog.keys())));
    }
}
