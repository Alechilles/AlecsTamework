package com.alechilles.alecstamework.api;

import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public record CommandLinkView(@Nonnull String profileId,
                              @Nullable UUID currentNpcUuid,
                              @Nullable UUID ownerUuid,
                              @Nonnull Set<String> toolIds,
                              boolean hasHomePosition,
                              @Nullable Vector3View homePosition,
                              @Nullable Vector3View lastKnownPosition,
                              @Nonnull Set<String> activeSnapshotTypes,
                              long lastUpdatedAtMs) {
    public CommandLinkView {
        toolIds = Set.copyOf(toolIds);
        activeSnapshotTypes = Set.copyOf(activeSnapshotTypes);
    }
}
