package com.alechilles.alecstamework.ownership.groups;

import com.alechilles.alecstamework.api.PopulationGroupDefinitionView;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Derives deterministic old-release/new-admission group deltas from canonical facts. */
public final class PopulationGroupCountDeltaPlanner {
    private final PopulationGroupIndex index;

    public PopulationGroupCountDeltaPlanner(@Nonnull PopulationGroupIndex index) {
        this.index = Objects.requireNonNull(index, "index");
    }

    public Map<PopulationGroupBucket, PopulationGroupCountDelta> plan(@Nonnull PopulationGroupTransition transition) {
        Objects.requireNonNull(transition, "transition");
        TreeMap<PopulationGroupBucket, PopulationGroupCountDelta> result = new TreeMap<>();
        accumulate(result, transition.oldOwnerUuid(), transition.oldRoleId(), transition.oldOwnershipWorldName(),
                transition.oldLifecycle(), -1);
        accumulate(result, transition.newOwnerUuid(), transition.newRoleId(), transition.newOwnershipWorldName(),
                transition.newLifecycle(), 1);
        result.entrySet().removeIf(entry -> entry.getValue().isZero());
        return Map.copyOf(result);
    }

    private void accumulate(Map<PopulationGroupBucket, PopulationGroupCountDelta> target,
                            @Nullable UUID ownerUuid,
                            @Nullable String roleId,
                            @Nullable String ownershipWorldName,
                            @Nullable CompanionLifecycleState lifecycle,
                            int direction) {
        if (ownerUuid == null || roleId == null || roleId.isBlank() || lifecycle == null) return;
        int owned = PopulationGroupLifecycleClassifier.consumesOwned(lifecycle) ? direction : 0;
        int active = PopulationGroupLifecycleClassifier.consumesActive(lifecycle) ? direction : 0;
        if (owned == 0 && active == 0) return;
        for (PopulationGroupDefinitionView definition : index.resolveForRole(roleId)) {
            PopulationGroupBucket bucket = PopulationGroupBucket.of(ownerUuid, definition, ownershipWorldName);
            target.merge(bucket, new PopulationGroupCountDelta(owned, active), PopulationGroupCountDelta::plus);
        }
    }
}
