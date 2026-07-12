package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopVanillaImportService.ImportContext;
import com.alechilles.alecstamework.items.ManagedCoopVanillaImportService.Status;
import com.alechilles.alecstamework.items.ManagedCoopVanillaImportService.SweepResult;
import com.alechilles.alecstamework.items.ManagedCoopVanillaProjectionAdoptionGateway.AdoptionRequest;
import com.alechilles.alecstamework.items.ManagedCoopVanillaProjectionAdoptionGateway.AdoptionResult;
import com.alechilles.alecstamework.items.VanillaCoopImportEvidenceCodec.PlannedDisposition;
import com.alechilles.alecstamework.items.VanillaCoopImportEvidenceCodec.SourcePlan;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationKind;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportDispositionWriter;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportDispositionWriter.ManagedRows;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportDispositionWriter.QuarantineRows;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.DispositionBinding;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.DispositionKind;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.FinalizationRequest;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.NeutralizationProof;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.NeutralizationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SessionRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SourceRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Advances one durable active import session through source binding, live projection adoption,
 * vanilla neutralization, current-boot absence reproof, and terminal authority publication.
 */
final class ManagedCoopImportSessionProcessor {
    private final ManagedCoopResidentRepository residents;
    private final CoopLifecycleOperationRepository lifecycle;
    private final ManagedCoopImportRepository imports;
    private final ManagedCoopImportDispositionWriter dispositionWriter;
    private final VanillaCoopImportAdapter adapter;
    private final VanillaCoopImportEvidenceCodec evidenceCodec;
    private final VanillaCoopImportNeutralizer neutralizer;
    private final VanillaCoopImportAbsenceVerifier absenceVerifier;
    private final ManagedCoopVanillaProjectionAdoptionGateway projections;
    private final ManagedCoopImportControl importControl;
    private final ManagedCoopImportWriteCoordinator writes;

    ManagedCoopImportSessionProcessor(
            @Nonnull ManagedCoopResidentRepository residents,
            @Nonnull CoopLifecycleOperationRepository lifecycle,
            @Nonnull ManagedCoopImportRepository imports,
            @Nonnull ManagedCoopImportDispositionWriter dispositionWriter,
            @Nonnull VanillaCoopImportAdapter adapter,
            @Nonnull VanillaCoopImportEvidenceCodec evidenceCodec,
            @Nonnull VanillaCoopImportNeutralizer neutralizer,
            @Nonnull VanillaCoopImportAbsenceVerifier absenceVerifier,
            @Nonnull ManagedCoopVanillaProjectionAdoptionGateway projections,
            @Nonnull ManagedCoopImportControl importControl,
            @Nonnull ManagedCoopImportWriteCoordinator writes) {
        this.residents = Objects.requireNonNull(residents, "residents");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.imports = Objects.requireNonNull(imports, "imports");
        this.dispositionWriter = Objects.requireNonNull(dispositionWriter, "dispositionWriter");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.evidenceCodec = Objects.requireNonNull(evidenceCodec, "evidenceCodec");
        this.neutralizer = Objects.requireNonNull(neutralizer, "neutralizer");
        this.absenceVerifier = Objects.requireNonNull(absenceVerifier, "absenceVerifier");
        this.projections = Objects.requireNonNull(projections, "projections");
        this.importControl = Objects.requireNonNull(importControl, "importControl");
        this.writes = Objects.requireNonNull(writes, "writes");
    }

    @Nonnull
    SweepResult advance(@Nonnull ImportContext context,
                        @Nonnull CoopBlock coop,
                        @Nonnull VanillaCoopImportAdapter.AuditResult audit,
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
        for (SourceRecord source : sources) {
            if (source.neutralizationState() == NeutralizationState.VERIFIED_ABSENT
                    && !absenceVerifier.isCurrentBootProof(source)) {
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
            return writes.queue(context, "bind_quarantined_source",
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
        return writes.queue(context, "bind_" + disposition.name().toLowerCase(Locale.ROOT),
                dispositionWriter.bindManaged(rows));
    }

    @Nullable
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
        SweepResult adoptionBlock = adoptDeployedSource(context, session, source);
        if (adoptionBlock != null) {
            return adoptionBlock;
        }
        VanillaCoopImportNeutralizer.Result removal = neutralizer.neutralize(coop, source.evidence());
        if (!removal.absentAfter()) {
            return blocked("vanilla_source_neutralization_"
                    + removal.status().name().toLowerCase(Locale.ROOT) + ":" + removal.detail());
        }
        VanillaCoopImportAdapter.AuditResult after = adapter.auditForImport(coop);
        if (!after.readable()) {
            return blocked("vanilla_source_post_neutralization_audit_failed:" + after.detail());
        }
        VanillaCoopImportAbsenceVerifier.Result verified =
                absenceVerifier.verify(session, sources, source, after, nowMs);
        if (verified.status() != VanillaCoopImportAbsenceVerifier.Status.VERIFIED
                || verified.proof() == null) {
            return blocked("vanilla_source_absence_not_proven:" + verified.detail());
        }
        boolean refreshing = source.neutralizationState() == NeutralizationState.VERIFIED_ABSENT;
        NeutralizationProof proof = refreshing
                ? proofWithRecordedCompletionTime(verified.proof(), source.verifiedAbsentAtMs())
                : verified.proof();
        SweepResult queued = writes.queue(context, refreshing
                        ? "refresh_verified_source_absence" : "record_verified_source_absence",
                refreshing
                        ? imports.refreshVerifiedNeutralization(proof)
                        : imports.recordVerifiedNeutralization(proof));
        return queued.status() == Status.WRITE_QUEUED
                ? result(Status.SOURCE_REMOVED, removal.status().name().toLowerCase(Locale.ROOT), true)
                : queued;
    }

    private NeutralizationProof proofWithRecordedCompletionTime(
            NeutralizationProof proof, long recordedCompletionTime) {
        return new NeutralizationProof(
                proof.sessionId(), proof.sourceId(), proof.auditFingerprint(),
                proof.sourceFingerprint(), proof.sourcePayloadHash(), proof.sourceSlot(),
                proof.sourceOrder(), proof.persistentUuid(), proof.commandId(),
                proof.absenceProofJson(), proof.absenceProofHash(), proof.absenceProofVersion(),
                recordedCompletionTime);
    }

    /** Returns a blocking result, or {@code null} once an exact deployed source is adopted. */
    @Nullable
    private SweepResult adoptDeployedSource(ImportContext context,
                                            SessionRecord session,
                                            SourceRecord source) {
        if (!source.evidence().deployedToWorld()) {
            return null;
        }
        if (source.disposition() == null || source.disposition() == DispositionKind.QUARANTINED
                || source.operationId() == null || source.residentId() == null
                || source.profileId() == null || source.evidence().persistentUuid() == null) {
            return blocked("deployed_projection_durable_binding_incomplete");
        }
        ResidentRecord resident = findResident(context, source.residentId());
        if (!validDeployedResident(context, source, resident)) {
            return blocked("deployed_projection_resident_binding_mismatch");
        }
        final OperationRecord operation;
        try {
            operation = lifecycle.load(source.operationId());
        } catch (SQLException exception) {
            return blocked("deployed_projection_import_operation_read_failed:"
                    + detail(exception));
        }
        if (!validImportOperation(context, source, resident, operation)) {
            return blocked("deployed_projection_import_operation_mismatch");
        }
        final AdoptionResult adoption;
        try {
            adoption = projections.adopt(new AdoptionRequest(
                    context.authorityKey(), context.coopId(), session.envelope().sessionId(),
                    source.evidence().sourceId(), source.evidence().sourceFingerprint(),
                    source.operationId(), source.residentId(), source.profileId(),
                    resident.residentSlot(), source.evidence().persistentUuid(),
                    resident.generation(), resident.snapshotHash()));
        } catch (RuntimeException exception) {
            return blocked("deployed_projection_adoption_failed:" + detail(exception));
        }
        if (adoption != null
                && adoption.status()
                == ManagedCoopVanillaProjectionAdoptionGateway.AdoptionStatus.PENDING) {
            return result(Status.WRITE_PENDING, "deployed_projection_adoption_pending", true);
        }
        if (adoption == null || !adoption.adoptedOrAlreadyAdopted()) {
            return blocked("deployed_projection_adoption_"
                    + (adoption == null ? "missing_result" : adoption.status().name()
                    .toLowerCase(Locale.ROOT) + ":" + adoption.detail()));
        }
        return null;
    }

    private boolean validDeployedResident(ImportContext context,
                                          SourceRecord source,
                                          @Nullable ResidentRecord resident) {
        UUID persistentUuid = source.evidence().persistentUuid();
        return resident != null && resident.active()
                && resident.state() == ManagedCoopResidentRepository.ResidentState.DEPLOYED
                && resident.authorityKey().equals(context.authorityKey())
                && resident.coopId().equalsIgnoreCase(context.coopId())
                && resident.residentId().equals(source.residentId())
                && resident.profileId().equals(source.profileId())
                && persistentUuid.equals(resident.residentUuid())
                && persistentUuid.equals(resident.deployedNpcUuid())
                && resident.snapshotHash() != null;
    }

    private boolean validImportOperation(ImportContext context,
                                         SourceRecord source,
                                         ResidentRecord resident,
                                         @Nullable OperationRecord operation) {
        boolean retirementReady = source.neutralizationState() == NeutralizationState.AUTHORIZED
                && operation != null && operation.active()
                && operation.state() == OperationState.SOURCE_RETIRE_REQUESTED
                && operation.generation() == 2L
                && operation.completedAtMs() == 0L;
        boolean currentBootRevalidation =
                source.neutralizationState() == NeutralizationState.VERIFIED_ABSENT
                && operation != null && !operation.active()
                && operation.state() == OperationState.COMPLETE
                && operation.generation() == 3L
                && operation.completedAtMs() != 0L;
        return (retirementReady || currentBootRevalidation)
                && operation.kind() == OperationKind.IMPORT
                && operation.operationId().equals(source.operationId())
                && operation.profileId().equals(source.profileId())
                && operation.authorityKey().equals(context.authorityKey())
                && operation.coopId().equalsIgnoreCase(context.coopId())
                && operation.residentSlot() == resident.residentSlot()
                && Objects.equals(operation.sourceNpcUuid(), source.evidence().persistentUuid())
                && Objects.equals(operation.snapshotHash(), resident.snapshotHash())
                && operation.expectedResidentGeneration() == resident.generation();
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
        // Never let this session's consent authorize a later identical source set.
        importControl.cancel(context.authorityKey());
        return writes.queue(context, "finalize_import_authority",
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

    private String readFailure(String prefix, ManagedCoopReadResult<?> result) {
        return result.failure() == null
                ? prefix + ":" + result.status().name().toLowerCase(Locale.ROOT)
                : prefix + ":" + result.failure().kind().name().toLowerCase(Locale.ROOT)
                + ":" + result.failure().detail();
    }

    private SweepResult blocked(String detail) {
        return result(Status.BLOCKED, detail, true);
    }

    private SweepResult result(Status status, @Nullable String detail, boolean blocks) {
        return new SweepResult(status, detail, blocks);
    }

    private String detail(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }
}
