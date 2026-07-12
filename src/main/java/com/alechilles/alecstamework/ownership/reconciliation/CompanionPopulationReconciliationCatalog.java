package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Explicit declaration of every persisted evidence family included in one reconciliation pass.
 */
public final class CompanionPopulationReconciliationCatalog {
    private final List<CompanionPopulationEvidenceSource> sources;
    private final Map<CompanionPopulationCoverageRecord.Dimension, Boolean> sealed;

    public CompanionPopulationReconciliationCatalog(
            @Nonnull List<CompanionPopulationEvidenceSource> coreSources,
            boolean profileCatalogSealed,
            boolean worldEntityCatalogSealed,
            boolean playerCatalogSealed,
            boolean baseContainerCatalogSealed,
            @Nonnull CustomContainerReconciliationRegistry.Snapshot customContainers
    ) {
        Objects.requireNonNull(coreSources, "coreSources");
        Objects.requireNonNull(customContainers, "customContainers");
        List<CompanionPopulationEvidenceSource> combined = new ArrayList<>(coreSources);
        combined.addAll(customContainers.sources());
        validateUniqueSources(combined);
        this.sources = List.copyOf(combined);
        EnumMap<CompanionPopulationCoverageRecord.Dimension, Boolean> declarations =
                new EnumMap<>(CompanionPopulationCoverageRecord.Dimension.class);
        declarations.put(CompanionPopulationCoverageRecord.Dimension.PROFILE_STATE, profileCatalogSealed);
        declarations.put(CompanionPopulationCoverageRecord.Dimension.WORLD_ENTITIES, worldEntityCatalogSealed);
        declarations.put(CompanionPopulationCoverageRecord.Dimension.PLAYER_SAVES, playerCatalogSealed);
        declarations.put(CompanionPopulationCoverageRecord.Dimension.BASE_CONTAINER_BLOCKS, baseContainerCatalogSealed);
        declarations.put(CompanionPopulationCoverageRecord.Dimension.CUSTOM_CONTAINERS, customContainers.sealed());
        this.sealed = Map.copyOf(declarations);
    }

    @Nonnull
    public List<CompanionPopulationEvidenceSource> sources() {
        return sources;
    }

    public boolean sealed(@Nonnull CompanionPopulationCoverageRecord.Dimension dimension) {
        return Boolean.TRUE.equals(sealed.get(dimension));
    }

    @Nonnull
    public List<CompanionPopulationEvidenceSource> sources(
            @Nonnull CompanionPopulationCoverageRecord.Dimension dimension
    ) {
        return sources.stream()
                .filter(source -> source.descriptor().dimension() == dimension)
                .toList();
    }

    @Nonnull
    public String generation(@Nonnull CompanionPopulationCoverageRecord.Dimension dimension) {
        List<String> values = new ArrayList<>();
        for (CompanionPopulationEvidenceSource source : sources(dimension)) {
            values.add(source.descriptor().coverageKey() + "=" + source.descriptor().scanGeneration());
        }
        values.add("sealed=" + sealed(dimension));
        return ReconciliationGeneration.forStrings("catalog:" + dimension.name(), values);
    }

    @Nonnull
    public Set<String> activeCoverageKeys(@Nonnull Map<CompanionPopulationCoverageRecord.Dimension, String> sentinels) {
        Set<String> keys = new HashSet<>();
        for (CompanionPopulationEvidenceSource source : sources) {
            keys.add(source.descriptor().coverageKey());
        }
        keys.addAll(sentinels.values());
        return Set.copyOf(keys);
    }

    private static void validateUniqueSources(@Nonnull List<CompanionPopulationEvidenceSource> sources) {
        Set<String> keys = new HashSet<>();
        for (CompanionPopulationEvidenceSource source : sources) {
            Objects.requireNonNull(source, "source");
            CompanionPopulationCoverageRecord.Dimension dimension = source.descriptor().dimension();
            if (dimension != CompanionPopulationCoverageRecord.Dimension.PROFILE_STATE
                    && dimension != CompanionPopulationCoverageRecord.Dimension.WORLD_ENTITIES
                    && dimension != CompanionPopulationCoverageRecord.Dimension.PLAYER_SAVES
                    && dimension != CompanionPopulationCoverageRecord.Dimension.BASE_CONTAINER_BLOCKS
                    && dimension != CompanionPopulationCoverageRecord.Dimension.CUSTOM_CONTAINERS) {
                throw new IllegalArgumentException("Evidence source uses a non-scannable dimension: " + dimension);
            }
            if (!keys.add(source.descriptor().coverageKey())) {
                throw new IllegalArgumentException(
                        "Duplicate reconciliation coverage key: " + source.descriptor().coverageKey()
                );
            }
        }
    }
}
