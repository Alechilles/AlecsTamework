package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopImportControl.AuthorizationStatus;
import com.alechilles.alecstamework.items.ManagedCoopVanillaImportInspectionService.ImportInspection;
import com.alechilles.alecstamework.items.ManagedCoopVanillaImportInspectionService.InspectionStatus;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportDispositionWriter;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.NpcIdentityRepository;
import com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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
    private final VanillaCoopImportAdapter adapter;
    private final VanillaCoopImportAuditPreparer preparer;
    private final ManagedCoopVanillaImportInspectionService inspections;
    private final ManagedCoopImportControl importControl;
    private final ManagedCoopVanillaProjectionAdoptionGateway projections;
    private final ManagedCoopImportWriteCoordinator writes;
    private final ManagedCoopImportSessionProcessor sessionProcessor;

    public ManagedCoopVanillaImportService(
            @Nonnull ManagedCoopResidentRepository residents,
            @Nonnull CoopLifecycleOperationRepository lifecycle,
            @Nonnull ManagedCoopImportRepository imports,
            @Nonnull NpcProfileRepository profiles,
            @Nonnull ManagedCoopCompositeIndexRefreshService compositeIndexes) {
        this(residents, lifecycle, imports, profiles, compositeIndexes,
                ManagedCoopImportControl.shared());
    }

    public ManagedCoopVanillaImportService(
            @Nonnull ManagedCoopResidentRepository residents,
            @Nonnull CoopLifecycleOperationRepository lifecycle,
            @Nonnull ManagedCoopImportRepository imports,
            @Nonnull NpcProfileRepository profiles,
            @Nonnull ManagedCoopCompositeIndexRefreshService compositeIndexes,
            @Nonnull ManagedCoopImportControl importControl) {
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
                new VanillaCoopImportAbsenceVerifier(),
                importControl,
                ManagedCoopVanillaProjectionAdoptionGateway.unavailable()
        );
    }

    /** Production constructor enabling exact in-place adoption of deployed vanilla residents. */
    public ManagedCoopVanillaImportService(
            @Nonnull ManagedCoopResidentRepository residents,
            @Nonnull CoopLifecycleOperationRepository lifecycle,
            @Nonnull ManagedCoopImportRepository imports,
            @Nonnull NpcProfileRepository profiles,
            @Nonnull ManagedCoopCompositeIndexRefreshService compositeIndexes,
            @Nonnull CoopResidentStateSnapshotService snapshots,
            @Nonnull ComponentType<EntityStore, TameworkProjectionIdentityComponent> markerType,
            @Nonnull NpcIdentityRepository identities,
            @Nonnull LoadedNpcIdentityIndex loadedIdentities) {
        this(
                residents, lifecycle, imports, profiles, compositeIndexes,
                ManagedCoopImportControl.shared(), snapshots, markerType,
                identities, loadedIdentities);
    }

    /** Production constructor with an explicit process-local import approval controller. */
    public ManagedCoopVanillaImportService(
            @Nonnull ManagedCoopResidentRepository residents,
            @Nonnull CoopLifecycleOperationRepository lifecycle,
            @Nonnull ManagedCoopImportRepository imports,
            @Nonnull NpcProfileRepository profiles,
            @Nonnull ManagedCoopCompositeIndexRefreshService compositeIndexes,
            @Nonnull ManagedCoopImportControl importControl,
            @Nonnull CoopResidentStateSnapshotService snapshots,
            @Nonnull ComponentType<EntityStore, TameworkProjectionIdentityComponent> markerType,
            @Nonnull NpcIdentityRepository identities,
            @Nonnull LoadedNpcIdentityIndex loadedIdentities) {
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
                new VanillaCoopImportAbsenceVerifier(),
                importControl,
                new HytaleManagedCoopVanillaProjectionAdoptionGateway(
                        snapshots, markerType, identities, loadedIdentities));
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
        this(residents, lifecycle, imports, profiles, compositeIndexes,
                dispositionWriter, adapter, preparer, evidenceCodec, neutralizer,
                absenceVerifier, ManagedCoopImportControl.shared(),
                ManagedCoopVanillaProjectionAdoptionGateway.unavailable());
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
            @Nonnull VanillaCoopImportAbsenceVerifier absenceVerifier,
            @Nonnull ManagedCoopImportControl importControl) {
        this(residents, lifecycle, imports, profiles, compositeIndexes,
                dispositionWriter, adapter, preparer, evidenceCodec, neutralizer,
                absenceVerifier, importControl,
                ManagedCoopVanillaProjectionAdoptionGateway.unavailable());
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
            @Nonnull VanillaCoopImportAbsenceVerifier absenceVerifier,
            @Nonnull ManagedCoopImportControl importControl,
            @Nonnull ManagedCoopVanillaProjectionAdoptionGateway projections) {
        this.residents = Objects.requireNonNull(residents, "residents");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.imports = Objects.requireNonNull(imports, "imports");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.preparer = Objects.requireNonNull(preparer, "preparer");
        this.importControl = Objects.requireNonNull(importControl, "importControl");
        this.projections = Objects.requireNonNull(projections, "projections");
        this.inspections = new ManagedCoopVanillaImportInspectionService(
                residents, lifecycle, imports, profiles, adapter, preparer, evidenceCodec,
                projections);
        this.writes = new ManagedCoopImportWriteCoordinator(
                Objects.requireNonNull(compositeIndexes, "compositeIndexes"));
        this.sessionProcessor = new ManagedCoopImportSessionProcessor(
                residents, lifecycle, imports, dispositionWriter, adapter, evidenceCodec,
                neutralizer, absenceVerifier, projections, importControl, writes);
    }

    /** Returns and publishes a fresh read-only report without submitting import writes. */
    @Nonnull
    public ImportInspection inspect(@Nonnull ImportContext context,
                                    @Nonnull CoopBlock coop,
                                    long nowMs) {
        ImportInspection inspection = inspections.inspect(context, coop, nowMs);
        importControl.observe(inspection);
        return inspection;
    }

    /** Runs one fail-closed import step for an exact, currently loaded managed coop context. */
    @Nonnull
    public SweepResult sweep(@Nonnull ImportContext context,
                             @Nonnull CoopBlock coop,
                             long nowMs) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(coop, "coop");
        if (!context.enabledManaged()) {
            importControl.remove(context.authorityKey());
            return result(Status.NOT_MANAGED, "managed_authority_disabled", false);
        }
        if (nowMs == 0L) {
            return blocked("non_zero_signed_import_timestamp_required");
        }
        SweepResult pendingResult = writes.settlePending(context.authorityKey().authorityId());
        if (pendingResult != null) {
            return pendingResult;
        }
        ImportInspection inspection = inspect(context, coop, nowMs);
        if (inspection.status() == InspectionStatus.FAILED
                || inspection.status() == InspectionStatus.BLOCKED
                || inspection.status() == InspectionStatus.DISABLED) {
            return blocked(inspection.detail());
        }
        String approvedFingerprint = null;
        if (inspection.approvalRequired()) {
            approvedFingerprint = inspection.auditFingerprint();
            AuthorizationStatus authorization = importControl.authorize(
                    context.authorityKey(), approvedFingerprint);
            if (authorization != AuthorizationStatus.APPROVED) {
                return blocked("managed_coop_import_approval_"
                        + authorization.name().toLowerCase(Locale.ROOT)
                        + ":" + approvedFingerprint);
            }
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
            return writes.queue(context, "register_" + initial.name().toLowerCase(Locale.ROOT),
                    residents.registerAuthority(
                            context.authorityKey(), context.coopId(), initial, nowMs));
        }
        SweepResult result = advance(
                context, coop, audit, authorityRead.value(), approvedFingerprint, nowMs);
        if (result.status() == Status.COMPLETE_MANAGED
                || result.status() == Status.COMPLETE_CONFLICT) {
            importControl.cancel(context.authorityKey());
        }
        return result;
    }

    private SweepResult advance(ImportContext context,
                                 CoopBlock coop,
                                 VanillaCoopImportAdapter.AuditResult audit,
                                 AuthorityRecord authority,
                                 @Nullable String approvedFingerprint,
                                 long nowMs) {
        return switch (authority.state()) {
            case TWORK_MANAGED -> startImportIfNeeded(context, audit, nowMs);
            case VANILLA_DISCOVERED -> beginSession(
                    context, audit, authority.importVersion(), approvedFingerprint, nowMs);
            case IMPORTING_TO_TWORK -> sessionProcessor.advance(context, coop, audit, nowMs);
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
        return writes.queue(context, "mark_vanilla_discovered",
                residents.transitionAuthority(
                        context.authorityKey(), AuthorityState.TWORK_MANAGED,
                        AuthorityState.VANILLA_DISCOVERED, null, nowMs));
    }

    private SweepResult beginSession(ImportContext context,
                                     VanillaCoopImportAdapter.AuditResult audit,
                                     int currentImportVersion,
                                     @Nullable String approvedFingerprint,
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
            int importGeneration = ManagedCoopImportGeneration.next(currentImportVersion);
            prepared = preparer.prepare(new VanillaCoopImportAuditPreparer.Request(
                    context.authorityKey(), context.coopId(), context.maximumResidents(),
                    audit, residentRead.value(), operationRead.value(),
                    profiles::resolveProfileId, projections::inspect,
                    importGeneration, nowMs));
        } catch (RuntimeException exception) {
            return blocked("import_audit_preparation_failed:" + detail(exception));
        }
        String currentFingerprint = prepared.beginRequest().envelope().auditFingerprint();
        if (approvedFingerprint == null
                || !approvedFingerprint.equals(currentFingerprint)) {
            importControl.cancel(context.authorityKey());
            return blocked("approved_import_fingerprint_changed:" + currentFingerprint);
        }
        return writes.queue(context, "begin_import_session",
                imports.beginSession(prepared.beginRequest()));
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

    private String detail(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

}
