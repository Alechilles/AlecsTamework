package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.metrics.CrashTelemetryDiagnostics;
import com.alechilles.alecstamework.metrics.CrashTelemetryService;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Shows crash telemetry diagnostics and can trigger manual flushes.
 */
public final class TameworkDebugCrashTelemetryCommand extends AbstractTameworkServerCommand {
    private static final Set<UUID> SIMULATE_ALLOWED_PLAYERS = Set.of(
            UUID.fromString("4f0181d6-516c-4fd4-b366-f606d9bb864a"),
            UUID.fromString("bb1eb15f-ed3f-4335-a9f6-0b280de7a440")
    );

    public TameworkDebugCrashTelemetryCommand() {
        super("debugcrashtelemetry", "Show crash telemetry diagnostics. Optional: flush|simulate|eventerror|eventlifecycle");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void executeServer(@Nonnull CommandContext commandContext) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null) {
            commandContext.sender().sendMessage(Message.raw("Tamework plugin not available."));
            return;
        }

        CrashTelemetryService crashTelemetryService = plugin.getCrashTelemetryService();
        if (crashTelemetryService == null) {
            commandContext.sender().sendMessage(Message.raw("Crash telemetry service is not initialized."));
            return;
        }

        String action = normalizeAction(getFirstArg(commandContext));
        if ("flush".equals(action)) {
            boolean scheduled = crashTelemetryService.triggerFlushAsync();
            commandContext.sender().sendMessage(Message.raw(
                    scheduled
                            ? "Crash telemetry flush scheduled."
                            : "Crash telemetry flush was not scheduled (disabled, already running, or no executor available)."
            ));
        } else if ("eventerror".equals(action)) {
            UUID playerUuid = playerUuid(commandContext);
            if (!isAllowedSimulateCaller(playerUuid)) {
                commandContext.sender().sendMessage(Message.raw(
                        "You are not allowed to run /tw debugcrashtelemetry eventerror."
                ));
                return;
            }
            String token = Long.toHexString(System.currentTimeMillis());
            boolean recorded = crashTelemetryService.recordError(
                    "debug_command_error",
                    new IllegalStateException("Simulated Tamework telemetry error event (" + token + ")"),
                    TameworkTelemetryEvents.commandContext(
                                    "/tw debugcrashtelemetry",
                                    "telemetry_debug",
                                    "crash_telemetry_debug"
                            )
                            .operation("eventerror")
                            .detail("Triggered by /tw debugcrashtelemetry eventerror.")
                            .detail("source", "debug_command")
                            .detail("debugAction", "eventerror")
                            .detail("debugToken", token)
                            .build()
            );
            commandContext.sender().sendMessage(Message.raw(
                    recorded
                            ? "Embedded telemetry error event requested."
                            : "Embedded telemetry error event was not requested (telemetry disabled or event delivery not configured)."
            ));
        } else if ("eventlifecycle".equals(action)) {
            UUID playerUuid = playerUuid(commandContext);
            if (!isAllowedSimulateCaller(playerUuid)) {
                commandContext.sender().sendMessage(Message.raw(
                        "You are not allowed to run /tw debugcrashtelemetry eventlifecycle."
                ));
                return;
            }
            String token = Long.toHexString(System.currentTimeMillis());
            boolean recorded = crashTelemetryService.recordLifecycle(
                    "debug_command_lifecycle",
                    123,
                    true,
                    TameworkTelemetryEvents.commandContext(
                                    "/tw debugcrashtelemetry",
                                    "telemetry_debug",
                                    "crash_telemetry_debug"
                            )
                            .operation("eventlifecycle")
                            .detail("Triggered by /tw debugcrashtelemetry eventlifecycle.")
                            .detail("source", "debug_command")
                            .detail("debugAction", "eventlifecycle")
                            .detail("debugToken", token)
                            .build()
            );
            commandContext.sender().sendMessage(Message.raw(
                    recorded
                            ? "Embedded telemetry lifecycle event requested."
                            : "Embedded telemetry lifecycle event was not requested (telemetry disabled or event delivery not configured)."
            ));
        } else if ("simulate".equals(action)) {
            UUID playerUuid = playerUuid(commandContext);
            if (!isAllowedSimulateCaller(playerUuid)) {
                commandContext.sender().sendMessage(Message.raw(
                        "You are not allowed to run /tw debugcrashtelemetry simulate."
                ));
            } else {
                String token = Long.toHexString(System.currentTimeMillis());
                Thread thread = new Thread(
                        () -> throwSimulatedCrash(token),
                        "TameworkCrashTelemetrySim-" + token
                );
                thread.setDaemon(true);
                thread.start();
                commandContext.sender().sendMessage(Message.raw(
                        "Simulated uncaught Tamework crash dispatched (token=" + token + ")."
                ));
                if (HytaleServer.SCHEDULED_EXECUTOR != null) {
                    HytaleServer.SCHEDULED_EXECUTOR.schedule(
                            crashTelemetryService::triggerFlushAsync,
                            1L,
                            TimeUnit.SECONDS
                    );
                }
            }
        } else if (action != null) {
            commandContext.sender().sendMessage(Message.raw("Usage: /tw debugcrashtelemetry [flush|simulate|eventerror|eventlifecycle]"));
            return;
        }

        CrashTelemetryDiagnostics diagnostics = crashTelemetryService.diagnostics();
        commandContext.sender().sendMessage(Message.raw(
                "Crash telemetry: enabled=" + diagnostics.enabled()
                        + ", endpoint=" + diagnostics.endpoint()
                        + ", pending=" + diagnostics.pendingReports()
                        + ", flushInProgress=" + diagnostics.flushInProgress()
        ));
        commandContext.sender().sendMessage(Message.raw(
                "Crash telemetry last flush: "
                        + diagnostics.formatLastFlushAtUtc()
                        + " | "
                        + diagnostics.lastFlushResult()
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
    private static String normalizeAction(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static void throwSimulatedCrash(@Nonnull String token) {
        throw new RuntimeException("Simulated Tamework crash telemetry test (" + token + ")");
    }

    private static boolean isAllowedSimulateCaller(@Nullable UUID playerUuid) {
        return playerUuid != null && SIMULATE_ALLOWED_PLAYERS.contains(playerUuid);
    }

    @Nullable
    private static UUID playerUuid(@Nonnull CommandContext context) {
        return context.isPlayer() ? context.sender().getUuid() : null;
    }
}
