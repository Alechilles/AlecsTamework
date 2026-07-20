package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.persistence.diagnostics.PersistenceDiagnosticsService;
import com.alechilles.alecstamework.persistence.recovery.ScopedPersistenceRecoveryCoordinator;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.world.World;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Runs bounded persistence diagnostic reads and exports away from the world thread. */
final class TameworkPersistenceDiagnosticsCommandHandler {
    private static final Set<String> ACTIONS = Set.of(
            "health", "incidents", "incident", "retry", "export");

    static boolean supports(String action) {
        return action != null && ACTIONS.contains(action);
    }

    void handle(CommandContext context,
                World world,
                Tamework plugin,
                TameworkPersistenceRuntime runtime,
                String action) {
        Path runtimeDataDirectory = plugin.getRuntimeDataDirectory();
        if (runtimeDataDirectory == null) {
            send(context, "Tamework persistence data directory is not available.");
            return;
        }
        PersistenceDiagnosticsService diagnostics =
                new PersistenceDiagnosticsService(
                        runtime, runtimeDataDirectory, plugin.getCrashTelemetryService());
        String[] args = arguments(context);
        switch (action) {
            case "health" -> async(context, world, diagnostics::health,
                    health -> printHealth(context, health));
            case "incidents" -> listIncidents(context, world, diagnostics, args);
            case "incident" -> showIncident(context, world, diagnostics, args);
            case "retry" -> retry(context, world, diagnostics, args);
            case "export" -> export(context, world, diagnostics, args);
            default -> send(context, "Unknown persistence diagnostic action.");
        }
    }

    private void listIncidents(CommandContext context,
                               World world,
                               PersistenceDiagnosticsService diagnostics,
                               String[] args) {
        boolean openOnly = args.length == 0 || !"all".equalsIgnoreCase(args[0]);
        async(context, world, () -> diagnostics.incidents(openOnly, 25), incidents -> {
            send(context, (openOnly ? "Open" : "Recent") + " persistence incidents: " + incidents.size());
            for (var incident : incidents) {
                send(context, "- " + incident.shortId() + " " + incident.status()
                        + " " + incident.domain() + "/" + incident.phase()
                        + " reason=" + incident.reasonCode()
                        + " repeats=" + incident.occurrenceCount());
            }
        });
    }

    private void showIncident(CommandContext context,
                              World world,
                              PersistenceDiagnosticsService diagnostics,
                              String[] args) {
        if (args.length == 0) {
            send(context, "Usage: /tw debugdb incident <incident-id>");
            return;
        }
        async(context, world, () -> diagnostics.incident(args[0]), result -> {
            if (result.isEmpty()) {
                send(context, "Incident was not found or its prefix is ambiguous.");
                return;
            }
            var details = result.orElseThrow();
            var incident = details.incident();
            send(context, "Incident " + incident.shortId() + ": " + incident.status()
                    + " " + incident.domain() + "/" + incident.phase()
                    + " reason=" + incident.reasonCode()
                    + " disposition=" + incident.disposition());
            send(context, "Occurrences=" + incident.occurrenceCount()
                    + ", recoveryAttempts=" + incident.recoveryAttempts()
                    + ", activeFences=" + details.quarantines().size());
            for (var scope : details.scopes()) {
                send(context, "- scope=" + scope.type() + " fingerprint=" + shortHash(scope.scopeHash())
                        + (scope.authorityDimension() == null ? "" : " authority=" + scope.authorityDimension()));
            }
        });
    }

    private void retry(CommandContext context,
                       World world,
                       PersistenceDiagnosticsService diagnostics,
                       String[] args) {
        if (args.length == 0) {
            send(context, "Usage: /tw debugdb retry <incident-id>");
            return;
        }
        diagnostics.retry(args[0]).whenComplete((result, failure) -> world.execute(() -> {
            if (failure != null || result == null) {
                send(context, "Incident verification could not be requested.");
                return;
            }
            send(context, "Incident verification result=" + result.status()
                    + " reason=" + result.reason()
                    + (result.attempts() == null ? "" : " attempts=" + result.attempts()));
        }));
    }

    private void export(CommandContext context,
                        World world,
                        PersistenceDiagnosticsService diagnostics,
                        String[] args) {
        if (!TameworkConfigPermission.hasAccess(context.sender())) {
            send(context, "You do not have permission to export persistence diagnostics.");
            return;
        }
        String incident = null;
        if (args.length > 0 && "incident".equalsIgnoreCase(args[0])) {
            if (args.length < 2) {
                send(context, "Usage: /tw debugdb export [recent|incident <incident-id>]");
                return;
            }
            incident = args[1];
        } else if (args.length > 0 && !"recent".equalsIgnoreCase(args[0])) {
            send(context, "Usage: /tw debugdb export [recent|incident <incident-id>]");
            return;
        }
        String selectedIncident = incident;
        async(context, world, () -> diagnostics.export(selectedIncident), result ->
                send(context, "Persistence bundle " + shortHash(result.supportId())
                        + " created (" + result.memberCount() + " files, " + result.sizeBytes()
                        + " bytes): " + result.path().toAbsolutePath().normalize()));
    }

    private void printHealth(CommandContext context,
                             PersistenceDiagnosticsService.HealthSnapshot health) {
        send(context, "Persistence storage=" + health.storageStatus()
                + " reason=" + value(health.storageReason())
                + " incident=" + value(health.storageIncidentId()));
        send(context, "Open incidents=" + health.openIncidents()
                + ", active quarantines=" + health.activeQuarantines()
                + ", queue depth=" + health.writeQueueDepth()
                + ", failed batches=" + health.failedWriteBatches()
                + ", dropped diagnostics=" + health.droppedDiagnosticRecords());
        long unavailable = health.coverage().values().stream().filter(state -> !state.ready()).count();
        long paused = health.circuits().values().stream().filter(state -> !state.effectiveEnabled()).count();
        send(context, "Evidence dimensions unavailable=" + unavailable
                + ", feature circuits paused=" + paused);
    }

    private <T> void async(CommandContext context,
                           World world,
                           ThrowingSupplier<T> supplier,
                           Consumer<T> success) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (Exception failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }).whenComplete((result, failure) -> world.execute(() -> {
            if (failure != null) send(context, "Persistence diagnostics failed; see the server log.");
            else success.accept(result);
        }));
    }

    private String[] arguments(CommandContext context) {
        String input = context.getInputString();
        if (input == null || input.isBlank()) return new String[0];
        String[] tokens = input.trim().split("\\s+");
        return tokens.length <= 3 ? new String[0]
                : Arrays.copyOfRange(tokens, 3, Math.min(tokens.length, 6));
    }

    private String shortHash(String value) {
        if (value == null || value.isBlank()) return "<none>";
        return value.substring(0, Math.min(12, value.length()));
    }

    private String value(String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }

    private void send(CommandContext context, String message) {
        context.sender().sendMessage(Message.raw(message));
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
