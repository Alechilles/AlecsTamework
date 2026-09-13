package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.diagnostics
        .PersistenceDiagnosticExporter;
import com.alechilles.alecstamework.persistence.diagnostics
        .BondedCompanionDiagnosticSnapshot;
import com.alechilles.alecstamework.persistence.diagnostics
        .BondedCompanionDiagnosticContributor;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.runtime
        .PersistenceDiagnosticsReader;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceDiagnosticsSnapshot;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceMetricsSnapshot;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceOperationalStatus;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Prints bounded replacement-persistence status without exposing storage maintenance internals.
 */
public final class TameworkDebugDbCommand
        extends AbstractTameworkServerCommand {
    enum Action {
        STATUS("status"),
        HEALTH("health"),
        DETAIL("detail"),
        EXPORT("export");

        private final String commandName;

        Action(String commandName) {
            this.commandName = commandName;
        }
    }

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final Action action;
    private final PersistenceDiagnosticsReader diagnostics;
    private final PersistenceDiagnosticExporter exporter;
    private final BondedCompanionDiagnosticContributor bonded;

    public TameworkDebugDbCommand(
            @Nonnull Action action,
            @Nullable PersistenceDiagnosticsReader diagnostics,
            @Nullable PersistenceDiagnosticExporter exporter,
            @Nullable BondedCompanionDiagnosticContributor bonded
    ) {
        super(
                action.commandName,
                "server.tamework.commands.debugDb.description"
        );
        this.action = action;
        this.diagnostics = diagnostics;
        this.exporter = exporter;
        this.bonded = bonded;
    }

    @Override
    protected void executeServer(@Nonnull CommandContext context) {
        if (diagnostics == null && exporter == null && bonded == null) {
            send(context, Message.translation("server.tamework.commands.debugDb.replacement.persistence.runtime.is.not.available"));
            return;
        }
        switch (action) {
            case STATUS, HEALTH -> printStatus(context);
            case DETAIL -> printDetail(context);
            case EXPORT -> export(context);
        }
    }

    private void printStatus(CommandContext context) {
        if (diagnostics == null) {
            send(context, Message.translation("server.tamework.commands.debugDb.generic.persistence.status.is.unavailable"));
            printBondedStatus(context);
            return;
        }
        final PublicPersistenceOperationalStatus status;
        final PublicPersistenceMetricsSnapshot metrics;
        try {
            status = diagnostics.status();
            metrics = diagnostics.metrics();
        } catch (RuntimeException unavailable) {
            send(context, Message.translation("server.tamework.commands.debugDb.generic.persistence.status.is.unavailable"));
            printBondedStatus(context);
            return;
        }
        send(context, Message.translation("server.tamework.commands.debugDb.persistence.engine.mode.origin.schema").param("0", String.valueOf(status.engine())).param("1", String.valueOf(status.storageMode())).param("2", String.valueOf(status.targetOrigin()
                .map(Enum::name).orElse("<pending>"))).param("3", String.valueOf((status.schemaVersion().isPresent()
                ? status.schemaVersion().getAsInt() : "<pending>"))));
        send(context, Message.translation("server.tamework.commands.debugDb.startup.readiness.running.deferred.failed.detail").param("0", String.valueOf(status.startup().readiness())).param("1", String.valueOf(value(status.startup().runningNode()))).param("2", String.valueOf(value(status.startup().deferredNode()))).param("3", String.valueOf(value(status.startup().failedNode()))).param("4", String.valueOf(text(status.startup().detail()))));
        send(context, Message.translation("server.tamework.commands.debugDb.operations.accepted.rejected.completed.failed.busyretries.readsfailed").param("0", String.valueOf(accepted(metrics))).param("1", String.valueOf(rejected(metrics))).param("2", String.valueOf(completed(metrics))).param("3", String.valueOf(failed(metrics))).param("4", String.valueOf(retries(metrics))).param("5", String.valueOf(metrics.readsFailed())));
        boolean schemaVerified = status.startupNodes().get(
                PersistenceStartupNode.VALIDATE_SCHEMA
        ) == PublicPersistenceOperationalStatus.NodeState.COMPLETED;
        send(context, Message.translation("server.tamework.commands.debugDb.schema.integrity.validation.checkpoint").param("0", String.valueOf((schemaVerified ? "complete" : "not complete"))).param("1", String.valueOf(status.lastCheckpoint().status())));
        printBondedStatus(context);
    }

    private void printDetail(CommandContext context) {
        if (diagnostics == null) {
            send(context, Message.translation("server.tamework.commands.debugDb.generic.persistence.detail.is.unavailable"));
            printBondedStatus(context);
            return;
        }
        final java.util.concurrent.CompletionStage<
                PersistenceReadResult<PublicPersistenceDiagnosticsSnapshot>>
                details;
        try {
            details = diagnostics.details();
        } catch (RuntimeException unavailable) {
            send(context, Message.translation("server.tamework.commands.debugDb.generic.persistence.detail.is.unavailable"));
            printBondedStatus(context);
            return;
        }
        details.whenComplete((read, failure) -> {
            if (failure != null || read == null) {
                send(context, Message.translation("server.tamework.commands.debugDb.persistence.detail.is.unavailable"));
                printBondedStatus(context);
                return;
            }
            if (!(read instanceof PersistenceReadResult.Found<
                    PublicPersistenceDiagnosticsSnapshot> found)) {
                send(context, Message.translation("server.tamework.commands.debugDb.persistence.detail.read.did.not.complete"));
                printBondedStatus(context);
                return;
            }
            PublicPersistenceDiagnosticsSnapshot detail = found.value();
            long incidents = detail.openIncidentsByCode().values().stream()
                    .mapToLong(Long::longValue).sum();
            long quarantines = detail.activeQuarantinesByScope().values()
                    .stream().mapToLong(Long::longValue).sum();
            send(context, Message.translation("server.tamework.commands.debugDb.persistence.detail.features.outboxhead.openincidents.activequarantines.opencircuits").param("0", String.valueOf(detail.features().size())).param("1", String.valueOf(detail.outboxHead())).param("2", String.valueOf(incidents)).param("3", String.valueOf(quarantines)).param("4", String.valueOf(detail.openCircuitCount())).param("5", String.valueOf(detail.operationsByPhase())));
            printBondedStatus(context);
        });
    }

    private void printBondedStatus(CommandContext context) {
        if (bonded != null) {
            send(context, bondedLine(bonded.snapshot()));
            return;
        }
        if (exporter == null) {
            return;
        }
        exporter.bondedSnapshot().ifPresent(snapshot -> send(
                context,
                bondedLine(snapshot)
        ));
    }

    private Message bondedLine(BondedCompanionDiagnosticSnapshot snapshot) {
        return Message.translation("server.tamework.commands.debugDb.bonded.companions.readiness.schema.stored.active.dead").param("0", String.valueOf(snapshot.readiness())).param("1", String.valueOf(snapshot.schemaVersion())).param("2", String.valueOf(snapshot.storedProfiles())).param("3", String.valueOf(snapshot.activeProfiles())).param("4", String.valueOf(snapshot.deadProfiles())).param("5", String.valueOf(snapshot.activeLeases())).param("6", String.valueOf(snapshot.pendingBoundedCleanups())).param("7", String.valueOf(snapshot.lastFailureCategory()));
    }

    private void export(CommandContext context) {
        if (exporter == null) {
            send(context, Message.translation("server.tamework.commands.debugDb.persistence.diagnostic.export.is.unavailable"));
            return;
        }
        send(context, Message.translation("server.tamework.commands.debugDb.collecting.bounded.persistence.diagnostics"));
        exporter.export().whenComplete((result, failure) -> {
            if (failure != null || result == null) {
                send(context, Message.translation("server.tamework.commands.debugDb.persistence.diagnostic.export.failed.see.the.server"));
                return;
            }
            send(context, Message.translation("server.tamework.commands.debugDb.persistence.bundle.created.files.bytes").param("0", String.valueOf(shortId(result.supportId()))).param("1", String.valueOf(result.memberCount())).param("2", String.valueOf(result.sizeBytes())).param("3", String.valueOf(result.path())));
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

    private String value(@Nullable Object value) {
        return value == null ? "<none>" : value.toString();
    }

    private String text(@Nullable String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }

    private String shortId(String value) {
        return value.substring(0, Math.min(12, value.length()));
    }

    private void send(CommandContext context, Message message) {
        LOGGER.at(Level.INFO).log(
                "/tw debug persistence " + action.commandName + ": " + message.getAnsiMessage()
        );
        context.sender().sendMessage(message);
    }
}
