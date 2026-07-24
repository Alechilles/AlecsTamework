package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.api.PersistenceResilienceView;
import com.alechilles.alecstamework.api.PopulationDiagnosticsView;
import com.alechilles.alecstamework.api.PopulationGroupReconciliationView;
import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.api.TameworkApiCapability;
import com.alechilles.alecstamework.api.internal.TameworkEventBus;
import com.alechilles.alecstamework.config.assets.TwPopulationGroupConfig;
import com.alechilles.alecstamework.persistence.incidents.PersistenceIncident;
import com.alechilles.alecstamework.persistence.sqlite.CaptureAttemptRecord;
import com.alechilles.alecstamework.persistence.sqlite.CaptureAttemptRepository;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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
            boolean captureReady,
            @Nullable TameworkEventBus eventBus) {
        return new TameworkIntegrationDiagnosticsService(
                new LiveSource(api, persistence, captureReady, eventBus));
    }

    @Nonnull
    List<String> overview() {
        List<String> lines = new ArrayList<>(MAX_LINES);
        lines.add("Tamework API=" + safe(source::apiVersion, "unavailable")
                + " capabilities=" + safe(source::capabilities, "[]"));
        lines.add("Integration readiness: capturePolicy=" + source.captureReady()
                + ", commandFamilyRosters="
                + source.hasCapability(TameworkApiCapability.COMMAND_FAMILY_ROSTERS)
                + ", timedSummoning="
                + source.hasCapability(TameworkApiCapability.COMMAND_TIMED_SUMMONING)
                + ", populationGroups=" + source.hasCapability(TameworkApiCapability.POPULATION_GROUPS));
        appendCaptureSummary(lines);
        appendPopulationSummary(lines);
        appendEventDeliverySummary(lines);
        appendPersistenceSummary(lines);
        return List.copyOf(lines.subList(0, Math.min(lines.size(), MAX_LINES)));
    }

    private void appendEventDeliverySummary(List<String> lines) {
        EventDeliverySummary summary = safe(source::eventDeliverySummary,
                new EventDeliverySummary(0L, 0L, 0L, 0L, null));
        lines.add("API events: dispatched=" + summary.dispatched()
                + ", deliveryAttempts=" + summary.deliveryAttempts()
                + ", delivered=" + summary.delivered()
                + ", listenerFailuresSinceBoot=" + summary.listenerFailuresSinceBoot()
                + ", lastFailedEventType=" + boundedOrNone(summary.lastFailedEventType()));
    }

    @Nonnull
    List<String> captureAttempt(@Nonnull String attemptId) {
        String key = bounded(attemptId);
        CaptureAttemptDetail detail;
        try {
            detail = source.findCaptureAttempt(attemptId);
        } catch (Exception failure) {
            return List.of("Capture-attempt diagnostic unavailable: " + failureCode(failure));
        }
        if (detail == null) {
            return List.of("Capture attempt not found for id '" + key + "'.");
        }
        List<String> lines = new ArrayList<>(5);
        lines.add("Capture attempt: id=" + bounded(detail.attemptId())
                + ", state=" + detail.state()
                + ", outcome=" + boundedOrNone(detail.outcome())
                + ", reason=" + boundedOrNone(detail.reasonCode())
                + ", recovery=" + bounded(detail.recoveryStatus())
                + ", updatedAtMs=" + detail.updatedAtMs());
        lines.add("Pinned capture policy: spawner=" + bounded(detail.spawnerConfigId())
                + "@" + detail.spawnerConfigRevision()
                + ", target=" + (detail.targetPolicyConfigId() == null
                ? "<bypassed>" : bounded(detail.targetPolicyConfigId())
                + "@" + detail.targetPolicyConfigRevision())
                + ", guaranteed=" + detail.guaranteed()
                + ", sourceItem=" + bounded(detail.sourceItemId())
                + ", sourceRole=" + boundedOrNone(detail.sourceRoleId()));
        if (detail.hasResolution()) {
            lines.add("Capture formula: power=" + detail.power()
                    + ", minimumPower=" + detail.minimumPower()
                    + ", health=" + detail.currentHealth() + "/" + detail.maximumHealth()
                    + ", missingHealth=" + detail.missingHealthFraction()
                    + ", conditionBonus=" + detail.conditionBonus()
                    + ", effectiveChance=" + detail.effectiveChance()
                    + ", entropy=<redacted>");
        } else {
            lines.add("Capture formula: unresolved; entropy=<redacted>");
        }
        lines.add("Capture linkage: operation=" + boundedOrNone(detail.captureOperationId())
                + ", populationOperation=" + boundedOrNone(detail.populationOperationId())
                + ", populationJournalOperation="
                + boundedOrNone(detail.populationJournalOperationId())
                + ", correlation=" + boundedOrNone(detail.correlationId())
                + ", profile=" + boundedOrNone(detail.profileId()));
        lines.add("Capture durability: cooldown=" + bounded(detail.cooldownStatus())
                + ", eventEmitted=" + detail.eventEmitted()
                + ", quarantine=" + detail.quarantined()
                + ", quarantineEvidence=" + (detail.hasQuarantineEvidence() ? "present" : "absent")
                + ", incident=" + boundedOrNone(detail.incidentId())
                + ", incidentStatus=" + boundedOrNone(detail.incidentStatus())
                + ", incidentReason=" + boundedOrNone(detail.incidentReason()));
        return List.copyOf(lines);
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

    private void appendCaptureSummary(List<String> lines) {
        CaptureSummary summary = safe(source::captureSummary, null);
        if (summary == null) {
            lines.add("Capture attempts: diagnostics=UNAVAILABLE");
            return;
        }
        lines.add("Capture attempts: prepared=" + summary.prepared()
                + ", resolvedFailure=" + summary.resolvedFailure()
                + ", applying=" + summary.applying()
                + ", quarantined=" + summary.quarantined()
                + ", recovered=" + summary.recovered()
                + ", duplicateCallbacksSinceBoot=" + summary.duplicateCallbacksSinceBoot());
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
        @Nonnull PopulationGroupReconciliationView groupReadiness() throws Exception;
        @Nonnull PopulationDiagnosticsView populationDiagnostics() throws Exception;
        @Nonnull GroupOperationSummary groupOperationSummary() throws Exception;
        @Nonnull GroupConfigSummary groupConfigSummary() throws Exception;
        @Nonnull PersistenceSummary persistenceSummary() throws Exception;
        @Nonnull CaptureSummary captureSummary() throws Exception;
        default @Nonnull EventDeliverySummary eventDeliverySummary() throws Exception {
            return new EventDeliverySummary(0L, 0L, 0L, 0L, null);
        }
        @Nullable CaptureAttemptDetail findCaptureAttempt(@Nonnull String attemptId) throws Exception;
    }

    private static final class LiveSource implements Source {
        @Nullable private final TameworkApi api;
        private final TameworkPersistenceRuntime persistence;
        private final boolean captureReady;
        @Nullable private final TameworkEventBus eventBus;

        private LiveSource(@Nullable TameworkApi api,
                           TameworkPersistenceRuntime persistence,
                           boolean captureReady,
                           @Nullable TameworkEventBus eventBus) {
            this.api = api;
            this.persistence = Objects.requireNonNull(persistence, "persistence");
            this.captureReady = captureReady;
            this.eventBus = eventBus;
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

        @Override public PopulationGroupReconciliationView groupReadiness() {
            return api == null ? PopulationGroupReconciliationView.unavailable()
                    : api.policies().populationGroups().getReconciliationStatus();
        }

        @Override public PopulationDiagnosticsView populationDiagnostics() {
            return api == null ? PopulationDiagnosticsView.unavailable()
                    : api.diagnostics().getPopulationDiagnostics();
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

        @Override public CaptureSummary captureSummary() throws Exception {
            CaptureAttemptRepository.DiagnosticsSummary summary = persistence
                    .getCaptureAttemptRepository().summarizeDiagnostics();
            return new CaptureSummary(summary.prepared(), summary.resolvedFailure(),
                    summary.applying(), summary.quarantined(), summary.recovered(),
                    summary.duplicateCallbacksSinceBoot());
        }

        @Override public EventDeliverySummary eventDeliverySummary() {
            if (eventBus == null) return new EventDeliverySummary(0L, 0L, 0L, 0L, null);
            TameworkEventBus.DeliveryDiagnostics diagnostics = eventBus.deliveryDiagnostics();
            return new EventDeliverySummary(
                    diagnostics.dispatchedEvents(), diagnostics.deliveryAttempts(),
                    diagnostics.deliveredListeners(), diagnostics.listenerFailuresSinceBoot(),
                    diagnostics.lastFailedEventType());
        }

        @Override public CaptureAttemptDetail findCaptureAttempt(String attemptId) throws Exception {
            CaptureAttemptRecord attempt = persistence.getCaptureAttemptRepository().find(attemptId);
            if (attempt == null) return null;
            CaptureAttemptRecord.Resolution resolution = attempt.resolution();
            PopulationGroupOperationRecord population = attempt.populationOperationId() == null
                    ? null : persistence.getPopulationGroupRepository()
                    .findOperation(attempt.populationOperationId());
            CaptureAttemptRepository.FailureCooldown cooldown = resolution == null
                    || resolution.failureCooldownUntilMs() == 0L ? null
                    : persistence.getCaptureAttemptRepository().findFailureCooldown(
                    attempt.identity().actorUuid(), attempt.config().spawnerConfigId());
            long nowMs = System.currentTimeMillis();
            String cooldownStatus = cooldown == null
                    || !attempt.identity().attemptId().equals(cooldown.attemptId())
                    ? "none"
                    : (cooldown.cooldownUntilMs() > nowMs ? "active-until-" : "expired-at-")
                    + cooldown.cooldownUntilMs();
            PersistenceIncident incident = findRelatedIncident(attempt, population);
            boolean quarantined = attempt.state() == CaptureAttemptRecord.State.QUARANTINED;
            return new CaptureAttemptDetail(
                    attempt.identity().attemptId(), attempt.state().name(),
                    resolution == null ? null : resolution.outcome(),
                    resolution == null ? attempt.lastError() : resolution.reasonCode(),
                    attempt.recoveryStatus(), attempt.updatedAtMs(),
                    attempt.config().spawnerConfigId(), attempt.config().spawnerConfigRevision(),
                    attempt.config().targetPolicyConfigId(),
                    attempt.config().targetPolicyConfigRevision(), attempt.config().guaranteed(),
                    attempt.identity().sourceItemId(), attempt.identity().sourceRoleId(),
                    resolution != null,
                    resolution == null ? 0.0D : resolution.power(),
                    resolution == null ? 0.0D : resolution.minimumPower(),
                    resolution == null ? 0.0D : resolution.currentHealth(),
                    resolution == null ? 0.0D : resolution.maximumHealth(),
                    resolution == null ? 0.0D : resolution.missingHealthFraction(),
                    resolution == null ? 0.0D : resolution.conditionBonus(),
                    resolution == null ? 0.0D : resolution.effectiveChance(),
                    attempt.captureOperationId(), attempt.populationOperationId(),
                    population == null ? null : population.operationId(),
                    population == null ? attempt.populationOperationId()
                            : population.populationOperationId(),
                    attempt.identity().profileId(), cooldownStatus,
                    attempt.eventEmittedAtMs() != 0L, quarantined,
                    attempt.lastError() != null || incident != null,
                    incident == null ? null : incident.incidentId(),
                    incident == null ? null : incident.status().name(),
                    incident == null ? null : incident.reasonCode());
        }

        @Nullable
        private PersistenceIncident findRelatedIncident(
                CaptureAttemptRecord attempt,
                @Nullable PopulationGroupOperationRecord population) throws Exception {
            List<String> operationIds = new ArrayList<>(4);
            operationIds.add(attempt.identity().attemptId());
            if (attempt.captureOperationId() != null) operationIds.add(attempt.captureOperationId());
            if (attempt.populationOperationId() != null) operationIds.add(attempt.populationOperationId());
            if (population != null) operationIds.add(population.operationId());
            return persistence.getIncidentRepository().listRecent(false, 100).stream()
                    .filter(incident -> incident.operationId() != null
                            && operationIds.contains(incident.operationId()))
                    .findFirst().orElse(null);
        }

        private static boolean relevantCoverage(String dimension) {
            String normalized = dimension.toUpperCase(Locale.ROOT);
            return normalized.contains("POPULATION") || normalized.contains("PROFILE")
                    || normalized.contains("WORLD") || normalized.contains("INVENTORY");
        }
    }

    record GroupOperationSummary(long openOperations, long quarantined,
                                 @Nullable String oldestCorrelation) { }
    record GroupConfigSummary(@Nonnull String winners, long mappingConflicts) { }
    record PersistenceSummary(@Nonnull String storageState, @Nullable String storageReason,
                              int activeIncidents, int activeQuarantines,
                              @Nonnull String coverage) { }
    record CaptureSummary(long prepared, long resolvedFailure, long applying,
                          long quarantined, long recovered,
                          long duplicateCallbacksSinceBoot) { }
    record EventDeliverySummary(long dispatched, long deliveryAttempts, long delivered,
                                long listenerFailuresSinceBoot,
                                @Nullable String lastFailedEventType) {
        EventDeliverySummary {
            if (dispatched < 0L || deliveryAttempts < 0L || delivered < 0L
                    || listenerFailuresSinceBoot < 0L
                    || delivered + listenerFailuresSinceBoot > deliveryAttempts) {
                throw new IllegalArgumentException("Invalid event delivery summary");
            }
        }
    }
    record CaptureAttemptDetail(
            @Nonnull String attemptId, @Nonnull String state,
            @Nullable String outcome, @Nullable String reasonCode,
            @Nonnull String recoveryStatus, long updatedAtMs,
            @Nonnull String spawnerConfigId, long spawnerConfigRevision,
            @Nullable String targetPolicyConfigId, @Nullable Long targetPolicyConfigRevision,
            boolean guaranteed, @Nonnull String sourceItemId, @Nullable String sourceRoleId,
            boolean hasResolution, double power, double minimumPower,
            double currentHealth, double maximumHealth, double missingHealthFraction,
            double conditionBonus, double effectiveChance,
            @Nullable String captureOperationId, @Nullable String populationOperationId,
            @Nullable String populationJournalOperationId, @Nullable String correlationId,
            @Nullable String profileId, @Nonnull String cooldownStatus,
            boolean eventEmitted, boolean quarantined, boolean hasQuarantineEvidence,
            @Nullable String incidentId, @Nullable String incidentStatus,
            @Nullable String incidentReason) { }
    @FunctionalInterface
    private interface CheckedSupplier<T> { T get() throws Exception; }
}
