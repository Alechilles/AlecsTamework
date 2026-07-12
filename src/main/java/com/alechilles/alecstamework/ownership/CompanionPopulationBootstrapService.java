package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyReadiness;
import com.alechilles.alecstamework.persistence.sqlite.CompanionIdentityRepository;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRepository;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationRepository;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationStateRecord;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Loads the canonical population/identity snapshot before capped admissions are exposed.
 */
public final class CompanionPopulationBootstrapService {
    private static final EnumSet<CompanionPopulationCoverageRecord.Dimension> GLOBAL_REQUIRED =
            EnumSet.of(
                    CompanionPopulationCoverageRecord.Dimension.GLOBAL_OWNER,
                    CompanionPopulationCoverageRecord.Dimension.PROFILE_STATE,
                    CompanionPopulationCoverageRecord.Dimension.WORLD_ENTITIES,
                    CompanionPopulationCoverageRecord.Dimension.PLAYER_SAVES,
                    CompanionPopulationCoverageRecord.Dimension.BASE_CONTAINER_BLOCKS,
                    CompanionPopulationCoverageRecord.Dimension.CUSTOM_CONTAINERS
            );

    private final CompanionPopulationRepository populationRepository;
    private final CompanionPopulationCoverageRepository coverageRepository;
    private final CompanionIdentityRepository identityRepository;
    private final PersistenceHealthService persistenceHealth;
    private final OwnerPopulationIndex index;
    private final CompanionIdentityResolver identityResolver;
    private final ClaimOccupancyIndex claimOccupancyIndex;

    public CompanionPopulationBootstrapService(
            @Nonnull CompanionPopulationRepository populationRepository,
            @Nonnull CompanionPopulationCoverageRepository coverageRepository,
            @Nonnull CompanionIdentityRepository identityRepository,
            @Nonnull PersistenceHealthService persistenceHealth,
            @Nonnull OwnerPopulationIndex index,
            @Nonnull CompanionIdentityResolver identityResolver,
            @Nonnull ClaimOccupancyIndex claimOccupancyIndex
    ) {
        this.populationRepository = Objects.requireNonNull(populationRepository, "populationRepository");
        this.coverageRepository = Objects.requireNonNull(coverageRepository, "coverageRepository");
        this.identityRepository = Objects.requireNonNull(identityRepository, "identityRepository");
        this.persistenceHealth = Objects.requireNonNull(persistenceHealth, "persistenceHealth");
        this.index = Objects.requireNonNull(index, "index");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.claimOccupancyIndex = Objects.requireNonNull(claimOccupancyIndex, "claimOccupancyIndex");
    }

    @Nonnull
    public BootstrapResult load() {
        return load(true);
    }

    /** Loads entries while admissions remain RECONCILING until buffered live observations replay. */
    @Nonnull
    public BootstrapResult loadForReconciliation() {
        return load(false);
    }

    /**
     * Loads a canonical-reload candidate while treating its durably staged owner rows as pending.
     * In-memory admission remains RECONCILING until the caller completes finalization.
     */
    @Nonnull
    public BootstrapResult loadForFinalizationCandidate() {
        return load(false, true);
    }

    /** Publishes readiness derived by a preceding reconciliation snapshot load. */
    public void publishReadiness(@Nonnull BootstrapResult result) {
        Objects.requireNonNull(result, "result");
        index.setReadiness(result.globalReadiness(), result.perWorldReadiness());
        claimOccupancyIndex.setReadiness(toClaimReadiness(result.globalReadiness()));
    }

    @Nonnull
    private BootstrapResult load(boolean publishFinalReadiness) {
        return load(publishFinalReadiness, false);
    }

    @Nonnull
    private BootstrapResult load(
            boolean publishFinalReadiness,
            boolean assumeStagedOwnerCoverage
    ) {
        if (!persistenceHealth.isHealthy()) {
            index.replaceCommittedEntries(List.of(), OwnerPopulationReadiness.DEGRADED);
            claimOccupancyIndex.replaceCommittedEntries(List.of(), ClaimOccupancyReadiness.DEGRADED);
            return new BootstrapResult(
                    OwnerPopulationReadiness.DEGRADED,
                    OwnerPopulationReadiness.DEGRADED,
                    0,
                    0,
                    0,
                    "persistence-degraded"
            );
        }
        try {
            List<CompanionPopulationStateRecord> states = populationRepository.loadAllStates();
            List<CompanionPopulationOperationRecord> operations =
                    populationRepository.loadNonterminalOperations();
            List<CompanionPopulationCoverageRecord> coverage = coverageRepository.loadAll();
            List<OwnerPopulationEntry> entries = toOwnerEntries(states);
            List<ClaimOccupancyEntry> claimEntries = toClaimEntries(states);
            identityResolver.replaceDurableAliases(identityRepository.loadAllAliases());

            Map<CompanionPopulationCoverageRecord.Dimension, CompanionPopulationCoverageRecord.State>
                    coverageStates = aggregateCoverage(coverage);
            if (assumeStagedOwnerCoverage) {
                promoteStagedOwnerCoverage(coverageStates);
            }
            OwnerPopulationReadiness global = deriveGlobalReadiness(coverageStates, operations);
            OwnerPopulationReadiness perWorld = derivePerWorldReadiness(
                    coverageStates,
                    operations,
                    entries,
                    global
            );
            OwnerPopulationReadiness loadedGlobal = publishFinalReadiness
                    ? global
                    : OwnerPopulationReadiness.RECONCILING;
            OwnerPopulationReadiness loadedPerWorld = publishFinalReadiness
                    ? perWorld
                    : OwnerPopulationReadiness.RECONCILING;
            index.replaceCommittedEntries(entries, loadedGlobal, loadedPerWorld);
            claimOccupancyIndex.replaceCommittedEntries(
                    claimEntries,
                    publishFinalReadiness
                            ? toClaimReadiness(global)
                            : ClaimOccupancyReadiness.RECONCILING
            );
            return new BootstrapResult(
                    global,
                    perWorld,
                    entries.size(),
                    operations.size(),
                    identityResolver.aliasCount(),
                    operations.isEmpty() ? "population-loaded" : "population-operations-pending"
            );
        } catch (Exception exception) {
            persistenceHealth.markDegraded(
                    "population_bootstrap_failed:" + exception.getClass().getSimpleName()
            );
            index.replaceCommittedEntries(List.of(), OwnerPopulationReadiness.DEGRADED);
            claimOccupancyIndex.replaceCommittedEntries(List.of(), ClaimOccupancyReadiness.DEGRADED);
            return new BootstrapResult(
                    OwnerPopulationReadiness.DEGRADED,
                    OwnerPopulationReadiness.DEGRADED,
                    0,
                    0,
                    0,
                    "population-bootstrap-failed"
            );
        }
    }

    @Nonnull
    private static List<ClaimOccupancyEntry> toClaimEntries(
            @Nonnull List<CompanionPopulationStateRecord> states
    ) {
        List<ClaimOccupancyEntry> entries = new ArrayList<>(states.size());
        for (CompanionPopulationStateRecord state : states) {
            String physicalWorld = normalizeWorld(state.physicalWorldName());
            ClaimChunkCoordinate physicalChunk = physicalWorld == null
                    || state.physicalChunkX() == null
                    || state.physicalChunkZ() == null
                    ? null
                    : new ClaimChunkCoordinate(
                            physicalWorld,
                            state.physicalChunkX(),
                            state.physicalChunkZ()
                    );
            entries.add(new ClaimOccupancyEntry(
                    state.profileId(),
                    state.ownerUuid(),
                    CompanionLifecycleState.valueOf(state.lifecycleState()),
                    physicalChunk,
                    state.revision()
            ));
        }
        return List.copyOf(entries);
    }

    @Nonnull
    private static ClaimOccupancyReadiness toClaimReadiness(
            @Nonnull OwnerPopulationReadiness readiness
    ) {
        return switch (readiness) {
            case READY -> ClaimOccupancyReadiness.READY;
            case DEGRADED -> ClaimOccupancyReadiness.DEGRADED;
            case LOADING -> ClaimOccupancyReadiness.LOADING;
            case RECONCILING -> ClaimOccupancyReadiness.RECONCILING;
        };
    }

    @Nonnull
    private static List<OwnerPopulationEntry> toOwnerEntries(
            @Nonnull List<CompanionPopulationStateRecord> states
    ) {
        List<OwnerPopulationEntry> entries = new ArrayList<>(states.size());
        for (CompanionPopulationStateRecord state : states) {
            String ownershipWorld = normalizeWorld(state.ownershipWorldName());
            if (ownershipWorld == null) {
                ownershipWorld = normalizeWorld(state.profileLastWorldName());
            }
            entries.add(new OwnerPopulationEntry(
                    state.profileId(),
                    state.ownerUuid(),
                    ownershipWorld,
                    CompanionLifecycleState.valueOf(state.lifecycleState()),
                    state.revision()
            ));
        }
        return List.copyOf(entries);
    }

    @Nonnull
    private static OwnerPopulationReadiness deriveGlobalReadiness(
            @Nonnull Map<CompanionPopulationCoverageRecord.Dimension,
                    CompanionPopulationCoverageRecord.State> states,
            @Nonnull List<CompanionPopulationOperationRecord> operations
    ) {
        if (!operations.isEmpty()) {
            return OwnerPopulationReadiness.RECONCILING;
        }
        return readinessFor(GLOBAL_REQUIRED, states);
    }

    @Nonnull
    private static OwnerPopulationReadiness derivePerWorldReadiness(
            @Nonnull Map<CompanionPopulationCoverageRecord.Dimension,
                    CompanionPopulationCoverageRecord.State> states,
            @Nonnull List<CompanionPopulationOperationRecord> operations,
            @Nonnull List<OwnerPopulationEntry> entries,
            @Nonnull OwnerPopulationReadiness global
    ) {
        if (global == OwnerPopulationReadiness.DEGRADED || !operations.isEmpty()) {
            return global == OwnerPopulationReadiness.DEGRADED
                    ? OwnerPopulationReadiness.DEGRADED
                    : OwnerPopulationReadiness.RECONCILING;
        }
        for (OwnerPopulationEntry entry : entries) {
            if (entry.ownerId() != null && entry.ownershipWorldName() == null) {
                return OwnerPopulationReadiness.RECONCILING;
            }
        }
        EnumSet<CompanionPopulationCoverageRecord.Dimension> required =
                EnumSet.copyOf(GLOBAL_REQUIRED);
        required.add(CompanionPopulationCoverageRecord.Dimension.PER_WORLD_OWNER);
        return readinessFor(required, states);
    }

    private static void promoteStagedOwnerCoverage(
            @Nonnull Map<CompanionPopulationCoverageRecord.Dimension,
                    CompanionPopulationCoverageRecord.State> states
    ) {
        for (CompanionPopulationCoverageRecord.Dimension dimension : List.of(
                CompanionPopulationCoverageRecord.Dimension.GLOBAL_OWNER,
                CompanionPopulationCoverageRecord.Dimension.PER_WORLD_OWNER)) {
            if (states.get(dimension) == CompanionPopulationCoverageRecord.State.RECONCILING) {
                states.put(dimension, CompanionPopulationCoverageRecord.State.READY);
            }
        }
    }

    @Nonnull
    private static Map<CompanionPopulationCoverageRecord.Dimension, CompanionPopulationCoverageRecord.State>
    aggregateCoverage(@Nonnull List<CompanionPopulationCoverageRecord> coverage) {
        EnumMap<CompanionPopulationCoverageRecord.Dimension, CompanionPopulationCoverageRecord.State> states =
                new EnumMap<>(CompanionPopulationCoverageRecord.Dimension.class);
        for (CompanionPopulationCoverageRecord record : coverage) {
            states.merge(record.dimension(), record.state(), CompanionPopulationBootstrapService::leastReady);
        }
        return states;
    }

    @Nonnull
    private static OwnerPopulationReadiness readinessFor(
            @Nonnull EnumSet<CompanionPopulationCoverageRecord.Dimension> required,
            @Nonnull Map<CompanionPopulationCoverageRecord.Dimension, CompanionPopulationCoverageRecord.State> states
    ) {
        boolean reconciling = false;
        for (CompanionPopulationCoverageRecord.Dimension dimension : required) {
            CompanionPopulationCoverageRecord.State state = states.get(dimension);
            if (state == CompanionPopulationCoverageRecord.State.DEGRADED) {
                return OwnerPopulationReadiness.DEGRADED;
            }
            if (state != CompanionPopulationCoverageRecord.State.READY) {
                reconciling = true;
            }
        }
        return reconciling ? OwnerPopulationReadiness.RECONCILING : OwnerPopulationReadiness.READY;
    }

    @Nonnull
    private static CompanionPopulationCoverageRecord.State leastReady(
            @Nonnull CompanionPopulationCoverageRecord.State first,
            @Nonnull CompanionPopulationCoverageRecord.State second
    ) {
        if (first == CompanionPopulationCoverageRecord.State.DEGRADED
                || second == CompanionPopulationCoverageRecord.State.DEGRADED) {
            return CompanionPopulationCoverageRecord.State.DEGRADED;
        }
        if (first == CompanionPopulationCoverageRecord.State.LOADING
                || second == CompanionPopulationCoverageRecord.State.LOADING) {
            return CompanionPopulationCoverageRecord.State.LOADING;
        }
        if (first == CompanionPopulationCoverageRecord.State.RECONCILING
                || second == CompanionPopulationCoverageRecord.State.RECONCILING) {
            return CompanionPopulationCoverageRecord.State.RECONCILING;
        }
        return CompanionPopulationCoverageRecord.State.READY;
    }

    @Nullable
    private static String normalizeWorld(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record BootstrapResult(@Nonnull OwnerPopulationReadiness globalReadiness,
                                  @Nonnull OwnerPopulationReadiness perWorldReadiness,
                                  int profileCount,
                                  int nonterminalOperationCount,
                                  int aliasCount,
                                  @Nonnull String reason) {
    }
}
