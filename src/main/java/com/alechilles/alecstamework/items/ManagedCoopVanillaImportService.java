package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.VanillaCoopImportEvidenceCodec.PlannedDisposition;
import com.alechilles.alecstamework.items.VanillaCoopImportEvidenceCodec.SourcePlan;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportDispositionWriter;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportDispositionWriter.ManagedRows;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportDispositionWriter.QuarantineRows;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.DispositionBinding;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.DispositionKind;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.FinalizationRequest;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.NeutralizationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SessionRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SourceRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Incrementally imports vanilla residents before normal managed-coop work runs for a context.
 *
 * <p>Every call is synchronous with respect to the live {@link CoopBlock}. Asynchronous writes
 * receive immutable DTOs only; no block, entity, component, store, or player reference crosses
 * the persistence boundary. Callers must short-circuit all later managed work while
 * {@link SweepResult#blocksManagedRuntime()} is true.</p>
 */
public final class ManagedCoopVanillaImportService {
    public enum Status {
        NOT_MANAGED,
        COMPLETE_MANAGED,
        COMPLETE_CONFLICT,
        WRITE_QUEUED,
        WRITE_PENDING,
        SOURCE_REMOVED,
        BLOCKED
    }

    public record ImportContext(@Nonnull ManagedCoopAuthorityKey authorityKey,
                                @Nonnull String coopId,
                                int maximumResidents,
                                boolean enabledManaged) {
        public ImportContext {
            Objects.requireNonNull(authorityKey, "authorityKey");
            if (coopId == null || coopId.isBlank()) {
                throw new IllegalArgumentException("coopId must not be blank");
            }
            coopId = coopId.trim().toLowerCase(Locale.ROOT);
            if (maximumResidents < 0) {
                throw new IllegalArgumentException("maximumResidents must not be negative");
            }
        }
    }

    public record SweepResult(@Nonnull Status status,
                              @Nullable String detail,
                              boolean blocksManagedRuntime) {
        public SweepResult {
            Objects.requireNonNull(status, "status");
        }
    }

    private final ManagedCoopResidentRepository residents;
    private final CoopLifecycleOperationRepository lifecycle;
    private final ManagedCoopImportRepository imports;
    private final NpcProfileRepository profiles;
    private final ManagedCoopImportDispositionWriter dispositionWriter;
    private final ManagedCoopCompositeIndexRefreshService compositeIndexes;
    private final VanillaCoopImportAdapter adapter;
    private final VanillaCoopImportAuditPreparer preparer;
    private final VanillaCoopImportEvidenceCodec evidenceCodec;
    private final VanillaCoopImportNeutralizer neutralizer;
    private final VanillaCoopImportAbsenceVerifier absenceVerifier;
    private final Map<String, PendingWrite> pending = new ConcurrentHashMap<>();

    public ManagedCoopVanillaImportService(
            @Nonnull ManagedCoopResidentRepository residents,
            @Nonnull CoopLifecycleOperationRepository lifecycle,
            @Nonnull ManagedCoopImportRepository imports,
            @Nonnull NpcProfileRepository profiles,
            @Nonnull ManagedCoopCompositeIndexRefreshService compositeIndexes) {
        this(
                residents,
                lifecycle,
                imports,
                profiles,
                compositeIndexes,
                new ManagedCoopImportDispositionWriter(imports),
                new VanillaCoopImportAdapter(),
                new VanillaCoopImportAuditPreparer(),
                new VanillaCoopImportEvidenceCodec(),
                new VanillaCoopImportNeutralizer(),
                new VanillaCoopImportAbsenceVerifier()
        );
    }

    ManagedCoopVanillaImportService(
            @Nonnull ManagedCoopResidentRepository residents,
            @Nonnull CoopLifecycleOperationRepository lifecycle,
            @Nonnull ManagedCoopImportRepository imports,
            @Nonnull NpcProfileRepository profiles,
            @Nonnull ManagedCoopCompositeIndexRefreshService compositeIndexes,
            @Nonnull ManagedCoopImportDispositionWriter dispositionWriter,
            @Nonnull VanillaCoopImportAdapter adapter,
            @Nonnull VanillaCoopImportAuditPreparer preparer,
            @Nonnull VanillaCoopImportEvidenceCodec evidenceCodec,
            @Nonnull VanillaCoopImportNeutralizer neutralizer,
            @Nonnull VanillaCoopImportAbsenceVerifier absenceVerifier) {
        this.residents = Objects.requireNonNull(residents, "residents");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.imports = Objects.requireNonNull(imports, "imports");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.compositeIndexes = Objects.requireNonNull(compositeIndexes, "compositeIndexes");
        this.dispositionWriter = Objects.requireNonNull(dispositionWriter, "dispositionWriter");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.preparer = Objects.requireNonNull(preparer, "preparer");
        this.evidenceCodec = Objects.requireNonNull(evidenceCodec, "evidenceCodec");
        this.neutralizer = Objects.requireNonNull(neutralizer, "neutralizer");
        this.absenceVerifier = Objects.requireNonNull(absenceVerifier, "absenceVerifier");
    }

    /** Runs one fail-closed import step for an exact, currently loaded managed coop context. */
    @Nonnull
    public SweepResult sweep(@Nonnull ImportContext context,
                             @Nonnull CoopBlock coop,
                             long nowMs) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(coop, "coop");
        if (!context.enabledManaged()) {
            return result(Status.NOT_MANAGED, "managed_authority_disabled", false);
        }
        if (nowMs == 0L) {
            return blocked("non_zero_signed_import_timestamp_required");
        }
        SweepResult pendingResult = settlePending(context.authorityKey().authorityId());
        if (pendingResult != null) {
            return pendingResult;
        }
        VanillaCoopImportAdapter.AuditResult audit = adapter.auditForImport(coop);
        if (!audit.readable()
                || !VanillaCoopImportAdapter.SUPPORTED_LAYOUT_ID.equals(audit.layoutId())) {
            return blocked("unsupported_vanilla_coop_layout:" + audit.detail());
        }
        ManagedCoopReadResult<AuthorityRecord> authorityRead =
                residents.loadAuthority(context.authorityKey(), context.coopId());
        if (authorityRead.status() == ManagedCoopReadResult.Status.FAILED) {
            return blocked(readFailure("authority_read_failed", authorityRead));
        }
        if (authorityRead.status() == ManagedCoopReadResult.Status.NOT_FOUND) {
            AuthorityState initial = audit.residents().isEmpty()
                    ? AuthorityState.TWORK_MANAGED : AuthorityState.VANILLA_DISCOVERED;
            return queue(context, "register_" + initial.name().toLowerCase(Locale.ROOT),
                    residents.registerAuthority(
                            context.authorityKey(), context.coopId(), initial, nowMs));
        }
        return advance(context, coop, audit, authorityRead.value(), nowMs);
    }

    private SweepResult advance(ImportContext context,
                                CoopBlock coop,
                                VanillaCoopImportAdapter.AuditResult audit,
                                AuthorityRecord authority,
                                long nowMs) {
        return switch (authority.state()) {
            case TWORK_MANAGED -> startImportIfNeeded(context, audit, nowMs);
            case VANILLA_DISCOVERED -> beginSession(context, audit, nowMs);
            case IMPORTING_TO_TWORK -> advanceSession(context, coop, audit, nowMs);
            case CONFLICT -> result(Status.COMPLETE_CONFLICT,
                    "managed_coop_import_quarantined", true);
            case DISABLED -> blocked("managed_coop_authority_persisted_disabled");
        };
    }

    private SweepResult startImportIfNeeded(ImportContext context,
                                            VanillaCoopImportAdapter.AuditResult audit,
                                            long nowMs) {
        if (audit.residents().isEmpty()) {
            return result(Status.COMPLETE_MANAGED, null, false);
        }
        ManagedCoopReadResult<List<OperationRecord>> active =
                lifecycle.loadActiveOperations(context.authorityKey(), context.coopId());
        if (active.status() != ManagedCoopReadResult.Status.LOADED || active.value() == null) {
            return blocked(readFailure("lifecycle_read_failed", active));
        }
        if (!active.value().isEmpty()) {
            return blocked("active_lifecycle_operation_prevents_import_start");
        }
        return queue(context, "mark_vanilla_discovered",
                residents.transitionAuthority(
                        context.authorityKey(), AuthorityState.TWORK_MANAGED,
                        AuthorityState.VANILLA_DISCOVERED, null, nowMs));
    }

    private SweepResult beginSession(ImportContext context,
                                     VanillaCoopImportAdapter.AuditResult audit,
                                     long nowMs) {
        ManagedCoopReadResult<List<ResidentRecord>> residentRead =
                residents.loadAllActiveResidents();
        ManagedCoopReadResult<List<OperationRecord>> operationRead =
                lifecycle.loadAllActiveOperations();
        if (residentRead.status() != ManagedCoopReadResult.Status.LOADED
                || residentRead.value() == null) {
            return blocked(readFailure("resident_read_failed", residentRead));
        }
        if (operationRead.status() != ManagedCoopReadResult.Status.LOADED
                || operationRead.value() == null) {
            return blocked(readFailure("lifecycle_read_failed", operationRead));
        }
        boolean exactAuthorityOperation = operationRead.value().stream().anyMatch(operation ->
                operation.authorityKey().equals(context.authorityKey())
                        && operation.coopId().equalsIgnoreCase(context.coopId()));
        if (exactAuthorityOperation) {
            return blocked("active_lifecycle_operation_prevents_import_audit");
        }
        final VanillaCoopImportAuditPreparer.PreparedAudit prepared;
        try {
            prepared = preparer.prepare(new VanillaCoopImportAuditPreparer.Request(
                    context.authorityKey(), context.coopId(), context.maximumResidents(),
                    audit, residentRead.value(), operationRead.value(),
                    profiles::resolveProfileId, nowMs));
        } catch (RuntimeException exception) {
            return blocked("import_audit_preparation_failed:" + detail(exception));
        }
        return queue(context, "begin_import_session",
                imports.beginSession(prepared.beginRequest()));
    }

    private SweepResult advanceSession(ImportContext context,
                                       CoopBlock coop,
                                       VanillaCoopImportAdapter.AuditResult audit,
                                       long nowMs) {
        ManagedCoopReadResult<SessionRecord> sessionRead =
                imports.loadActiveSession(context.authorityKey(), context.coopId());
        if (sessionRead.status() != ManagedCoopReadResult.Status.LOADED
                || sessionRead.value() == null) {
            return blocked(readFailure("active_import_session_missing", sessionRead));
        }
        SessionRecord session = sessionRead.value();
        ManagedCoopReadResult<List<SourceRecord>> sourceRead =
                imports.loadSources(session.envelope().sessionId());
        if (sourceRead.status() != ManagedCoopReadResult.Status.LOADED
                || sourceRead.value() == null
                || sourceRead.value().size() != session.sourceCount()) {
            return blocked(readFailure("import_sources_not_loaded", sourceRead));
        }
        List<SourceRecord> sources = sourceRead.value();
        for (SourceRecord source : sources) {
            if (source.disposition() == null) {
                return bindSource(context, session, source, nowMs);
            }
        }
        for (SourceRecord source : sources) {
            if (source.neutralizationState() == NeutralizationState.AUTHORIZED) {
                return neutralizeSource(context, coop, audit, session, sources, source, nowMs);
            }
        }
        return finalizeSession(context, session, sources, nowMs);
    }

    private SweepResult bindSource(ImportContext context,
                                   SessionRecord session,
                                   SourceRecord source,
                                   long nowMs) {
        final SourcePlan plan;
        try {
            plan = evidenceCodec.decodeSourcePlan(source.evidence());
        } catch (RuntimeException exception) {
            return blocked("persisted_source_plan_invalid:" + detail(exception));
        }
        String commandId = ManagedCoopImportDispositionWriter.commandId(
                session.envelope().sessionId(), source.evidence().sourceId(),
                plan.disposition().name());
        if (plan.disposition() == PlannedDisposition.QUARANTINED) {
            String conflictId = ManagedCoopImportDispositionWriter.conflictId(
                    session.envelope().sessionId(), source.evidence().sourceId(),
                    plan.conflictKind());
            DispositionBinding binding = binding(
                    session, source, commandId, DispositionKind.QUARANTINED,
                    null, null, null, conflictId, plan.conflictKind(), nowMs);
            return queue(context, "bind_quarantined_source",
                    dispositionWriter.bindQuarantined(new QuarantineRows(
                            binding, source.evidence(), context.authorityKey(), context.coopId())));
        }
        ResidentRecord resident = plan.disposition() == PlannedDisposition.MATCHED
                ? findResident(context, plan.residentId()) : null;
        if (plan.disposition() == PlannedDisposition.MATCHED && resident == null) {
            return blocked("planned_matched_resident_missing_or_unreadable");
        }
        String snapshotJson = resident == null
                ? source.evidence().managedSnapshotJson() : resident.snapshotJson();
        String snapshotHash = resident == null
                ? source.evidence().managedSnapshotHash() : resident.snapshotHash();
        if (snapshotJson == null || snapshotHash == null) {
            return blocked("managed_import_snapshot_missing");
        }
        int slot = resident == null ? plan.targetSlot() : resident.residentSlot();
        long generation = resident == null ? 0L : resident.generation();
        String operationId = ManagedCoopImportDispositionWriter.operationId(
                session.envelope().sessionId(), source.evidence().sourceId(),
                plan.profileId(), slot);
        DispositionKind disposition = plan.disposition() == PlannedDisposition.IMPORTED
                ? DispositionKind.IMPORTED : DispositionKind.MATCHED;
        DispositionBinding binding = binding(
                session, source, commandId, disposition, operationId,
                plan.residentId(), plan.profileId(), null, null, nowMs);
        ManagedRows rows = new ManagedRows(
                binding, source.evidence(), context.authorityKey(), context.coopId(), slot,
                resident == null ? plan.residentUuid() : resident.residentUuid(),
                plan.roleId(), snapshotJson, snapshotHash,
                resident == null ? source.evidence().managedSnapshotVersion() : resident.snapshotVersion(),
                source.evidence().deployedToWorld()
                        ? ManagedCoopResidentRepository.ResidentState.DEPLOYED
                        : ManagedCoopResidentRepository.ResidentState.HOUSED,
                generation,
                resident == null
        );
        return queue(context, "bind_" + disposition.name().toLowerCase(Locale.ROOT),
                dispositionWriter.bindManaged(rows));
    }

    private ResidentRecord findResident(ImportContext context, String residentId) {
        ManagedCoopReadResult<List<ResidentRecord>> read =
                residents.loadActiveResidents(context.authorityKey(), context.coopId());
        if (read.status() != ManagedCoopReadResult.Status.LOADED || read.value() == null) {
            return null;
        }
        return read.value().stream().filter(value -> value.residentId().equals(residentId))
                .findFirst().orElse(null);
    }

    private SweepResult neutralizeSource(ImportContext context,
                                         CoopBlock coop,
                                         VanillaCoopImportAdapter.AuditResult audit,
                                         SessionRecord session,
                                         List<SourceRecord> sources,
                                         SourceRecord source,
                                         long nowMs) {
        VanillaCoopImportNeutralizer.Result removal = neutralizer.neutralize(coop, source.evidence());
        if (!removal.absentAfter()) {
            return blocked("vanilla_source_neutralization_" + removal.status().name().toLowerCase(Locale.ROOT)
                    + ":" + removal.detail());
        }
        VanillaCoopImportAdapter.AuditResult after = removal.status()
                == VanillaCoopImportNeutralizer.Status.REMOVED
                ? adapter.auditForImport(coop) : audit;
        VanillaCoopImportAbsenceVerifier.Result verified =
                absenceVerifier.verify(session, sources, source, after, nowMs);
        if (verified.status() != VanillaCoopImportAbsenceVerifier.Status.VERIFIED
                || verified.proof() == null) {
            return blocked("vanilla_source_absence_not_proven:" + verified.detail());
        }
        SweepResult queued = queue(context, "record_verified_source_absence",
                imports.recordVerifiedNeutralization(verified.proof()));
        return queued.status() == Status.WRITE_QUEUED
                ? result(Status.SOURCE_REMOVED, removal.status().name().toLowerCase(Locale.ROOT), true)
                : queued;
    }

    private SweepResult finalizeSession(ImportContext context,
                                        SessionRecord session,
                                        List<SourceRecord> sources,
                                        long nowMs) {
        boolean quarantined = sources.stream()
                .anyMatch(source -> source.disposition() == DispositionKind.QUARANTINED);
        AuthorityState target = quarantined ? AuthorityState.CONFLICT : AuthorityState.TWORK_MANAGED;
        String commandId = ManagedCoopImportDispositionWriter.commandId(
                session.envelope().sessionId(), session.envelope().authorityKey().authorityId(),
                "finalize_" + target.name());
        return queue(context, "finalize_import_authority",
                imports.finalizeAuthority(new FinalizationRequest(
                        session.envelope().sessionId(), context.authorityKey(), context.coopId(),
                        session.envelope().auditFingerprint(), commandId, target, nowMs)));
    }

    private DispositionBinding binding(SessionRecord session,
                                       SourceRecord source,
                                       String commandId,
                                       DispositionKind disposition,
                                       @Nullable String operationId,
                                       @Nullable String residentId,
                                       @Nullable String profileId,
                                       @Nullable String conflictId,
                                       @Nullable String conflictKind,
                                       long nowMs) {
        return new DispositionBinding(
                session.envelope().sessionId(), source.evidence().sourceId(),
                session.envelope().auditFingerprint(), source.evidence().sourceFingerprint(),
                commandId, disposition, operationId, residentId, profileId,
                conflictId, conflictKind, nowMs);
    }

    private SweepResult queue(ImportContext context,
                              String operation,
                              PersistenceWriteQueue.WriteSubmission<?> submission) {
        if (!submission.accepted()) {
            return blocked("import_write_rejected:" + operation);
        }
        pending.put(context.authorityKey().authorityId(),
                new PendingWrite(operation, submission.completion()));
        return result(Status.WRITE_QUEUED, operation, true);
    }

    @Nullable
    private SweepResult settlePending(String authorityId) {
        PendingWrite write = pending.get(authorityId);
        if (write == null) {
            return null;
        }
        if (!write.completion().isDone()) {
            return result(Status.WRITE_PENDING, write.operation(), true);
        }
        pending.remove(authorityId, write);
        final PersistenceWriteQueue.WriteOutcome<?> outcome;
        try {
            outcome = write.completion().join();
        } catch (RuntimeException exception) {
            return blocked("import_write_completion_failed:" + detail(exception));
        }
        if (!outcome.isCommitted()) {
            return blocked("import_write_not_committed:" + outcome.failureReason());
        }
        Object value = outcome.value();
        if (value instanceof ManagedCoopImportRepository.MutationResult mutation
                && !mutation.succeeded()) {
            return blocked("import_mutation_" + mutation.status().name().toLowerCase(Locale.ROOT)
                    + ":" + mutation.detail());
        }
        if (value instanceof ManagedCoopResidentRepository.MutationResult mutation
                && !mutation.succeeded()) {
            return blocked("authority_mutation_" + mutation.status().name().toLowerCase(Locale.ROOT)
                    + ":" + mutation.detail());
        }
        ManagedCoopCompositeIndexRefreshService.RefreshResult refreshed =
                compositeIndexes.refresh();
        if (refreshed == null || !refreshed.refreshed() || !compositeIndexes.isTrusted()) {
            return blocked("import_composite_index_refresh_failed:"
                    + (refreshed == null ? "missing_result" : refreshed.detail()));
        }
        return null;
    }

    private String readFailure(String prefix, ManagedCoopReadResult<?> result) {
        return result.failure() == null ? prefix + ":" + result.status().name().toLowerCase(Locale.ROOT)
                : prefix + ":" + result.failure().kind().name().toLowerCase(Locale.ROOT)
                + ":" + result.failure().detail();
    }

    private SweepResult blocked(String detail) {
        return result(Status.BLOCKED, detail, true);
    }

    private SweepResult result(Status status, @Nullable String detail, boolean blocks) {
        return new SweepResult(status, detail, blocks);
    }

    private String detail(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    private record PendingWrite(
            @Nonnull String operation,
            @Nonnull CompletableFuture<? extends PersistenceWriteQueue.WriteOutcome<?>> completion) {
    }
}
