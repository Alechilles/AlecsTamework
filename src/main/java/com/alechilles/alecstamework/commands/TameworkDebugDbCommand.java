package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceIntegrityService;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.alechilles.alecstamework.persistence.diagnostics.LegacyPersistenceDiagnostics;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Prints SQLite persistence diagnostics and allows manual maintenance triggers.
 */
public final class TameworkDebugDbCommand extends AbstractTameworkServerCommand {
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);

    public TameworkDebugDbCommand() {
        super("debugdb", "Inspect, verify, or export bounded persistence diagnostics.");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void executeServer(@Nonnull CommandContext commandContext) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null) {
            commandContext.sender().sendMessage(Message.raw("Tamework plugin not available."));
            return;
        }
        TameworkPersistenceRuntime runtime = plugin.getPersistenceRuntime();
        if (runtime == null) {
            commandContext.sender().sendMessage(Message.raw("SQLite persistence runtime is not available."));
            return;
        }

        String action = normalizeAction(getFirstArg(commandContext));
        if (TameworkPersistenceDiagnosticsCommandHandler.supports(action)) {
            new TameworkPersistenceDiagnosticsCommandHandler().handle(
                    commandContext, plugin, runtime, action);
            return;
        } else if ("integrity".equals(action)) {
            printIntegrity(commandContext, runtime.getIntegrityService().inspect());
        } else if ("checkpoint".equals(action)) {
            boolean scheduled = runtime.requestWalCheckpoint();
            commandContext.sender().sendMessage(Message.raw(
                    scheduled
                            ? "Requested SQLite WAL checkpoint."
                            : "Unable to schedule SQLite WAL checkpoint (runtime may be shutting down)."
            ));
        } else if ("vacuum".equals(action)) {
            boolean scheduled = runtime.requestVacuum();
            commandContext.sender().sendMessage(Message.raw(
                    scheduled
                            ? "Requested SQLite VACUUM."
                            : "Unable to schedule SQLite VACUUM (runtime may be shutting down)."
            ));
        } else if (action != null) {
            commandContext.sender().sendMessage(
                    Message.raw("Usage: /tw debugdb [health|incidents|incident|retry|export|integrity|checkpoint|vacuum]"));
            return;
        }

        LegacyPersistenceDiagnostics diagnostics = runtime.collectDiagnostics();
        PersistenceWriteQueue.QueueMetrics queueMetrics = diagnostics.queueMetrics();
        PersistenceWriteQueue.QueueLifecycleMetrics lifecycleMetrics =
                runtime.getWriteQueueLifecycleMetrics();

        String healthReason = diagnostics.healthState().reason();
        String failureTime = formatTimestamp(diagnostics.healthState().lastFailureAtMs());
        commandContext.sender().sendMessage(Message.raw(
                "Persistence health=" + diagnostics.healthState().status()
                        + ", reason=" + (healthReason == null ? "<none>" : healthReason)
                        + ", lastFailure=" + failureTime
        ));

        commandContext.sender().sendMessage(Message.raw(
                "SQLite files: main=" + formatBytes(diagnostics.sqliteBytes())
                        + ", wal=" + formatBytes(diagnostics.walBytes())
                        + ", shm=" + formatBytes(diagnostics.shmBytes())
                        + ", total=" + formatBytes(diagnostics.totalBytes())
        ));

        commandContext.sender().sendMessage(Message.raw(
                "Write queue: depth=" + queueMetrics.queueDepth()
                        + ", lastBatch=" + queueMetrics.lastBatchSize()
                        + ", lastBatchWriteMs=" + formatDecimal(queueMetrics.lastBatchWriteMs())
                        + ", maxBatch=" + queueMetrics.maxBatchSize()
                        + ", avgBatch=" + formatDecimal(queueMetrics.averageBatchSize())
                        + ", avgWriteMs=" + formatDecimal(queueMetrics.averageWriteMs())
                        + ", retries=" + queueMetrics.retryAttempts()
                        + ", failures=" + queueMetrics.failedBatches()
                        + ", processedOps=" + queueMetrics.operationsProcessed()
                        + ", lastFailureReason="
                        + (queueMetrics.lastFailureReason() == null
                        ? "<none>"
                        : queueMetrics.lastFailureReason())
        ));
        commandContext.sender().sendMessage(Message.raw(
                "Write queue lifecycle: state=" + lifecycleMetrics.state()
                        + ", pending=" + lifecycleMetrics.pendingTaskCount()
                        + ", activeBatch=" + lifecycleMetrics.activeBatchSize()
                        + ", failedAccepted=" + lifecycleMetrics.failedAcceptedTasks()
                        + ", drainTimedOut=" + lifecycleMetrics.drainTimedOut()
        ));
    }

    private void printIntegrity(
            @Nonnull CommandContext commandContext,
            @Nonnull PersistenceIntegrityService.IntegrityReport report) {
        if (report.status() == PersistenceIntegrityService.ReportStatus.FAILED) {
            commandContext.sender().sendMessage(Message.raw(
                    "SQLite integrity audit FAILED: " + report.failureReason()));
        } else if (report.isClean()) {
            commandContext.sender().sendMessage(Message.raw(
                    "SQLite integrity audit: clean (SQLite, foreign keys, identity, lifecycle, and import invariants)."));
        }
        for (PersistenceIntegrityService.IntegrityIssue issue : report.issues()) {
            commandContext.sender().sendMessage(Message.raw(
                    "Integrity issue " + issue.id()
                            + ": groups=" + issue.affectedGroups()
                            + ", detail=" + issue.detail()));
        }
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

    @Nonnull
    private static String formatTimestamp(long epochMs) {
        if (epochMs <= 0L) {
            return "<never>";
        }
        return TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(epochMs));
    }

    @Nonnull
    private static String formatDecimal(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    @Nonnull
    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024.0) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        return String.format(Locale.ROOT, "%.2f MB", mb);
    }
}
