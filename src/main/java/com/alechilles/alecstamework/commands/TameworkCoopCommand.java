package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.items.ManagedCoopImportControl;
import com.alechilles.alecstamework.items.ManagedCoopImportControl.ConfirmationResult;
import com.alechilles.alecstamework.items.ManagedCoopVanillaImportInspectionService.ImportInspection;
import com.alechilles.alecstamework.items.ManagedCoopVanillaImportInspectionService.SourceSummary;
import com.alechilles.alecstamework.items.VanillaCoopImportEvidenceCodec.PlannedDisposition;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopDiagnosticsService.AuditReport;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopDiagnosticsService.OperationDetail;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopDiagnosticsService.ReportStatus;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopDiagnosticsService.ResidentDetail;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceIntegrityService;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.permissions.PermissionHolder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Operator diagnostics plus explicitly authorized managed-coop import reconciliation. */
public final class TameworkCoopCommand extends AbstractPlayerCommand {
    private static final String RECONCILE_PERMISSION = "tamework.command.coop.reconcile";
    private static final int MAX_DETAIL_LINES = 25;

    public TameworkCoopCommand() {
        super("coop", "Audit and reconcile Tamework managed coops.");
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
        String[] tokens = tokens(commandContext);
        String action = action(tokens);
        if ("reconcile".equals(action)) {
            reconcile(commandContext, world, playerRef, tokens);
            return;
        }
        if ("rollback-preflight".equals(action)) {
            rollbackPreflight(commandContext, runtime);
            return;
        }
        if (!"audit".equals(action) && !"import-status".equals(action)) {
            usage(commandContext);
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
            printAudit(commandContext, runtime, report);
        }
    }

    private void printAudit(CommandContext context,
                            TameworkPersistenceRuntime runtime,
                            AuditReport report) {
        send(context, "Managed coops: authorities=" + report.activeAuthorities()
                + " " + report.activeAuthoritiesByState()
                + ", residents=" + report.activeResidents()
                + " " + report.activeResidentsByState());
        send(context, "Lifecycle operations=" + report.activeOperations()
                + " " + report.activeOperationsByKindAndState()
                + ", indexTrusted=" + report.compositeIndexTrusted()
                + " (residentRev=" + report.residentIndexRevision()
                + ", operationRev=" + report.operationIndexRevision() + ")");
        PersistenceWriteQueue.QueueLifecycleMetrics queue =
                runtime.getWriteQueueLifecycleMetrics();
        send(context, "Persistence queue: state=" + queue.state()
                + ", pending=" + queue.pendingTaskCount()
                + ", activeBatch=" + queue.activeBatchSize()
                + ", failedAccepted=" + queue.failedAcceptedTasks()
                + ", drainTimedOut=" + queue.drainTimedOut());
        printResidentDetails(context, report.residentDetails(), report.activeResidents());
        printOperationDetails(context, report.operationDetails(), report.activeOperations());
        printImportStatus(context, report);
    }

    private void printResidentDetails(CommandContext context,
                                      List<ResidentDetail> details,
                                      long total) {
        int shown = Math.min(details.size(), MAX_DETAIL_LINES);
        for (int index = 0; index < shown; index++) {
            ResidentDetail resident = details.get(index);
            send(context, "Resident profile=" + resident.profileId()
                    + ", state=" + resident.state()
                    + ", site=" + site(resident.authorityKey(), resident.residentSlot())
                    + ", residentUuid=" + resident.residentUuid()
                    + ", sourceUuid=" + value(resident.sourceNpcUuid())
                    + ", projectionUuid=" + value(resident.deployedNpcUuid())
                    + ", generation=" + resident.generation());
        }
        printOmitted(context, "resident", Math.max(0L, total - shown));
    }

    private void printOperationDetails(CommandContext context,
                                       List<OperationDetail> details,
                                       long total) {
        int shown = Math.min(details.size(), MAX_DETAIL_LINES);
        for (int index = 0; index < shown; index++) {
            OperationDetail operation = details.get(index);
            send(context, "Operation id=" + operation.operationId()
                    + ", profile=" + operation.profileId()
                    + ", kind/state=" + operation.kind() + "/" + operation.state()
                    + ", site=" + site(operation.authorityKey(), operation.residentSlot())
                    + ", source=" + value(operation.sourceNpcUuid())
                    + ", planned=" + value(operation.plannedTargetUuid())
                    + ", actual=" + value(operation.actualTargetUuid())
                    + ", generation=" + operation.generation()
                    + ", retries=" + operation.retryCount()
                    + (operation.lastError() == null ? "" : ", error=" + operation.lastError()));
        }
        printOmitted(context, "operation", Math.max(0L, total - shown));
    }

    private void printOmitted(CommandContext context, String kind, long omitted) {
        if (omitted > 0) {
            send(context, "... " + omitted + " additional " + kind
                    + " detail lines omitted; inspect SQLite for the complete set.");
        }
    }

    private void printImportStatus(CommandContext context, AuditReport report) {
        send(context, "Managed-coop import: activeSessions=" + report.activeImportSessions()
                + ", pendingSources=" + report.pendingImportSources()
                + ", awaitingAbsenceProof=" + report.awaitingAbsenceProof()
                + ", unresolvedConflicts=" + report.unresolvedImportConflicts()
                + (report.requiresAttention() ? " [attention required]" : " [clear]"));
    }

    private void rollbackPreflight(CommandContext context,
                                   TameworkPersistenceRuntime runtime) {
        AuditReport coop = runtime.getManagedCoopDiagnosticsService().inspect();
        PersistenceIntegrityService.IntegrityReport integrity =
                runtime.getIntegrityService().inspect();
        PersistenceWriteQueue.QueueLifecycleMetrics queue =
                runtime.getWriteQueueLifecycleMetrics();
        PersistenceHealthService.HealthState health = runtime.getHealthState();
        BackupEvidence backups = findPreV5Backups(runtime.getSqlitePath());

        send(context, "Rollback preflight: live v5-to-v4 downgrade is unsupported. "
                + "Restore a complete pre-v5 save, or disable managed mutations and roll forward.");
        send(context, "Preflight health=" + health.status()
                + ", queue=" + queue.state()
                + " (pending=" + queue.pendingTaskCount()
                + ", activeBatch=" + queue.activeBatchSize()
                + ", failedAccepted=" + queue.failedAcceptedTasks()
                + ", drainTimedOut=" + queue.drainTimedOut() + ")");
        if (coop.status() == ReportStatus.FAILED) {
            send(context, "Preflight integrity=" + integrity.status()
                    + ", issues=" + integrity.issues().size()
                    + ", managedCoopAudit=FAILED, activeLifecycle=UNKNOWN, activeImports=UNKNOWN"
                    + ", unresolvedImportConflicts=UNKNOWN, reason=" + coop.failureReason());
        } else {
            send(context, "Preflight integrity=" + integrity.status()
                    + ", issues=" + integrity.issues().size()
                    + ", managedCoopAudit=COMPLETE"
                    + ", activeLifecycle=" + coop.activeOperations()
                    + ", activeImports=" + coop.activeImportSessions()
                    + ", unresolvedImportConflicts=" + coop.unresolvedImportConflicts());
        }
        send(context, "SQLite=" + runtime.getSqlitePath()
                + ", pre-v5 SQLite backups=" + backups.count()
                + ", latest=" + backups.latestDisplay()
                + (backups.error() == null ? "" : ", backupScanError=" + backups.error()));
        send(context, "A SQLite backup is not a complete-save backup. Stop the server cleanly "
                + "and restore the matching full save before loading an older plugin.");
    }

    private BackupEvidence findPreV5Backups(Path sqlitePath) {
        Path parent = sqlitePath.toAbsolutePath().normalize().getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return new BackupEvidence(0L, null, "database_directory_unavailable");
        }
        try (Stream<Path> paths = Files.list(parent)) {
            List<Path> backups = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches(
                            "tamework_pre_v5_.*\\.sqlite\\.bak"))
                    .sorted(Comparator.comparingLong(this::lastModified).reversed())
                    .toList();
            return new BackupEvidence(backups.size(),
                    backups.isEmpty() ? null : backups.getFirst(), null);
        } catch (IOException exception) {
            return new BackupEvidence(0L, null,
                    exception.getMessage() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getMessage());
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private void reconcile(CommandContext context,
                           World world,
                           PlayerRef playerRef,
                           String[] tokens) {
        ManagedCoopAuthorityKey authorityKey = authorityKey(context, world, tokens);
        if (authorityKey == null) {
            return;
        }
        ManagedCoopImportControl control = ManagedCoopImportControl.shared();
        if (tokens.length == 6) {
            printReconcileReport(context, control, authorityKey);
            return;
        }
        if (tokens.length == 7 && "cancel".equalsIgnoreCase(tokens[6])) {
            if (!canMutate(context)) {
                send(context, "You do not have permission to change coop import approval.");
                return;
            }
            boolean cancelled = control.cancel(authorityKey);
            send(context, cancelled
                    ? "Coop import approval revoked. A write already submitted may still commit."
                    : "No active coop import approval existed for that location.");
            return;
        }
        if (tokens.length == 8 && "confirm".equalsIgnoreCase(tokens[6])) {
            confirm(context, playerRef, control, authorityKey, tokens[7]);
            return;
        }
        usage(context);
    }

    private void confirm(CommandContext context,
                         PlayerRef playerRef,
                         ManagedCoopImportControl control,
                         ManagedCoopAuthorityKey authorityKey,
                         String fingerprint) {
        if (!canMutate(context)) {
            send(context, "You do not have permission to change coop import approval.");
            return;
        }
        final ConfirmationResult result;
        try {
            result = control.confirm(authorityKey, fingerprint, playerRef.getUuid().toString());
        } catch (IllegalArgumentException exception) {
            send(context, "Invalid import fingerprint: " + exception.getMessage());
            return;
        }
        if (!result.confirmed()) {
            send(context, "Coop import was not approved: " + result.status()
                    + (result.detail() == null ? "" : " (" + result.detail() + ")"));
            return;
        }
        send(context, "Coop import approved for the exact report fingerprint. "
                + "The runtime will revalidate it and advance at most one durable step per sweep.");
    }

    private void printReconcileReport(CommandContext context,
                                      ManagedCoopImportControl control,
                                      ManagedCoopAuthorityKey authorityKey) {
        Optional<ImportInspection> latest = control.latestInspection(authorityKey);
        if (latest.isEmpty()) {
            send(context, "No cached import report exists for that location. Keep the coop loaded "
                    + "until a managed-coop sweep runs, then retry; no import was approved.");
            return;
        }
        ImportInspection report = latest.orElseThrow();
        long deployed = report.sources().stream().filter(SourceSummary::deployedToWorld).count();
        send(context, "Coop reconcile " + authorityKey.worldName() + " "
                + authorityKey.x() + "," + authorityKey.y() + "," + authorityKey.z()
                + ": status=" + report.status()
                + ", coop=" + report.coopId()
                + ", authority=" + report.authorityState()
                + ", sources=" + report.sourceCount()
                + " (matched=" + report.count(PlannedDisposition.MATCHED)
                + ", imported=" + report.count(PlannedDisposition.IMPORTED)
                + ", overflow=" + report.overflowCount()
                + ", quarantined=" + report.count(PlannedDisposition.QUARANTINED)
                + ", deployed=" + deployed + ")");
        if (report.auditFingerprint() != null) {
            send(context, "Import fingerprint=" + report.auditFingerprint()
                    + ", session=" + report.sessionId()
                    + ", approvalRequired=" + report.approvalRequired()
                    + ", approved=" + control.hasApproval(authorityKey));
        }
        if (!report.conflictKinds().isEmpty()) {
            send(context, "Import conflicts=" + report.conflictKinds());
        }
        printImportSources(context, report.sources());
        if (report.detail() != null) {
            send(context, "Import detail=" + report.detail());
        }
    }

    private void printImportSources(CommandContext context, List<SourceSummary> sources) {
        int shown = Math.min(sources.size(), MAX_DETAIL_LINES);
        for (int index = 0; index < shown; index++) {
            SourceSummary source = sources.get(index);
            send(context, "Import source id=" + source.sourceId()
                    + ", fingerprint=" + source.sourceFingerprint()
                    + ", plan=" + source.plannedDisposition()
                    + ", persisted=" + value(source.persistedDisposition())
                    + ", neutralization=" + source.neutralizationState()
                    + ", profile=" + value(source.profileId())
                    + ", slot=" + value(source.targetSlot())
                    + ", deployed=" + source.deployedToWorld()
                    + ", overflow=" + source.overflow()
                    + ", conflict=" + value(source.conflictKind())
                    + ", unavailableFields=" + source.unavailableFieldsJson());
        }
        printOmitted(context, "import-source", sources.size() - shown);
    }

    @Nullable
    private ManagedCoopAuthorityKey authorityKey(CommandContext context,
                                                  World world,
                                                  String[] tokens) {
        if (tokens.length < 6 || world.getName() == null || world.getName().isBlank()) {
            usage(context);
            return null;
        }
        try {
            return new ManagedCoopAuthorityKey(
                    world.getName(),
                    Integer.parseInt(tokens[3]),
                    Integer.parseInt(tokens[4]),
                    Integer.parseInt(tokens[5]));
        } catch (IllegalArgumentException exception) {
            send(context, "Coop coordinates must be whole numbers in the current world.");
            return null;
        }
    }

    private boolean canMutate(CommandContext context) {
        Object sender = context.sender();
        if (!(sender instanceof PermissionHolder holder)) {
            return false;
        }
        return holder.hasPermission(RECONCILE_PERMISSION)
                || TameworkConfigPermission.hasAccess(holder);
    }

    private void usage(CommandContext context) {
        send(context, "Usage: /tw coop audit|import-status|rollback-preflight|"
                + "reconcile <x> <y> <z> "
                + "[confirm <auditFingerprint>|cancel]");
    }

    private String site(ManagedCoopAuthorityKey key, int slot) {
        return key.worldName() + ":" + key.x() + "," + key.y() + "," + key.z()
                + "#" + slot;
    }

    private String value(Object value) {
        return value == null ? "<none>" : value.toString();
    }

    private void send(CommandContext context, String value) {
        context.sender().sendMessage(Message.raw(value));
    }

    @Nullable
    private String action(String[] tokens) {
        return tokens.length >= 3 ? tokens[2].toLowerCase(Locale.ROOT) : null;
    }

    private String[] tokens(CommandContext context) {
        String input = context.getInputString();
        return input == null || input.isBlank() ? new String[0] : input.trim().split("\\s+");
    }

    private record BackupEvidence(long count, @Nullable Path latest, @Nullable String error) {
        private String latestDisplay() {
            return latest == null ? "<none>" : latest.toString();
        }
    }
}
