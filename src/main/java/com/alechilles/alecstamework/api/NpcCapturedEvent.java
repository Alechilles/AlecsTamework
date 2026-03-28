package com.alechilles.alecstamework.api;

import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public record NpcCapturedEvent(@Nullable NpcProfileView profile,
                               @Nonnull UUID npcUuid,
                               @Nullable UUID ownerUuid,
                               @Nonnull Set<String> toolIds,
                               @Nullable String roleId,
                               @Nullable String displayName,
                               @Nullable Vector3View lastKnownPosition,
                               @Nullable Vector3View homePosition,
                               long capturedAtMs,
                               long emittedAtMs) implements TameworkEvent {
    public NpcCapturedEvent {
        toolIds = Set.copyOf(toolIds);
    }
}

