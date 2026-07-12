package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopVanillaImportService.ImportContext;
import com.alechilles.alecstamework.items.VanillaCoopImportEvidenceCodec.PlannedDisposition;
import com.alechilles.alecstamework.items.VanillaCoopImportEvidenceCodec.SourcePlan;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.DispositionKind;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.NeutralizationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SessionRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SourceEvidence;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SourceRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Produces a portable, read-only import report for one exact managed coop.
 *
 * <p>Live Hytale objects are consumed synchronously and never escape in {@link ImportInspection}.
 * The same deterministic preparer used by durable import supplies the confirmation fingerprint, so
 * an operator can approve exactly the plan the runtime will later attempt to persist.</p>
 */
public final class ManagedCoopVanillaImportInspectionService {
    public enum InspectionStatus {
        NOT_MANAGED,
        CLEAR,
        APPROVAL_REQUIRED,
        ACTIVE_IMPORT_APPROVAL_REQUIRED,
        CONFLICT,
        DISABLED,
        BLOCKED,
        FAILED
    }

    public record SourceSummary(@Nonnull String sourceId,
                                @Nonnull String sourceFingerprint,
                                @Nonnull PlannedDisposition plannedDisposition,
                                @Nullable DispositionKind persistedDisposition,
                                @Nonnull NeutralizationState neutralizationState,
                                @Nullable String profileId,
                                @Nullable Integer targetSlot,
                                boolean deployedToWorld,
                                @Nullable String conflictKind,
                                @Nonnull String unavailableFieldsJson,
                                boolean overflow) {
        /** Preserves callers that construct summaries from pre-overflow diagnostic data. */
        public SourceSummary(String sourceId,
                             String sourceFingerprint,
                             PlannedDisposition plannedDisposition,
                             DispositionKind persistedDisposition,
                             NeutralizationState neutralizationState,
                             String profileId,
                             Integer targetSlot,
                             boolean deployedToWorld,
                             String conflictKind,
                             String unavailableFieldsJson) {
            this(sourceId, sourceFingerprint, plannedDisposition, persistedDisposition,
                    neutralizationState, profileId, targetSlot, deployedToWorld, conflictKind,
                    unavailableFieldsJson, false);
        }

        public SourceSummary {
            sourceId = requireText(sourceId, "sourceId");
            sourceFingerprint = requireText(sourceFingerprint, "sourceFingerprint");
            Objects.requireNonNull(plannedDisposition, "plannedDisposition");
            Objects.requireNonNull(neutralizationState, "neutralizationState");
            unavailableFieldsJson = requireText(unavailableFieldsJson, "unavailableFieldsJson");
        }
    }

    public record ImportInspection(@Nonnull ManagedCoopAuthorityKey authorityKey,
                                   @Nonnull String coopId,
                                   @Nonnull InspectionStatus status,
                                   @Nullable AuthorityState authorityState,
                                   @Nullable String auditFingerprint,
                                   @Nullable String sessionId,
                                   int importGeneration,
                                   @Nonnull List<SourceSummary> sources,
                                   boolean approvalRequired,
                                   @Nullable String detail) {
        /** Preserves callers that construct diagnostics from pre-generation report data. */
        public ImportInspection(ManagedCoopAuthorityKey authorityKey,
                                String coopId,
                                InspectionStatus status,
                                AuthorityState authorityState,
                                String auditFingerprint,
                                String sessionId,
                                List<SourceSummary> sources,
                                boolean approvalRequired,
                                String detail) {
            this(authorityKey, coopId, status, authorityState, auditFingerprint, sessionId,
                    approvalRequired ? 1 : 0, sources, approvalRequired, detail);
        }

        public ImportInspection {
            Objects.requireNonNull(authorityKey, "authorityKey");
            coopId = requireText(coopId, "coopId").toLowerCase(Locale.ROOT);
            Objects.requireNonNull(status, "status");
            sources = List.copyOf(sources);
            if (approvalRequired && (auditFingerprint == null || sessionId == null)) {
                throw new IllegalArgumentException(
                        "approvable inspections require an audit fingerprint and session ID");
            }
            if (approvalRequired && importGeneration < 1) {
                throw new IllegalArgumentException(
                        "approvable inspections require a positive import generation");
            }
            if (!approvalRequired && importGeneration < 0) {
                throw new IllegalArgumentException("import generation must not be negative");
            }
        }

        public int sourceCount() {
            return sources.size();
        }

        public long count(@Nonnull PlannedDisposition disposition) {
            Objects.requireNonNull(disposition, "disposition");
            return sources.stream()
                    .filter(source -> source.plannedDisposition() == disposition)
                    .count();
        }

        public long overflowCount() {
            return sources.stream().filter(SourceSummary::overflow).count();
        }

        @Nonnull
        public List<String> conflictKinds() {
            return sources.stream()
                    .map(SourceSummary::conflictKind)
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted()
                    .toList();
        }
    }

    private final ManagedCoopResidentRepository residents;
    private final CoopLifecycleOperationRepository lifecycle;
    private final ManagedCoopImportRepository imports;
    private final NpcProfileRepository profiles;
    private final VanillaCoopImportAdapter adapter;
    private final VanillaCoopImportAuditPreparer preparer;
    private final VanillaCoopImportEvidenceCodec evidenceCodec;
    private final ManagedCoopVanillaProjectionAdoptionGateway projections;

    public ManagedCoopVanillaImportInspectionService(
            @Nonnull ManagedCoopResidentRepository residents,
            @Nonnull CoopLifecycleOperationRepository lifecycle,
            @Nonnull ManagedCoopImportRepository imports,
            @Nonnull NpcProfileRepository profiles) {
        this(residents, lifecycle, imports, profiles,
                new VanillaCoopImportAdapter(),
                new VanillaCoopImportAuditPreparer(),
                new VanillaCoopImportEvidenceCodec(),
                ManagedCoopVanillaProjectionAdoptionGateway.unavailable());
    }

    ManagedCoopVanillaImportInspectionService(
            @Nonnull ManagedCoopResidentRepository residents,
            @Nonnull CoopLifecycleOperationRepository lifecycle,
            @Nonnull ManagedCoopImportRepository imports,
            @Nonnull NpcProfileRepository profiles,
            @Nonnull VanillaCoopImportAdapter adapter,
            @Nonnull VanillaCoopImportAuditPreparer preparer,
            @Nonnull VanillaCoopImportEvidenceCodec evidenceCodec,
            @Nonnull ManagedCoopVanillaProjectionAdoptionGateway projections) {
        this.residents = Objects.requireNonNull(residents, "residents");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.imports = Objects.requireNonNull(imports, "imports");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.preparer = Objects.requireNonNull(preparer, "preparer");
        this.evidenceCodec = Objects.requireNonNull(evidenceCodec, "evidenceCodec");
        this.projections = Objects.requireNonNull(projections, "projections");
    }

    /** Inspects one live coop without submitting persistence writes or changing vanilla state. */
    @Nonnull
    public ImportInspection inspect(@Nonnull ImportContext context,
                                    @Nonnull CoopBlock coop,
                                    long nowMs) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(coop, "coop");
        if (!context.enabledManaged()) {
            return report(context, InspectionStatus.NOT_MANAGED, null,
                    null, null, List.of(), false, "managed_authority_disabled");
        }
        if (nowMs == 0L) {
            return failed(context, null, "non_zero_signed_import_timestamp_required");
        }
        VanillaCoopImportAdapter.AuditResult audit = adapter.auditForImport(coop);
        if (!audit.readable()
                || !VanillaCoopImportAdapter.SUPPORTED_LAYOUT_ID.equals(audit.layoutId())) {
            return failed(context, null,
                    "unsupported_vanilla_coop_layout:" + audit.detail());
        }
        ManagedCoopReadResult<AuthorityRecord> authorityRead =
                residents.loadAuthority(context.authorityKey(), context.coopId());
        if (authorityRead.status() == ManagedCoopReadResult.Status.FAILED) {
            return failed(context, null, readFailure("authority_read_failed", authorityRead));
        }
        if (authorityRead.status() == ManagedCoopReadResult.Status.NOT_FOUND) {
            return audit.residents().isEmpty()
                    ? report(context, InspectionStatus.CLEAR, null,
                    null, null, List.of(), false, "unregistered_empty_managed_coop")
                    : prepareCurrent(context, audit, null, 0, nowMs);
        }
        AuthorityRecord authority = authorityRead.value();
        return switch (authority.state()) {
            case TWORK_MANAGED -> audit.residents().isEmpty()
                    ? report(context, InspectionStatus.CLEAR, authority.state(),
                    null, null, List.of(), false, null)
                    : prepareCurrent(context, audit, authority.state(),
                    authority.importVersion(), nowMs);
            case VANILLA_DISCOVERED -> prepareCurrent(
                    context, audit, authority.state(), authority.importVersion(), nowMs);
            case IMPORTING_TO_TWORK -> inspectActiveSession(context, authority);
            case CONFLICT -> report(context, InspectionStatus.CONFLICT, authority.state(),
                    null, null, List.of(), false, "managed_coop_import_quarantined");
            case DISABLED -> report(context, InspectionStatus.DISABLED, authority.state(),
                    null, null, List.of(), false,
                    "managed_coop_authority_persisted_disabled");
        };
    }

    private ImportInspection prepareCurrent(ImportContext context,
                                             VanillaCoopImportAdapter.AuditResult audit,
                                             @Nullable AuthorityState authorityState,
                                             int currentImportVersion,
                                             long nowMs) {
        final int importGeneration;
        try {
            importGeneration = ManagedCoopImportGeneration.next(currentImportVersion);
        } catch (RuntimeException exception) {
            return failed(context, authorityState,
                    "import_generation_resolution_failed:" + detail(exception));
        }
        ManagedCoopReadResult<List<ResidentRecord>> residentRead =
                residents.loadAllActiveResidents();
        ManagedCoopReadResult<List<OperationRecord>> operationRead =
                lifecycle.loadAllActiveOperations();
        if (residentRead.status() != ManagedCoopReadResult.Status.LOADED
                || residentRead.value() == null) {
            return failed(context, authorityState,
                    readFailure("resident_read_failed", residentRead));
        }
        if (operationRead.status() != ManagedCoopReadResult.Status.LOADED
                || operationRead.value() == null) {
            return failed(context, authorityState,
                    readFailure("lifecycle_read_failed", operationRead));
        }
        boolean exactActiveOperation = operationRead.value().stream().anyMatch(operation ->
                operation.authorityKey().equals(context.authorityKey())
                        && operation.coopId().equalsIgnoreCase(context.coopId()));
        if (exactActiveOperation) {
            return report(context, InspectionStatus.BLOCKED, authorityState,
                    null, null, List.of(), false,
                    "active_lifecycle_operation_prevents_import_audit");
        }
        try {
            ManagedCoopImportRepository.BeginSessionRequest request = preparer.prepare(
                    new VanillaCoopImportAuditPreparer.Request(
                             context.authorityKey(), context.coopId(), context.maximumResidents(),
                             audit, residentRead.value(), operationRead.value(),
                             profiles::resolveProfileId, projections::inspect,
                             importGeneration, nowMs)).beginRequest();
            List<SourceSummary> summaries = summaries(request.sources());
            return report(context, InspectionStatus.APPROVAL_REQUIRED, authorityState,
                    request.envelope().auditFingerprint(), request.envelope().sessionId(),
                    importGeneration, summaries, true,
                    "explicit_import_confirmation_required");
        } catch (RuntimeException exception) {
            return failed(context, authorityState,
                    "import_audit_preparation_failed:" + detail(exception));
        }
    }

    private ImportInspection inspectActiveSession(ImportContext context,
                                                   AuthorityRecord authority) {
        AuthorityState authorityState = authority.state();
        ManagedCoopReadResult<SessionRecord> sessionRead =
                imports.loadActiveSession(context.authorityKey(), context.coopId());
        if (sessionRead.status() != ManagedCoopReadResult.Status.LOADED
                || sessionRead.value() == null) {
            return failed(context, authorityState,
                    readFailure("active_import_session_missing", sessionRead));
        }
        SessionRecord session = sessionRead.value();
        ManagedCoopReadResult<List<SourceRecord>> sourceRead =
                imports.loadSources(session.envelope().sessionId());
        if (sourceRead.status() != ManagedCoopReadResult.Status.LOADED
                || sourceRead.value() == null
                || sourceRead.value().size() != session.sourceCount()) {
            return failed(context, authorityState,
                    readFailure("import_sources_not_loaded", sourceRead));
        }
        try {
            int generation = ManagedCoopImportGeneration.persistedOrLegacy(
                    session.envelope(), ManagedCoopImportGeneration.next(authority.importVersion()));
            return report(context, InspectionStatus.ACTIVE_IMPORT_APPROVAL_REQUIRED,
                    authorityState, session.envelope().auditFingerprint(),
                    session.envelope().sessionId(), generation,
                    summariesFromRecords(sourceRead.value()), true,
                    "explicit_import_resume_confirmation_required");
        } catch (RuntimeException exception) {
            return failed(context, authorityState,
                    "persisted_source_plan_invalid:" + detail(exception));
        }
    }

    private List<SourceSummary> summaries(List<SourceEvidence> sources) {
        ArrayList<SourceSummary> result = new ArrayList<>(sources.size());
        for (SourceEvidence source : sources) {
            SourcePlan plan = evidenceCodec.decodeSourcePlan(source);
            result.add(summary(source, plan, null,
                    plan.disposition() == PlannedDisposition.QUARANTINED
                            ? NeutralizationState.NOT_REQUIRED
                            : NeutralizationState.NOT_AUTHORIZED));
        }
        return ordered(result);
    }

    private List<SourceSummary> summariesFromRecords(List<SourceRecord> sources) {
        ArrayList<SourceSummary> result = new ArrayList<>(sources.size());
        for (SourceRecord source : sources) {
            SourcePlan plan = evidenceCodec.decodeSourcePlan(source.evidence());
            result.add(summary(source.evidence(), plan,
                    source.disposition(), source.neutralizationState()));
        }
        return ordered(result);
    }

    private SourceSummary summary(SourceEvidence source,
                                  SourcePlan plan,
                                  @Nullable DispositionKind disposition,
                                  NeutralizationState neutralizationState) {
        return new SourceSummary(
                source.sourceId(), source.sourceFingerprint(), plan.disposition(), disposition,
                neutralizationState, plan.profileId(), plan.targetSlot(),
                source.deployedToWorld(), plan.conflictKind(), source.unavailableFieldsJson(),
                plan.overflow());
    }

    private List<SourceSummary> ordered(List<SourceSummary> summaries) {
        summaries.sort(Comparator.comparing(SourceSummary::sourceId));
        return List.copyOf(summaries);
    }

    private ImportInspection failed(ImportContext context,
                                    @Nullable AuthorityState authorityState,
                                    String detail) {
        return report(context, InspectionStatus.FAILED, authorityState,
                null, null, List.of(), false, detail);
    }

    private ImportInspection report(ImportContext context,
                                    InspectionStatus status,
                                    @Nullable AuthorityState authorityState,
                                    @Nullable String auditFingerprint,
                                    @Nullable String sessionId,
                                    List<SourceSummary> sources,
                                    boolean approvalRequired,
                                    @Nullable String detail) {
        return report(context, status, authorityState, auditFingerprint, sessionId,
                0, sources, approvalRequired, detail);
    }

    private ImportInspection report(ImportContext context,
                                    InspectionStatus status,
                                    @Nullable AuthorityState authorityState,
                                    @Nullable String auditFingerprint,
                                    @Nullable String sessionId,
                                    int importGeneration,
                                    List<SourceSummary> sources,
                                    boolean approvalRequired,
                                    @Nullable String detail) {
        return new ImportInspection(
                context.authorityKey(), context.coopId(), status, authorityState,
                auditFingerprint, sessionId, importGeneration,
                sources, approvalRequired, detail);
    }

    private String readFailure(String prefix, ManagedCoopReadResult<?> result) {
        if (result.failure() == null) {
            return prefix + ":" + result.status().name().toLowerCase(Locale.ROOT);
        }
        return prefix + ":" + result.failure().kind().name().toLowerCase(Locale.ROOT)
                + ":" + result.failure().detail();
    }

    private static String detail(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
