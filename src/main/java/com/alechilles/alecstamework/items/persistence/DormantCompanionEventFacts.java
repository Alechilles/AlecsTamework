package com.alechilles.alecstamework.items.persistence;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Primitive-only released API event facts frozen with the dormant snapshot. */
public record DormantCompanionEventFacts(
        @Nonnull UUID npcUuid,
        @Nullable UUID ownerUuid,
        @Nullable String ownerName,
        @Nonnull Set<String> toolIds,
        @Nullable String snapshotRoleId,
        @Nullable String customName,
        boolean tamed,
        @Nullable DormantCompanionObservation.PositionObservation homePosition
) {
    public DormantCompanionEventFacts {
        Objects.requireNonNull(npcUuid, "Dormant event NPC is required");
        toolIds = Set.copyOf(toolIds);
        ownerName = normalize(ownerName);
        snapshotRoleId = normalize(snapshotRoleId);
        customName = normalize(customName);
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
