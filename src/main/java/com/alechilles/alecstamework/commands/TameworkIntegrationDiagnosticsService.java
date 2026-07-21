package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.api.BondedVesselReadinessView;
import com.alechilles.alecstamework.api.PersistenceResilienceView;
import com.alechilles.alecstamework.api.PopulationDiagnosticsView;
import com.alechilles.alecstamework.api.PopulationGroupReconciliationView;
import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.api.TameworkApiCapability;
import com.alechilles.alecstamework.config.assets.TwPopulationGroupConfig;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionProvisioningOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupClassificationRecord;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.TreeMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Builds bounded, read-only operator evidence for the API 0.9 integration authorities. */
final class TameworkIntegrationDiagnosticsService {
    static final int MAX_LINES = 8;
    private static final int MAX_FIELD_LENGTH = 96;
    private static final int MAX_GROUP_IDS = 8;
    private final Source source;

    TameworkIntegrationDiagnosticsService(@Nonnull Source source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Nonnull
    static TameworkIntegrationDiagnosticsService live(
            @Nullable TameworkApi api,
            @Nonnull TameworkPersistenceRuntime persistence,
            boolean captureReady) {
        return new TameworkIntegrationDiagnosticsService(
                new LiveSource(api, persistence, captureReady));
    }

    @Nonnull
    List<String> overview() {
        List<String> lines = new ArrayList<>(MAX_LINES);
        lines.add("Tamework API=" + safe(source::apiVersion, "unavailable")
                + " capabilities=" + safe(source::capabilities, "[]"));
        lines.add("Integration readiness: capturePolicy=" + source.captureReady()
                + ", bondedVessels=" + source.hasCapability(TameworkApiCapability.BONDED_VESSELS)
                + ", populationGroups=" + source.hasCapability(TameworkApiCapability.POPULATION_GROUPS)
                + ", provisioning=" + source.hasCapability(TameworkApiCapability.COMPANION_PROVISIONING));
        appendVesselSummary(lines);
        appendPopulationSummary(lines);
        appendProvisioningSummary(lines);
        appendPersistenceSummary(lines);
        return List.copyOf(lines.subList(0, Math.min(lines.size(), MAX_LINES)));
    }

    @Nonnull
    List<String> population() {
        List<String> lines = new ArrayList<>(4);
        appendPopulationSummary(lines);
        PopulationDiagnosticsView diagnostics = safe(source::populationDiagnostics, null);
        if (diagnostics != null) {
            PopulationDiagnosticsView.CountView counts = diagnostics.counts();
            PopulationDiagnosticsView.ReservationMetricsView owners = diagnostics.ownerReservations();
            PopulationDiagnosticsView.ReservationMetricsView claims = diagnostics.claimReservations();
            lines.add("Population authority: tracked=" + counts.trackedProfiles()
                    + ", committedOwners=" + counts.committedOwnerProfiles()
                    + ", pendingOwnerSlots=" + counts.pendingOwnerSlots()
                    + ", committedClaims=" + counts.committedClaimProfiles()
                    + ", pendingClaimSlots=" + counts.pendingClaimSlots()
                    + ", overCapOwnerBuckets=" + counts.overCapOwnerBuckets()
                    + ", overCapClaimBuckets=" + counts.observedOverCapClaimBuckets());
            lines.add("Population reservations: owner(created=" + owners.created()
                    + ", committed=" + owners.committed() + ", canceled=" + owners.canceled()
                    + ", expired=" + owners.expired() + "), claim(created=" + claims.created()
                    + ", committed=" + claims.committed() + ", canceled=" + claims.canceled()
                    + ", expired=" + claims.expired() + ")");
        }
        PersistenceSummary persistence = safe(source::persistenceSummary, null);
        if (persistence != null) {
            lines.add("Population evidence: activeIncidents=" + persistence.activeIncidents()
                    + ", activeQuarantines=" + persistence.activeQuarantines()
                    + ", coverage=" + persistence.coverage());
        }
        return List.copyOf(lines.subList(0, Math.min(lines.size(), MAX_LINES)));
    }

    @Nonnull
    List<String> vessel(@Nonnull String bindingOrProfile) {
        String key = bounded(bindingOrProfile);
        VesselDetail detail;
        try {
            detail = source.findVessel(bindingOrProfile);
        } catch (Exception failure) {
            return List.of("Bonded vessel diagnostic unavailable: " + failureCode(failure));
        }
        if (detail == null) {
            return List.of("Bonded vessel not found for binding/profile '" + key + "'.");
        }
        List<String> lines = new ArrayList<>(3);
        lines.add("Bonded vessel: binding=" + bounded(detail.bindingId())
                + ", profile=" + bounded(detail.profileId())
                + ", lifecycle=" + detail.lifecycle()
                + ", generation=" + detail.generation()
                + ", profileRevision=" + detail.profileRevision()
                + ", config=" + bounded(detail.configId()) + "@" + detail.configRevision());
        lines.add("Vessel projection: status=" + detail.projectionStatus()
                + ", lastItem=" + boundedOrNone(detail.lastItemId())
                + ", itemEvidence=" + (detail.hasItemEvidence() ? "present" : "absent")
                + ", evidenceUpdatedAtMs=" + detail.evidenceUpdatedAtMs()
                + ", quarantine=" + detail.quarantined()
                + ", reason=" + boundedOrNone(detail.reason()));
        lines.add("Vessel operation: id=" + boundedOrNone(detail.operationId())
                + ", action=" + boundedOrNone(detail.operationAction())
                + ", state=" + boundedOrNone(detail.operationState())
                + ", populationOperation=" + boundedOrNone(detail.populationOperationId())
                + ", correlation=" + boundedOrNone(detail.correlationId())
                + ", recovery=" + boundedOrNone(detail.recoveryStatus()));
        return List.copyOf(lines);
    }

    @Nonnull
    List<String> provisioning(@Nonnull String callerNamespace,
                              @Nonnull String idempotencyKey) {
        ProvisioningDetail detail;
        try {
            detail = source.findProvisioning(callerNamespace, idempotencyKey);
        } catch (Exception failure) {
            return List.of("Provisioning diagnostic unavailable: " + failureCode(failure));
        }
        if (detail == null) {
            return List.of("Provisioning operation not found for origin '"
                    + bounded(callerNamespace) + "/" + bounded(idempotencyKey) + "'.");
        }
        List<String> lines = new ArrayList<>(3);
        lines.add("Provisioning operation: id=" + bounded(detail.operationId())
                + ", origin=" + bounded(detail.callerNamespace()) + "/"
                + bounded(detail.idempotencyKey())
                + ", correlation=" + boundedOrNone(detail.correlationId())
                + ", disposition=" + detail.disposition()
                + ", state=" + detail.state()
                + ", recovery=" + bounded(detail.recoveryStatus()));
        lines.add("Provisioning profile: provisional=" + bounded(detail.provisionalProfileId())
                + ", canonical=" + boundedOrNone(detail.canonicalProfileId())
                + ", role=" + bounded(detail.roleId())
                + ", profileUpdatedAtMs=" + detail.profileUpdatedAtMs()
                + ", classificationRevision=" + detail.classificationRevision()
                + ", groups=" + boundedGroups(detail.groupIds()));
        lines.add("Provisioning phases: dormantPopulation="
                + boundedOrNone(detail.dormantPopulationOperationId())
                + ", activePopulation=" + boundedOrNone(detail.activePopulationOperationId())
                + ", result=" + boundedOrNone(detail.resultCode())
                + ", projectionReason=" + boundedOrNone(detail.projectionReason())
                + ", updatedAtMs=" + detail.updatedAtMs()
                + ", completedAtMs=" + detail.completedAtMs());
        return List.copyOf(lines);
    }

    private void appendVesselSummary(List<String> lines) {
        BondedVesselReadinessView view = safe(source::vesselReadiness, null);
        if (view == null) {
            lines.add("Bonded vessels: readiness=UNAVAILABLE, reason=diagnostics-read-failed");
            return;
        }
        lines.add("Bonded vessels: readiness=" + view.readiness()
                + ", reason=" + bounded(view.reason())
                + ", bindings=" + view.bindingCount()
                + ", openOperations=" + view.pendingOperationCount()
                + ", quarantined=" + view.quarantinedCount()
                + ", updatedAtMs=" + view.updatedAtMs());
    }

    private void appendPopulationSummary(List<String> lines) {
        PopulationGroupReconciliationView view = safe(source::groupReadiness, null);
        GroupOperationSummary operations = safe(source::groupOperationSummary, null);
        GroupConfigSummary configs = safe(source::groupConfigSummary, null);
        if (view == null) {
            lines.add("Population groups: readiness=UNAVAILABLE, reason=diagnostics-read-failed");
            return;
        }
        lines.add("Population groups: readiness=" + view.readiness()
                + ", reason=" + bounded(view.reason())
                + ", configRevision=" + view.configRevision()
                + ", classified=" + view.classifiedProfiles()
                + ", pendingProfiles=" + view.pendingProfiles()
                + ", overLimitBuckets=" + view.overLimitBuckets()
                + (operations == null ? "" : ", openOperations=" + operations.openOperations()
                + ", quarantined=" + operations.quarantined()
                + ", oldestCorrelation=" + boundedOrNone(operations.oldestCorrelation()))
                + (configs == null ? "" : ", configs=" + configs.winners()
                + ", mappingConflicts=" + configs.mappingConflicts()));
    }

    private void appendProvisioningSummary(List<String> lines) {
        ProvisioningSummary summary = safe(source::provisioningSummary, null);
        if (summary == null) {
            lines.add("Provisioning: readiness=UNAVAILABLE, reason=diagnostics-read-failed");
            return;
        }
        lines.add("Provisioning: readiness=" + (summary.capabilityReady() ? "READY" : "UNAVAILABLE")
                + ", openOperations=" + summary.openOperations()
                + ", quarantined=" + summary.quarantined()
                + ", oldestUpdatedAtMs=" + summary.oldestUpdatedAtMs());
    }

    private void appendPersistenceSummary(List<String> lines) {
        PersistenceSummary summary = safe(source::persistenceSummary, null);
        if (summary == null) {
            lines.add("Persistence=unavailable reason=diagnostics-read-failed");
            return;
        }
        lines.add("Persistence=" + bounded(summary.storageState())
                + " reason=" + boundedOrNone(summary.storageReason())
                + ", activeIncidents=" + summary.activeIncidents()
                + ", activeQuarantines=" + summary.activeQuarantines()
                + ", coverage=" + summary.coverage());
    }

    private static <T> T safe(CheckedSupplier<T> supplier, T fallback) {
        try {
            T value = supplier.get();
            return value == null ? fallback : value;
        } catch (Exception | LinkageError ignored) {
            return fallback;
        }
    }

    private static String failureCode(Throwable failure) {
        return "diagnostics-read-failed:" + failure.getClass().getSimpleName();
    }

    private static String boundedGroups(List<String> groups) {
        if (groups == null || groups.isEmpty()) return "[]";
        StringJoiner joiner = new StringJoiner(",", "[", groups.size() > MAX_GROUP_IDS ? ",...]" : "]");
        groups.stream().sorted().limit(MAX_GROUP_IDS).map(TameworkIntegrationDiagnosticsService::bounded)
                .forEach(joiner::add);
        return joiner.toString();
    }

    private static String boundedOrNone(@Nullable String value) {
        return value == null || value.isBlank() ? "<none>" : bounded(value);
    }

    private static String bounded(@Nonnull String value) {
        String normalized = Objects.requireNonNull(value, "value")
                .replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        if (normalized.length() <= MAX_FIELD_LENGTH) return normalized;
        return normalized.substring(0, MAX_FIELD_LENGTH - 3) + "...";
    }

    interface Source {
        @Nonnull String apiVersion() throws Exception;
        @Nonnull String capabilities() throws Exception;
        boolean captureReady();
        boolean hasCapability(@Nonnull TameworkApiCapability capability);
        @Nonnull BondedVesselReadinessView vesselReadiness() throws Exception;
        @Nonnull PopulationGroupReconciliationView groupReadiness() throws Exception;
        @Nonnull PopulationDiagnosticsView populationDiagnostics() throws Exception;
        @Nonnull ProvisioningSummary provisioningSummary() throws Exception;
        @Nonnull GroupOperationSummary groupOperationSummary() throws Exception;
        @Nonnull GroupConfigSummary groupConfigSummary() throws Exception;
        @Nonnull PersistenceSummary persistenceSummary() throws Exception;
        @Nullable VesselDetail findVessel(@Nonnull String bindingOrProfile) throws Exception;
        @Nullable ProvisioningDetail findProvisioning(
                @Nonnull String callerNamespace, @Nonnull String idempotencyKey) throws Exception;
    }

    private static final class LiveSource implements Source {
        @Nullable private final TameworkApi api;
        private final TameworkPersistenceRuntime persistence;
        private final boolean captureReady;

        private LiveSource(@Nullable TameworkApi api,
                           TameworkPersistenceRuntime persistence,
                           boolean captureReady) {
            this.api = api;
            this.persistence = Objects.requireNonNull(persistence, "persistence");
            this.captureReady = captureReady;
        }

        @Override public String apiVersion() { return api == null ? "unavailable" : api.getApiVersion(); }

        @Override public String capabilities() {
            if (api == null) return "[]";
            return api.getCapabilities().stream().map(Enum::name).sorted()
                    .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
        }

        @Override public boolean captureReady() { return captureReady; }

        @Override public boolean hasCapability(TameworkApiCapability capability) {
            return api != null && api.getCapabilities().contains(capability);
        }

        @Override public BondedVesselReadinessView vesselReadiness() {
            return api == null ? BondedVesselReadinessView.unavailable()
                    : api.bondedVessels().readiness();
        }

        @Override public PopulationGroupReconciliationView groupReadiness() {
            return api == null ? PopulationGroupReconciliationView.unavailable()
                    : api.policies().populationGroups().getReconciliationStatus();
        }

        @Override public PopulationDiagnosticsView populationDiagnostics() {
            return api == null ? PopulationDiagnosticsView.unavailable()
                    : api.diagnostics().getPopulationDiagnostics();
        }

        @Override public ProvisioningSummary provisioningSummary() throws Exception {
            List<CompanionProvisioningOperationRecord> operations =
                    persistence.getCompanionProvisioningRepository().loadRecoverable();
            long open = operations.stream().filter(operation -> !operation.state().isTerminal()).count();
            long quarantined = operations.stream()
                    .filter(operation -> operation.state() == CompanionProvisioningOperationRecord.State.QUARANTINED)
                    .count();
            long oldest = operations.stream().mapToLong(CompanionProvisioningOperationRecord::updatedAtMs)
                    .min().orElse(0L);
            return new ProvisioningSummary(
                    hasCapability(TameworkApiCapability.COMPANION_PROVISIONING), open, quarantined, oldest);
        }

        @Override public GroupOperationSummary groupOperationSummary() throws Exception {
            List<PopulationGroupOperationRecord> operations =
                    persistence.getPopulationGroupRepository().loadRecoverableOperations();
            long open = operations.stream().filter(operation -> !operation.state().isTerminal()).count();
            long quarantined = operations.stream()
                    .filter(operation -> operation.state() == PopulationGroupOperationRecord.State.QUARANTINED)
                    .count();
            String correlation = operations.stream()
                    .min(Comparator.comparingLong(PopulationGroupOperationRecord::createdAtMs)
                            .thenComparing(PopulationGroupOperationRecord::operationId))
                    .map(operation -> operation.populationOperationId() == null
                            ? operation.operationId() : operation.populationOperationId())
                    .orElse(null);
            return new GroupOperationSummary(open, quarantined, correlation);
        }

        @Override public GroupConfigSummary groupConfigSummary() {
            var assetMap = TwPopulationGroupConfig.getAssetMap();
            if (assetMap == null || assetMap.getAssetMap() == null) {
                return new GroupConfigSummary("[]", 0L);
            }
            Comparator<TwPopulationGroupConfig> winnerOrder =
                    Comparator.comparingInt(TwPopulationGroupConfig::getPriority).reversed()
                            .thenComparing(TwPopulationGroupConfig::getId, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(TwPopulationGroupConfig::getId);
            TreeMap<String, List<TwPopulationGroupConfig>> byGroup = new TreeMap<>();
            for (TwPopulationGroupConfig config : assetMap.getAssetMap().values()) {
                if (config == null || !config.isEnabled() || config.getGroupId() == null
                        || config.getGroupId().isBlank()) continue;
                byGroup.computeIfAbsent(config.getGroupId(), ignored -> new ArrayList<>()).add(config);
            }
            long conflicts = byGroup.values().stream().mapToLong(values -> Math.max(0, values.size() - 1L)).sum();
            String winners = byGroup.entrySet().stream().limit(MAX_GROUP_IDS)
                    .map(entry -> {
                        TwPopulationGroupConfig winner = entry.getValue().stream()
                                .min(winnerOrder).orElseThrow();
                        return bounded(entry.getKey()) + "->" + bounded(winner.getId());
                    })
                    .collect(java.util.stream.Collectors.joining(",", "[",
                            byGroup.size() > MAX_GROUP_IDS ? ",...]" : "]"));
            return new GroupConfigSummary(winners, conflicts);
        }

        @Override public PersistenceSummary persistenceSummary() {
            if (api == null) {
                var health = persistence.getHealthState();
                return new PersistenceSummary(health.status().name(), health.reason(), 0, 0, "unavailable");
            }
            PersistenceResilienceView resilience = api.diagnostics().getPersistenceResilience();
            String coverage = resilience.coverage().stream()
                    .filter(view -> relevantCoverage(view.dimension()))
                    .sorted(Comparator.comparing(PersistenceResilienceView.CoverageView::dimension))
                    .limit(4)
                    .map(view -> bounded(view.dimension()) + "=" + bounded(view.status()))
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
            return new PersistenceSummary(resilience.storageState(), resilience.storageReason(),
                    resilience.activeIncidentCount(), resilience.activeQuarantineCount(), coverage);
        }

        @Override public VesselDetail findVessel(String bindingOrProfile) throws Exception {
            BondedVesselBindingRecord binding =
                    persistence.getBondedVesselRepository().findBinding(bindingOrProfile);
            if (binding == null) {
                binding = persistence.getBondedVesselRepository().findBindingByProfile(bindingOrProfile);
            }
            if (binding == null) return null;
            BondedVesselOperationRecord operation = binding.activeOperationId() == null
                    ? null : persistence.getBondedVesselRepository().findOperation(binding.activeOperationId());
            boolean quarantined = binding.itemProjectionStatus()
                    == BondedVesselBindingRecord.ItemProjectionStatus.QUARANTINED
                    || operation != null && operation.state() == BondedVesselOperationRecord.State.QUARANTINED;
            return new VesselDetail(binding.bindingId(), binding.profileId(), binding.lifecycleState().name(),
                    binding.generation(), binding.expectedProfileRevision(), binding.configId(),
                    binding.configRevision(), binding.itemProjectionStatus().name(), binding.lastItemId(),
                    binding.itemEvidenceJson() != null, binding.updatedAtMs(), quarantined,
                    binding.diagnosticReason(),
                    operation == null ? binding.activeOperationId() : operation.operationId(),
                    operation == null ? null : operation.action().name(),
                    operation == null ? null : operation.state().name(),
                    operation == null ? null : operation.populationOperationId(),
                    operation == null ? null : operation.correlationId(),
                    operation == null ? null : operation.recoveryStatus());
        }

        @Override public ProvisioningDetail findProvisioning(
                String callerNamespace, String idempotencyKey) throws Exception {
            CompanionProvisioningOperationRecord operation = persistence
                    .getCompanionProvisioningRepository()
                    .findByCallerKey(callerNamespace, idempotencyKey);
            if (operation == null) return null;
            NpcProfileRepository.ProfileRecord profile = operation.canonicalProfileId() == null
                    ? null : persistence.getNpcProfileRepository()
                    .loadProfileById(operation.canonicalProfileId());
            PopulationGroupClassificationRecord classification = operation.canonicalProfileId() == null
                    ? null : persistence.getPopulationGroupRepository()
                    .findClassification(operation.canonicalProfileId());
            return new ProvisioningDetail(operation.operationId(), operation.callerNamespace(),
                    operation.idempotencyKey(), operation.correlationId(),
                    operation.requestedDisposition().name(), operation.state().name(),
                    operation.recoveryStatus(), operation.provisionalProfileId(),
                    operation.canonicalProfileId(), operation.targetRoleId(),
                    profile == null ? 0L : profile.updatedAtMs(),
                    classification == null ? 0L : classification.classificationRevision(),
                    classification == null ? List.of() : classification.groupIds(),
                    operation.dormantPopulationOperationId(), operation.activePopulationOperationId(),
                    operation.resultCode(), operation.projectionReason(), operation.updatedAtMs(),
                    operation.completedAtMs());
        }

        private static boolean relevantCoverage(String dimension) {
            String normalized = dimension.toUpperCase(Locale.ROOT);
            return normalized.contains("POPULATION") || normalized.contains("PROFILE")
                    || normalized.contains("WORLD") || normalized.contains("INVENTORY");
        }
    }

    record ProvisioningSummary(boolean capabilityReady, long openOperations,
                               long quarantined, long oldestUpdatedAtMs) { }
    record GroupOperationSummary(long openOperations, long quarantined,
                                 @Nullable String oldestCorrelation) { }
    record GroupConfigSummary(@Nonnull String winners, long mappingConflicts) { }
    record PersistenceSummary(@Nonnull String storageState, @Nullable String storageReason,
                              int activeIncidents, int activeQuarantines,
                              @Nonnull String coverage) { }
    record VesselDetail(@Nonnull String bindingId, @Nonnull String profileId,
                        @Nonnull String lifecycle, long generation, long profileRevision,
                        @Nonnull String configId, long configRevision,
                        @Nonnull String projectionStatus, @Nullable String lastItemId,
                        boolean hasItemEvidence, long evidenceUpdatedAtMs,
                        boolean quarantined, @Nullable String reason,
                        @Nullable String operationId, @Nullable String operationAction,
                        @Nullable String operationState, @Nullable String populationOperationId,
                        @Nullable String correlationId, @Nullable String recoveryStatus) { }
    record ProvisioningDetail(@Nonnull String operationId, @Nonnull String callerNamespace,
                              @Nonnull String idempotencyKey, @Nullable String correlationId,
                              @Nonnull String disposition, @Nonnull String state,
                              @Nonnull String recoveryStatus, @Nonnull String provisionalProfileId,
                              @Nullable String canonicalProfileId, @Nonnull String roleId,
                              long profileUpdatedAtMs, long classificationRevision,
                              @Nonnull List<String> groupIds,
                              @Nullable String dormantPopulationOperationId,
                              @Nullable String activePopulationOperationId,
                              @Nullable String resultCode, @Nullable String projectionReason,
                              long updatedAtMs, long completedAtMs) { }

    @FunctionalInterface
    private interface CheckedSupplier<T> { T get() throws Exception; }
}
