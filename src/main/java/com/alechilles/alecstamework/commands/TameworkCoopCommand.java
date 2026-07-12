package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopDiagnosticsService.AuditReport;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopDiagnosticsService.ReportStatus;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Read-only operator audit for v5 managed-coop occupancy, lifecycle, and import state. */
public final class TameworkCoopCommand extends AbstractPlayerCommand {
    public TameworkCoopCommand() {
        super("coop", "Audit Tamework managed coops. Usage: /tw coop audit|import-status");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        Tamework plugin = Tamework.getInstance();
        TameworkPersistenceRuntime runtime = plugin != null ? plugin.getPersistenceRuntime() : null;
        if (runtime == null) {
            send(commandContext, "Tamework SQLite persistence is not available.");
            return;
        }
        String action = action(commandContext);
        if (!"audit".equals(action) && !"import-status".equals(action)) {
            send(commandContext, "Usage: /tw coop audit|import-status");
            return;
        }
        AuditReport report = runtime.getManagedCoopDiagnosticsService().inspect();
        if (report.status() == ReportStatus.FAILED) {
            send(commandContext, "Managed-coop audit FAILED: " + report.failureReason());
            return;
        }
        if ("import-status".equals(action)) {
            printImportStatus(commandContext, report);
        } else {
            printAudit(commandContext, report);
        }
    }

    private void printAudit(CommandContext context, AuditReport report) {
        send(context, "Managed coops: authorities=" + report.activeAuthorities()
                + " " + report.activeAuthoritiesByState()
                + ", residents=" + report.activeResidents()
                + " " + report.activeResidentsByState());
        send(context, "Lifecycle operations=" + report.activeOperations()
                + " " + report.activeOperationsByKindAndState()
                + ", indexTrusted=" + report.compositeIndexTrusted()
                + " (residentRev=" + report.residentIndexRevision()
                + ", operationRev=" + report.operationIndexRevision() + ")");
        printImportStatus(context, report);
    }

    private void printImportStatus(CommandContext context, AuditReport report) {
        send(context, "Managed-coop import: activeSessions=" + report.activeImportSessions()
                + ", pendingSources=" + report.pendingImportSources()
                + ", awaitingAbsenceProof=" + report.awaitingAbsenceProof()
                + ", unresolvedConflicts=" + report.unresolvedImportConflicts()
                + (report.requiresAttention() ? " [attention required]" : " [clear]"));
    }

    private void send(CommandContext context, String value) {
        context.sender().sendMessage(Message.raw(value));
    }

    @Nullable
    private String action(CommandContext context) {
        String input = context.getInputString();
        if (input == null) {
            return null;
        }
        String[] tokens = input.trim().split("\\s+");
        return tokens.length >= 3 ? tokens[2].toLowerCase(Locale.ROOT) : null;
    }
}
