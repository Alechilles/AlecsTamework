package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.persistence.health.PersistenceCoverageRegistry;
import com.alechilles.alecstamework.persistence.health.PersistenceCoverageStatus;
import com.alechilles.alecstamework.persistence.health.PersistenceEvidenceDimension;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeFactory;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRepository;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Projects durable population-scan coverage into the named persistence health dimensions. */
final class CompanionPopulationCoverageProjection {
    private static final EnumSet<PersistenceEvidenceDimension> PROJECTED_DIMENSIONS = EnumSet.of(
            PersistenceEvidenceDimension.GLOBAL_OWNER_COUNTS,
            PersistenceEvidenceDimension.PER_WORLD_OWNER_COUNTS,
            PersistenceEvidenceDimension.PHYSICAL_CLAIM_OCCUPANCY,
            PersistenceEvidenceDimension.SAVED_WORLD_ENTITIES,
            PersistenceEvidenceDimension.BASE_CONTAINER_EVIDENCE,
            PersistenceEvidenceDimension.PERSISTED_PLAYER_INVENTORIES,
            PersistenceEvidenceDimension.LIVE_PLAYER_OVERLAY,
            PersistenceEvidenceDimension.CUSTOM_CONTAINER_EVIDENCE,
            PersistenceEvidenceDimension.LOADED_PROJECTION_IDENTITIES
    );
    private static final String STORED_PLAYER_COVERAGE = "player-saves:stored";
    private static final String ONLINE_PLAYER_COVERAGE = "player-saves:online";

    private final PersistenceCoverageRegistry coverage;
    private final PersistenceScopeFactory scopes;

    CompanionPopulationCoverageProjection(
            @Nonnull PersistenceCoverageRegistry coverage,
            @Nonnull PersistenceScopeFactory scopes
    ) {
        this.coverage = Objects.requireNonNull(coverage, "coverage");
        this.scopes = Objects.requireNonNull(scopes, "scopes");
    }

    /** Revokes the previous scan generation before a new reconciliation pass starts. */
    void begin() {
        long generation = System.currentTimeMillis();
        for (PersistenceEvidenceDimension dimension : PROJECTED_DIMENSIONS) {
            coverage.publish(
                    dimension.key(), PersistenceCoverageStatus.LOADING,
                    "reconciliation-scan-in-progress", generation,
                    Set.of(), false, Set.of(), "evidence_ready"
            );
        }
    }

    /** Loads the just-committed scan records and publishes only evidence proven by that pass. */
    void refresh(
            @Nonnull CompanionPopulationCoverageRepository repository,
            @Nonnull CompanionPersistedProjectionEvidenceRegistry projectionEvidence,
            @Nonnull CompanionPopulationReconciliationProgress progress
    ) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(projectionEvidence, "projectionEvidence");
        Objects.requireNonNull(progress, "progress");
        long generation = System.currentTimeMillis();
        if (progress.status() == CompanionPopulationReconciliationProgress.Status.DEGRADED
                || progress.status() == CompanionPopulationReconciliationProgress.Status.CLOSED) {
            publishUnavailable(progress.reason(), generation);
            return;
        }
        try {
            publish(repository.loadAll(), projectionEvidence.snapshot(), generation);
        } catch (Exception failure) {
            publishUnavailable(
                    "reconciliation-coverage-read-failed:"
                            + failure.getClass().getSimpleName(),
                    generation
            );
        }
    }

    void publish(
            @Nonnull List<CompanionPopulationCoverageRecord> records,
            @Nonnull CompanionPersistedProjectionEvidenceRegistry.Snapshot projection,
            long generation
    ) {
        List<CompanionPopulationCoverageRecord> snapshot = List.copyOf(records);
        publishRows(PersistenceEvidenceDimension.GLOBAL_OWNER_COUNTS, snapshot,
                dimension(CompanionPopulationCoverageRecord.Dimension.GLOBAL_OWNER), generation);
        publishRows(PersistenceEvidenceDimension.PER_WORLD_OWNER_COUNTS, snapshot,
                dimension(CompanionPopulationCoverageRecord.Dimension.PER_WORLD_OWNER), generation);
        publishRows(PersistenceEvidenceDimension.PHYSICAL_CLAIM_OCCUPANCY, snapshot,
                dimension(CompanionPopulationCoverageRecord.Dimension.GLOBAL_OWNER), generation);
        publishRows(PersistenceEvidenceDimension.SAVED_WORLD_ENTITIES, snapshot,
                dimension(CompanionPopulationCoverageRecord.Dimension.WORLD_ENTITIES), generation);
        publishRows(PersistenceEvidenceDimension.BASE_CONTAINER_EVIDENCE, snapshot,
                dimension(CompanionPopulationCoverageRecord.Dimension.BASE_CONTAINER_BLOCKS), generation);
        publishRows(PersistenceEvidenceDimension.PERSISTED_PLAYER_INVENTORIES, snapshot,
                record -> STORED_PLAYER_COVERAGE.equals(record.coverageKey()), generation);
        publishRows(PersistenceEvidenceDimension.LIVE_PLAYER_OVERLAY, snapshot,
                record -> ONLINE_PLAYER_COVERAGE.equals(record.coverageKey()), generation);
        publishRows(PersistenceEvidenceDimension.CUSTOM_CONTAINER_EVIDENCE, snapshot,
                dimension(CompanionPopulationCoverageRecord.Dimension.CUSTOM_CONTAINERS), generation);
        publishLoadedProjection(projection, generation);
    }

    private void publishRows(
            PersistenceEvidenceDimension target,
            List<CompanionPopulationCoverageRecord> records,
            Predicate<CompanionPopulationCoverageRecord> selector,
            long generation
    ) {
        List<CompanionPopulationCoverageRecord> selected = records.stream()
                .filter(selector).toList();
        if (selected.isEmpty()) {
            publishState(target, PersistenceCoverageStatus.UNAVAILABLE,
                    "reconciliation-coverage-missing", generation, Set.of(), false,
                    "reconciliation_retry");
            return;
        }
        boolean allReady = selected.stream().allMatch(CompanionPopulationCoverageProjection::ready);
        boolean anyReady = selected.stream().anyMatch(CompanionPopulationCoverageProjection::ready);
        boolean anyDegraded = selected.stream().anyMatch(record ->
                record.state() == CompanionPopulationCoverageRecord.State.DEGRADED);
        PersistenceCoverageStatus status = allReady
                ? PersistenceCoverageStatus.READY
                : anyDegraded ? PersistenceCoverageStatus.UNAVAILABLE
                : anyReady ? PersistenceCoverageStatus.PARTIAL
                : PersistenceCoverageStatus.LOADING;
        publishState(
                target, status, reason(selected, status), generation,
                readyWorldScopes(target, selected), allReady,
                status == PersistenceCoverageStatus.LOADING ? "evidence_ready"
                        : status == PersistenceCoverageStatus.READY ? null : "reconciliation_retry"
        );
    }

    private void publishLoadedProjection(
            CompanionPersistedProjectionEvidenceRegistry.Snapshot projection,
            long generation
    ) {
        PersistenceCoverageStatus status = switch (projection.state()) {
            case SEALED -> projection.loadedIdentities() != null
                    && projection.loadedIdentities().initializationComplete()
                    ? PersistenceCoverageStatus.READY : PersistenceCoverageStatus.UNAVAILABLE;
            case SCANNING -> PersistenceCoverageStatus.LOADING;
            case UNSEALED -> PersistenceCoverageStatus.UNAVAILABLE;
            case DEGRADED -> PersistenceCoverageStatus.CONTRADICTORY;
        };
        String reason = status == PersistenceCoverageStatus.READY
                ? null : projection.detail();
        publishState(
                PersistenceEvidenceDimension.LOADED_PROJECTION_IDENTITIES,
                status, reason, generation, Set.of(), status == PersistenceCoverageStatus.READY,
                status == PersistenceCoverageStatus.LOADING ? "evidence_ready"
                        : status == PersistenceCoverageStatus.READY ? null : "reconciliation_retry"
        );
    }

    private Set<String> readyWorldScopes(
            PersistenceEvidenceDimension dimension,
            List<CompanionPopulationCoverageRecord> records
    ) {
        if (dimension != PersistenceEvidenceDimension.SAVED_WORLD_ENTITIES
                && dimension != PersistenceEvidenceDimension.BASE_CONTAINER_EVIDENCE) {
            return Set.of();
        }
        Set<String> hashes = new LinkedHashSet<>();
        for (CompanionPopulationCoverageRecord record : records) {
            if (!ready(record) || !worldScoped(record)) continue;
            hashes.add(scopes.world(record.worldOrSaveId()).scopeHash());
        }
        return Set.copyOf(hashes);
    }

    private static boolean worldScoped(CompanionPopulationCoverageRecord record) {
        String value = record.worldOrSaveId();
        return value != null && !value.isBlank()
                && !"catalog".equals(value)
                && !"canonical".equals(value)
                && !"universe".equals(value)
                && !"tamework.sqlite".equals(value);
    }

    private void publishUnavailable(@Nullable String reason, long generation) {
        for (PersistenceEvidenceDimension dimension : PROJECTED_DIMENSIONS) {
            publishState(
                    dimension, PersistenceCoverageStatus.UNAVAILABLE,
                    reason == null ? "reconciliation-unavailable" : reason,
                    generation, Set.of(), false, "reconciliation_retry"
            );
        }
    }

    private void publishState(
            PersistenceEvidenceDimension dimension,
            PersistenceCoverageStatus status,
            @Nullable String reason,
            long generation,
            Set<String> coveredScopes,
            boolean absenceAuthoritative,
            @Nullable String nextSafeTrigger
    ) {
        coverage.publish(
                dimension.key(), status, reason, generation, coveredScopes,
                absenceAuthoritative, Set.of(), nextSafeTrigger
        );
    }

    private static Predicate<CompanionPopulationCoverageRecord> dimension(
            CompanionPopulationCoverageRecord.Dimension dimension
    ) {
        return record -> record.dimension() == dimension;
    }

    private static boolean ready(CompanionPopulationCoverageRecord record) {
        return record.state() == CompanionPopulationCoverageRecord.State.READY;
    }

    @Nullable
    private static String reason(
            List<CompanionPopulationCoverageRecord> records,
            PersistenceCoverageStatus status
    ) {
        if (status == PersistenceCoverageStatus.READY) return null;
        List<String> reasons = new ArrayList<>();
        for (CompanionPopulationCoverageRecord record : records) {
            if (ready(record)) continue;
            String detail = record.lastError() == null
                    ? record.state().name().toLowerCase(java.util.Locale.ROOT)
                    : record.lastError();
            if (!reasons.contains(detail)) reasons.add(detail);
            if (reasons.size() == 3) break;
        }
        return reasons.isEmpty() ? "reconciliation-coverage-not-ready" : String.join(",", reasons);
    }
}
