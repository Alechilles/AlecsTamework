package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.VanillaCoopImportEvidenceCodec.PlannedDisposition;
import com.alechilles.alecstamework.items.VanillaCoopImportEvidenceCodec.SourcePlan;
import com.alechilles.alecstamework.items.VanillaCoopImportEvidenceCodec.StableSource;
import com.alechilles.alecstamework.items.ManagedCoopVanillaProjectionAdoptionGateway.InspectionRequest;
import com.alechilles.alecstamework.items.ManagedCoopVanillaProjectionAdoptionGateway.InspectionResult;
import com.alechilles.alecstamework.items.ManagedCoopVanillaProjectionAdoptionGateway.InspectionStatus;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.BeginSessionRequest;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SourceEvidence;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Builds one immutable, deterministic import session before any vanilla source is changed. */
public final class VanillaCoopImportAuditPreparer {
    @FunctionalInterface
    public interface ProfileResolver {
        @Nullable String resolve(@Nonnull UUID npcUuid);
    }

    /** Synchronous owning-world evidence source used only for deployed vanilla residents. */
    @FunctionalInterface
    public interface DeployedProjectionEvidenceResolver {
        @Nonnull InspectionResult inspect(@Nonnull InspectionRequest request);
    }

    public record Request(@Nonnull ManagedCoopAuthorityKey authorityKey,
                          @Nonnull String coopId,
                          int maximumResidents,
                          @Nonnull VanillaCoopImportAdapter.AuditResult audit,
                          @Nonnull List<ResidentRecord> managedResidents,
                          @Nonnull List<OperationRecord> activeOperations,
                          @Nonnull ProfileResolver profileResolver,
                          @Nonnull DeployedProjectionEvidenceResolver deployedProjectionEvidence,
                          int importGeneration,
                          long auditedAtMs) {
        public Request(ManagedCoopAuthorityKey authorityKey,
                       String coopId,
                       int maximumResidents,
                       VanillaCoopImportAdapter.AuditResult audit,
                       List<ResidentRecord> managedResidents,
                       List<OperationRecord> activeOperations,
                       ProfileResolver profileResolver,
                       long auditedAtMs) {
            this(authorityKey, coopId, maximumResidents, audit, managedResidents,
                    activeOperations, profileResolver, 1, auditedAtMs);
        }

        public Request(ManagedCoopAuthorityKey authorityKey,
                       String coopId,
                       int maximumResidents,
                       VanillaCoopImportAdapter.AuditResult audit,
                       List<ResidentRecord> managedResidents,
                       List<OperationRecord> activeOperations,
                       ProfileResolver profileResolver,
                       int importGeneration,
                       long auditedAtMs) {
            this(
                    authorityKey,
                    coopId,
                    maximumResidents,
                    audit,
                    managedResidents,
                    activeOperations,
                    profileResolver,
                    ignored -> InspectionResult.conflict(
                            "deployed_source_requires_live_projection_adoption", null),
                    importGeneration,
                    auditedAtMs);
        }

        /** Preserves the pre-generation constructor for callers preparing a first import. */
        public Request(ManagedCoopAuthorityKey authorityKey,
                       String coopId,
                       int maximumResidents,
                       VanillaCoopImportAdapter.AuditResult audit,
                       List<ResidentRecord> managedResidents,
                       List<OperationRecord> activeOperations,
                       ProfileResolver profileResolver,
                       DeployedProjectionEvidenceResolver deployedProjectionEvidence,
                       long auditedAtMs) {
            this(authorityKey, coopId, maximumResidents, audit, managedResidents,
                    activeOperations, profileResolver, deployedProjectionEvidence, 1, auditedAtMs);
        }

        public Request {
            Objects.requireNonNull(authorityKey, "authorityKey");
            coopId = requireText(coopId, "coopId").toLowerCase(Locale.ROOT);
            if (maximumResidents < 0) {
                throw new IllegalArgumentException("maximumResidents must not be negative");
            }
            Objects.requireNonNull(audit, "audit");
            managedResidents = List.copyOf(managedResidents);
            activeOperations = List.copyOf(activeOperations);
            Objects.requireNonNull(profileResolver, "profileResolver");
            Objects.requireNonNull(deployedProjectionEvidence, "deployedProjectionEvidence");
            if (importGeneration < 1) {
                throw new IllegalArgumentException("importGeneration must be positive");
            }
            if (auditedAtMs == 0L) {
                throw new IllegalArgumentException("auditedAtMs must use a non-zero signed value");
            }
        }
    }

    public record PreparedAudit(@Nonnull BeginSessionRequest beginRequest) {
        public PreparedAudit {
            Objects.requireNonNull(beginRequest, "beginRequest");
        }
    }

    private final VanillaCoopImportEvidenceCodec evidenceCodec;
    private final VanillaResidentImportPlanner planner;
    private final VanillaCoopImportEnvelopeFactory envelopeFactory;

    public VanillaCoopImportAuditPreparer() {
        this(new VanillaCoopImportEvidenceCodec(), new VanillaResidentImportPlanner());
    }

    VanillaCoopImportAuditPreparer(@Nonnull VanillaCoopImportEvidenceCodec evidenceCodec,
                                   @Nonnull VanillaResidentImportPlanner planner) {
        this.evidenceCodec = Objects.requireNonNull(evidenceCodec, "evidenceCodec");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.envelopeFactory = new VanillaCoopImportEnvelopeFactory(evidenceCodec);
    }

    /** Prepares all source decisions and portable snapshots as a single immutable audit. */
    @Nonnull
    public PreparedAudit prepare(@Nonnull Request request) {
        Objects.requireNonNull(request, "request");
        if (!request.audit().readable() || request.audit().coop() == null
                || !VanillaCoopImportAdapter.SUPPORTED_LAYOUT_ID.equals(request.audit().layoutId())) {
            throw new IllegalArgumentException("supported vanilla coop audit is required");
        }
        List<Candidate> candidates = groupSources(request);
        Map<String, VanillaResidentImportPlanner.Decision> decisions = plan(request, candidates);
        ArrayList<SourceEvidence> sources = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            sources.add(sourceEvidence(request, candidate, decisions.get(candidate.sourceFingerprint())));
        }
        sources.sort(Comparator.comparingInt(SourceEvidence::sourceOrder)
                .thenComparing(SourceEvidence::sourceFingerprint));

        return new PreparedAudit(new BeginSessionRequest(
                envelopeFactory.create(
                        request.authorityKey(), request.coopId(), request.maximumResidents(),
                        request.audit(), sources, request.importGeneration(), request.auditedAtMs()),
                sources));
    }

    private List<Candidate> groupSources(Request request) {
        LinkedHashMap<String, ArrayList<StableSource>> byFingerprint = new LinkedHashMap<>();
        for (VanillaCoopImportAdapter.ResidentEvidence resident : request.audit().residents()) {
            StableSource source = evidenceCodec.copyStableSource(resident);
            byFingerprint.computeIfAbsent(source.fingerprint(), ignored -> new ArrayList<>()).add(source);
        }
        ArrayList<Candidate> candidates = new ArrayList<>();
        for (ArrayList<StableSource> group : byFingerprint.values()) {
            group.sort(Comparator.comparingInt(StableSource::sourceOrder));
            StableSource first = group.getFirst();
            String payload = group.size() == 1
                    ? first.payload() : evidenceCodec.groupedPayload(first.payload(), group.size());
            candidates.add(new Candidate(
                    first,
                    group.size(),
                    payload,
                    VanillaCoopImportEvidenceCodec.sha256(payload),
                    selectedProfile(request, first),
                    residentUuid(request, first)
            ));
        }
        candidates.sort(Comparator.comparingInt(candidate -> candidate.source().sourceOrder()));
        return List.copyOf(candidates);
    }

    private Map<String, VanillaResidentImportPlanner.Decision> plan(
            Request request,
            List<Candidate> candidates) {
        VanillaResidentImportPlanner.CoopAuthority authority =
                new VanillaResidentImportPlanner.CoopAuthority(
                        request.authorityKey().authorityId(),
                        request.authorityKey().worldName(),
                        request.coopId(),
                        request.authorityKey().x(),
                        request.authorityKey().y(),
                        request.authorityKey().z(),
                        request.maximumResidents()
                );
        ArrayList<VanillaResidentImportPlanner.ManagedResidentEvidence> managed = new ArrayList<>();
        for (ResidentRecord resident : request.managedResidents()) {
            if (!resident.authorityKey().equals(request.authorityKey())
                    || !resident.coopId().equalsIgnoreCase(request.coopId())) {
                continue;
            }
            managed.add(new VanillaResidentImportPlanner.ManagedResidentEvidence(
                    resident.residentId(),
                    resident.authorityKey().authorityId(),
                    resident.coopId(),
                    resident.residentSlot(),
                    resident.profileId(),
                    resident.residentUuid(),
                    resident.sourceNpcUuid(),
                    resident.deployedNpcUuid()
            ));
        }
        ArrayList<VanillaResidentImportPlanner.VanillaResidentEvidence> vanilla = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate.multiplicity() != 1) {
                continue;
            }
            StableSource source = candidate.source();
            vanilla.add(new VanillaResidentImportPlanner.VanillaResidentEvidence(
                    candidate.sourceFingerprint(),
                    candidate.sourcePayload(),
                    source.sourceSlot(),
                    source.sourceOrder(),
                    source.persistentUuid(),
                    candidate.profileId(),
                    source.roleId(),
                    source.displayName()
            ));
        }
        VanillaResidentImportPlanner.ImportPlan plan = planner.plan(
                new VanillaResidentImportPlanner.ImportRequest(authority, managed, vanilla));
        LinkedHashMap<String, VanillaResidentImportPlanner.Decision> result = new LinkedHashMap<>();
        for (VanillaResidentImportPlanner.Decision decision : plan.decisions()) {
            result.put(decision.source().sourceFingerprint(), decision);
        }
        return Map.copyOf(result);
    }

    private SourceEvidence sourceEvidence(Request request,
                                          Candidate candidate,
                                          @Nullable VanillaResidentImportPlanner.Decision decision) {
        InspectionResult projection = deployedProjectionEvidence(request, candidate, decision);
        SourcePlan plan = durablePlan(request, candidate, decision, projection);
        int snapshotSlot = plan.targetSlot() == null
                ? candidate.source().sourceSlot() : plan.targetSlot();
        String snapshotRole = plan.roleId() == null ? "unsupported_vanilla_resident" : plan.roleId();
        UUID snapshotUuid = plan.residentUuid() == null
                ? candidate.residentUuid() : plan.residentUuid();
        boolean useLiveSnapshot = candidate.source().deployedToWorld()
                && plan.disposition() != PlannedDisposition.QUARANTINED
                && projection != null && projection.verified();
        String snapshotJson = useLiveSnapshot
                ? projection.snapshotJson()
                : evidenceCodec.managedSnapshot(
                        snapshotUuid,
                        request.coopId(),
                        snapshotSlot,
                        snapshotRole,
                        request.auditedAtMs());
        String snapshotHash = useLiveSnapshot
                ? projection.snapshotHash()
                : ManagedCoopCaptureClaimValidator.snapshotSha256(snapshotJson);
        int snapshotVersion = useLiveSnapshot
                ? projection.snapshotVersion()
                : Integer.parseInt(CoopResidentStateSnapshotCodec.CURRENT_VERSION);
        String sourceEnvelope = evidenceCodec.encodeSourceEnvelope(
                candidate.sourceFingerprint(),
                plan,
                candidate.source().sourceSlot(),
                candidate.source().sourceOrder()
        );
        JsonObject locator = new JsonObject();
        locator.addProperty("originalSlot", candidate.source().sourceSlot());
        locator.addProperty("originalOrder", candidate.source().sourceOrder());
        if (candidate.source().persistentUuid() != null) {
            locator.addProperty("persistentUuid", candidate.source().persistentUuid().toString());
        }
        String locatorJson = locator.toString();
        String sourceId = "managed-coop-import-source:" + VanillaCoopImportEvidenceCodec.sha256(
                token(request.authorityKey().authorityId())
                        + token(Integer.toString(request.importGeneration()))
                        + token(candidate.sourceFingerprint()));
        return new SourceEvidence(
                sourceId,
                candidate.sourceFingerprint(),
                sourceEnvelope,
                VanillaCoopImportEvidenceCodec.sha256(sourceEnvelope),
                candidate.sourcePayload(),
                VanillaCoopImportEvidenceCodec.sha256(candidate.sourcePayload()),
                locatorJson,
                VanillaCoopImportEvidenceCodec.sha256(locatorJson),
                candidate.source().sourceSlot(),
                candidate.source().sourceOrder(),
                candidate.source().metadataPresent(),
                candidate.source().persistentUuid() != null,
                candidate.source().persistentUuid(),
                candidate.source().deployedToWorld(),
                candidate.source().lastProduced(),
                plan.profileId(),
                candidate.source().roleId(),
                candidate.source().displayName(),
                snapshotJson,
                snapshotHash,
                snapshotVersion,
                evidenceCodec.unavailableFieldsJson(candidate.source().unavailableFields())
        );
    }

    @Nullable
    private InspectionResult deployedProjectionEvidence(
            Request request,
            Candidate candidate,
            @Nullable VanillaResidentImportPlanner.Decision decision) {
        StableSource source = candidate.source();
        if (!source.deployedToWorld() || candidate.multiplicity() != 1
                || !source.importSupported() || decision == null
                || decision.classification() == VanillaResidentImportPlanner.Classification.CONFLICT
                || decision.targetSlot() == null || source.persistentUuid() == null
                || source.roleId() == null) {
            return null;
        }
        InspectionResult result = request.deployedProjectionEvidence().inspect(
                new InspectionRequest(
                        request.authorityKey(),
                        request.coopId(),
                        candidate.sourceFingerprint(),
                        source.sourceSlot(),
                        source.sourceOrder(),
                        source.persistentUuid(),
                        candidate.profileId(),
                        source.roleId(),
                        decision.targetSlot()));
        if (result == null || result.status() == InspectionStatus.UNAVAILABLE) {
            throw new IllegalStateException("deployed_projection_evidence_unavailable:"
                    + (result == null || result.detail() == null
                    ? "missing_result" : result.detail()));
        }
        if (result.verified()
                && !ManagedCoopCaptureClaimValidator.snapshotSha256(result.snapshotJson())
                .equals(result.snapshotHash())) {
            throw new IllegalStateException("deployed_projection_snapshot_hash_mismatch");
        }
        return result;
    }

    private SourcePlan durablePlan(Request request,
                                   Candidate candidate,
                                   @Nullable VanillaResidentImportPlanner.Decision decision,
                                   @Nullable InspectionResult projection) {
        StableSource source = candidate.source();
        if (candidate.multiplicity() > 1) {
            return quarantine(candidate, "ambiguous_indistinguishable_sources");
        }
        if (!source.importSupported()) {
            return quarantine(candidate, "unsupported_vanilla_source_evidence");
        }
        if (decision == null) {
            return quarantine(candidate, "missing_import_plan_decision");
        }
        if (decision.classification() == VanillaResidentImportPlanner.Classification.CONFLICT) {
            return quarantine(candidate, reason("ambiguous", decision));
        }
        if (source.deployedToWorld()) {
            if (projection == null) {
                return quarantine(candidate, "deployed_projection_evidence_missing");
            }
            if (projection.status() == InspectionStatus.CONFLICT) {
                return quarantine(candidate, projection.conflictKind());
            }
            if (!projection.verified()) {
                throw new IllegalStateException("deployed_projection_evidence_not_verified");
            }
        }
        if (decision.classification() == VanillaResidentImportPlanner.Classification.MATCH_EXISTING) {
            ResidentRecord resident = request.managedResidents().stream()
                    .filter(value -> value.residentId().equals(decision.matchedResidentId()))
                    .findFirst().orElse(null);
            boolean expectedDeployed = source.deployedToWorld();
            boolean residentDeployed = resident != null && resident.state() == ResidentState.DEPLOYED;
            if (resident == null || expectedDeployed != residentDeployed
                    || resident.snapshotJson() == null || resident.snapshotHash() == null) {
                return quarantine(candidate, "managed_resident_state_mismatch");
            }
            String role = resident.roleId() == null ? source.roleId() : resident.roleId();
            if (role == null) {
                return quarantine(candidate, "matched_resident_role_missing");
            }
            return new SourcePlan(
                    PlannedDisposition.MATCHED,
                    source.payload(),
                    1,
                    resident.residentId(),
                    resident.profileId(),
                    resident.residentUuid(),
                    resident.residentSlot(),
                    role,
                    null
            );
        }
        boolean externallyOwned = request.managedResidents().stream().anyMatch(resident ->
                !resident.authorityKey().equals(request.authorityKey())
                        && (resident.profileId().equals(candidate.profileId())
                        || resident.residentUuid().equals(candidate.residentUuid())
                        || candidate.residentUuid().equals(resident.sourceNpcUuid())
                        || candidate.residentUuid().equals(resident.deployedNpcUuid())));
        if (externallyOwned) {
            return quarantine(candidate, "managed_identity_owned_by_other_coop");
        }
        int targetSlot = decision.targetSlot() == null ? -1 : decision.targetSlot();
        boolean lifecycleConflict = request.activeOperations().stream().anyMatch(operation ->
                operation.profileId().equals(candidate.profileId())
                        || (operation.authorityKey().equals(request.authorityKey())
                        && operation.residentSlot() == targetSlot));
        if (lifecycleConflict) {
            return quarantine(candidate, "active_lifecycle_identity_or_slot_conflict");
        }
        if (decision.targetSlot() == null || source.roleId() == null) {
            return quarantine(candidate, "import_target_or_role_missing");
        }
        return new SourcePlan(
                PlannedDisposition.IMPORTED,
                source.payload(),
                1,
                ManagedCoopCaptureClaimValidator.residentId(candidate.profileId()),
                candidate.profileId(),
                candidate.residentUuid(),
                decision.targetSlot(),
                source.roleId(),
                null,
                decision.classification() == VanillaResidentImportPlanner.Classification.OVERFLOW
        );
    }

    private SourcePlan quarantine(Candidate candidate, String conflictKind) {
        return new SourcePlan(
                PlannedDisposition.QUARANTINED,
                candidate.source().payload(),
                candidate.multiplicity(),
                null, null, null, null, null,
                conflictKind
        );
    }

    private String selectedProfile(Request request, StableSource source) {
        if (source.persistentUuid() != null) {
            String resolved = request.profileResolver().resolve(source.persistentUuid());
            if (resolved != null && !resolved.isBlank()) {
                return resolved.trim();
            }
        }
        return UUID.nameUUIDFromBytes(("tamework-import-profile|"
                + request.authorityKey().authorityId() + "|" + source.fingerprint())
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private UUID residentUuid(Request request, StableSource source) {
        if (source.persistentUuid() != null) {
            return source.persistentUuid();
        }
        return UUID.nameUUIDFromBytes(("tamework-import-resident|"
                + request.authorityKey().authorityId() + "|" + source.fingerprint())
                .getBytes(StandardCharsets.UTF_8));
    }

    private String reason(String prefix, VanillaResidentImportPlanner.Decision decision) {
        return prefix + "_" + decision.reasons().stream()
                .map(reason -> reason.name().toLowerCase(Locale.ROOT))
                .sorted().reduce((left, right) -> left + "_" + right).orElse("unknown");
    }

    private static String token(String value) {
        String normalized = requireText(value, "token");
        return normalized.length() + ":" + normalized;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private record Candidate(StableSource source,
                             int multiplicity,
                             String sourcePayload,
                             String sourceFingerprint,
                             String profileId,
                             UUID residentUuid) {
    }
}
