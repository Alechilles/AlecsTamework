package com.alechilles.alecstamework.ownership;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static com.alechilles.alecstamework.ownership.OwnerPopulationCountOps.addCounts;
import static com.alechilles.alecstamework.ownership.OwnerPopulationTransitionDraft.scopeKeys;

/** Validated replacement maps used when bootstrapping the owner population index. */
record OwnerPopulationCommittedState(Map<String, OwnerPopulationEntry> entries,
                                     Map<OwnerPopulationScopeKey, Long> counts) {
    OwnerPopulationCommittedState {
        entries = Map.copyOf(entries);
        counts = Map.copyOf(counts);
    }

    static OwnerPopulationCommittedState from(Collection<OwnerPopulationEntry> source) {
        Objects.requireNonNull(source, "entries");
        Map<String, OwnerPopulationEntry> entries = new HashMap<>();
        Map<OwnerPopulationScopeKey, Long> counts = new HashMap<>();
        for (OwnerPopulationEntry entry : source) {
            Objects.requireNonNull(entry, "entry");
            if (entries.put(entry.profileId(), entry) != null) {
                throw new IllegalArgumentException("Duplicate profile entry: " + entry.profileId());
            }
            addCounts(counts, scopeKeys(entry));
        }
        return new OwnerPopulationCommittedState(entries, counts);
    }
}
