package com.alechilles.alecstamework.api;

import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public record NpcDeathRecordedEvent(@Nullable NpcProfileView profile,
                                    @Nonnull UUID npcUuid,
                                    @Nullable UUID ownerUuid,
                                    @Nullable String ownerName,
                                    @Nonnull Set<String> toolIds,
                                    @Nullable String roleId,
                                    @Nullable String displayName,
                                    @Nullable String customName,
                                    boolean tamed,
                                    @Nullable Vector3View lastKnownPosition,
                                    @Nullable Vector3View homePosition,
                                    long diedAtMs,
                                    long respawnAvailableAtMs,
                                    long emittedAtMs) implements TameworkEvent {
    public NpcDeathRecordedEvent {
        toolIds = Set.copyOf(toolIds);
    }
}

