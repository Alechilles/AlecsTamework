package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.diagnostics
        .PersistenceDiagnosticExporter;
import com.alechilles.alecstamework.persistence.diagnostics
        .BondedCompanionDiagnosticSnapshot;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.runtime
        .PersistenceDiagnosticsReader;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceDiagnosticsSnapshot;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceMetricsSnapshot;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceOperationalStatus;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Prints bounded replacement-persistence status without exposing storage maintenance internals.
 */
public final class TameworkDebugDbCommand
        extends AbstractTameworkServerCommand {
    private final PersistenceDiagnosticsReader diagnostics;
    private final PersistenceDiagnosticExporter exporter;

    public TameworkDebugDbCommand(
            @Nullable PersistenceDiagnosticsReader diagnostics
    ) {
        this(diagnostics, null);
    }

    public TameworkDebugDbCommand(
            @Nullable PersistenceDiagnosticsReader diagnostics,
            @Nullable PersistenceDiagnosticExporter exporter
    ) {
        super(
                "debugdb",
                "Inspect bounded replacement persistence diagnostics."
        );
        this.diagnostics = diagnostics;
        this.exporter = exporter;
        setAllowsExtraArguments(true);
    }

    @Override
    protected void executeServer(@Nonnull CommandContext context) {
        if (diagnostics == null) {
            send(context, "Replacement persistence runtime is not available.");
            return;
        }
        String action = action(context);
        if (action == null || "health".equals(action)
                || "status".equals(action) || "integrity".equals(action)) {
            printStatus(context);
            return;
        }
        if ("detail".equals(action)) {
            printDetail(context);
            return;
        }
        if ("export".equals(action)) {
            export(context);
            return;
        }
        send(
                context,
                "Usage: /tw debugdb "
                        + "[status|health|integrity|detail|export]"
        );
    }

    private void printStatus(CommandContext context) {
        PublicPersistenceOperationalStatus status =
                diagnostics.status();
        PublicPersistenceMetricsSnapshot metrics = diagnostics.metrics();
        send(context, "Persistence engine=" + status.engine()
                + ", mode=" + status.storageMode()
                + ", origin=" + status.targetOrigin()
                .map(Enum::name).orElse("<pending>")
                + ", schema=" + (status.schemaVersion().isPresent()
                ? status.schemaVersion().getAsInt() : "<pending>"));
        send(context, "Startup readiness=" + status.startup().readiness()
                + ", running=" + value(status.startup().runningNode())
                + ", deferred=" + value(status.startup().deferredNode())
                + ", failed=" + value(status.startup().failedNode())
                + ", detail=" + text(status.startup().detail()));
        send(context, "Operations: accepted=" + accepted(metrics)
                + ", rejected=" + rejected(metrics)
                + ", completed=" + completed(metrics)
                + ", failed=" + failed(metrics)
                + ", busyRetries=" + retries(metrics)
                + ", readsFailed=" + metrics.readsFailed());
        boolean schemaVerified = status.startupNodes().get(
                PersistenceStartupNode.VALIDATE_SCHEMA
        ) == PublicPersistenceOperationalStatus.NodeState.COMPLETED;
        send(context, "Schema/integrity validation="
                + (schemaVerified ? "complete" : "not complete")
                + ", checkpoint=" + status.lastCheckpoint().status());
        printBondedStatus(context);
    }

    private void printDetail(CommandContext context) {
        diagnostics.details().whenComplete((read, failure) -> {
            if (failure != null || read == null) {
                send(context, "Persistence detail is unavailable.");
                return;
            }
            if (!(read instanceof PersistenceReadResult.Found<
                    PublicPersistenceDiagnosticsSnapshot> found)) {
                send(context, "Persistence detail read did not complete.");
                return;
            }
            PublicPersistenceDiagnosticsSnapshot detail = found.value();
            long incidents = detail.openIncidentsByCode().values().stream()
                    .mapToLong(Long::longValue).sum();
            long quarantines = detail.activeQuarantinesByScope().values()
                    .stream().mapToLong(Long::longValue).sum();
            send(context, "Persistence detail: features="
                    + detail.features().size()
                    + ", outboxHead=" + detail.outboxHead()
                    + ", openIncidents=" + incidents
                    + ", activeQuarantines=" + quarantines
                    + ", openCircuits=" + detail.openCircuitCount()
                    + ", operationPhases=" + detail.operationsByPhase());
            printBondedStatus(context);
        });
    }

    private void printBondedStatus(CommandContext context) {
        if (exporter == null) {
            return;
        }
        exporter.bondedSnapshot().ifPresent(snapshot -> send(
                context,
                bondedLine(snapshot)
        ));
    }

    private String bondedLine(BondedCompanionDiagnosticSnapshot snapshot) {
        return "Bonded companions: readiness=" + snapshot.readiness()
                + ", schema=" + snapshot.schemaVersion()
                + ", stored=" + snapshot.storedProfiles()
                + ", active=" + snapshot.activeProfiles()
                + ", dead=" + snapshot.deadProfiles()
                + ", leases=" + snapshot.activeLeases()
                + ", pendingCleanup=" + snapshot.pendingBoundedCleanups()
                + ", lastFailure=" + snapshot.lastFailureCategory();
    }

    private void export(CommandContext context) {
        if (exporter == null) {
            send(context, "Persistence diagnostic export is unavailable.");
            return;
        }
        send(context, "Collecting bounded persistence diagnostics...");
        exporter.export().whenComplete((result, failure) -> {
            if (failure != null || result == null) {
                send(
                        context,
                        "Persistence diagnostic export failed; "
                                + "see the server log."
                );
                return;
            }
            send(
                    context,
                    "Persistence bundle "
                            + shortId(result.supportId())
                            + " created (" + result.memberCount()
                            + " files, " + result.sizeBytes()
                            + " bytes): " + result.path()
            );
        });
    }

    private long accepted(PublicPersistenceMetricsSnapshot metrics) {
        return metrics.features().values().stream()
                .mapToLong(value -> value.writesAccepted()).sum();
    }

    private long rejected(PublicPersistenceMetricsSnapshot metrics) {
        return metrics.features().values().stream()
                .mapToLong(value -> value.writesRejected()).sum();
    }

    private long completed(PublicPersistenceMetricsSnapshot metrics) {
        return metrics.features().values().stream()
                .mapToLong(value -> value.unitsCompleted()).sum();
    }

    private long failed(PublicPersistenceMetricsSnapshot metrics) {
        return metrics.features().values().stream()
                .mapToLong(value -> value.unitsFailed()).sum();
    }

    private long retries(PublicPersistenceMetricsSnapshot metrics) {
        return metrics.features().values().stream()
                .mapToLong(value -> value.busyRetries()).sum();
    }

    @Nullable
    private String action(CommandContext context) {
        String input = context.getInputString();
        if (input == null) {
            return null;
        }
        String[] tokens = input.trim().split("\\s+");
        return tokens.length < 3
                ? null : tokens[2].toLowerCase(Locale.ROOT);
    }

    private String value(@Nullable Object value) {
        return value == null ? "<none>" : value.toString();
    }

    private String text(@Nullable String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }

    private String shortId(String value) {
        return value.substring(0, Math.min(12, value.length()));
    }

    private void send(CommandContext context, String message) {
        context.sender().sendMessage(Message.raw(message));
    }
}
