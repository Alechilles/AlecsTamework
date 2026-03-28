package com.alechilles.alecstamework.api;

import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public record NpcProfileView(@Nonnull String profileId,
                             @Nullable UUID currentNpcUuid,
                             @Nullable UUID ownerUuid,
                             @Nullable String ownerName,
                             @Nullable String roleId,
                             @Nullable String displayName,
                             @Nullable String customName,
                             boolean tamed,
                             @Nullable String coopId,
                             @Nullable Integer coopSlot,
                             @Nonnull Set<String> toolIds,
                             @Nonnull Set<String> activeSnapshotTypes,
                             long lastUpdatedAtMs) {
    public NpcProfileView {
        toolIds = Set.copyOf(toolIds);
        activeSnapshotTypes = Set.copyOf(activeSnapshotTypes);
    }
}

