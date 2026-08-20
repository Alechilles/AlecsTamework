package com.alechilles.alecstamework.companion.population.domain;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Exact internal settlement result for one managed litter operation. */
public record ManagedBatchSettlement(
        @Nonnull Status status,
        @Nonnull String reason,
        int requestedUnits,
        @Nonnull Set<Integer> settledOrdinals,
        @Nonnull Map<Integer, UUID> actualChildIds
) {
    public ManagedBatchSettlement {
        if (status == null || reason == null || requestedUnits <= 0
                || settledOrdinals == null || actualChildIds == null
                || settledOrdinals.stream().anyMatch(ordinal ->
                ordinal == null || ordinal < 0 || ordinal >= requestedUnits)
                || !actualChildIds.keySet().equals(settledOrdinals)
                || actualChildIds.values().stream().anyMatch(java.util.Objects::isNull)
                || actualChildIds.values().stream().distinct().count()
                != actualChildIds.size()) {
            throw new IllegalArgumentException("Exact managed batch settlement is required");
        }
        reason = reason.trim();
        settledOrdinals = Set.copyOf(settledOrdinals);
        actualChildIds = Map.copyOf(actualChildIds);
    }

    public enum Status {
        COMMITTED,
        CANCELED,
        UNAVAILABLE
    }
}
