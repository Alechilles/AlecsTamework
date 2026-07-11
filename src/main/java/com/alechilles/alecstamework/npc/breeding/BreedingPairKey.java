package com.alechilles.alecstamework.npc.breeding;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Canonical identity for a breeding pair inside one world.
 *
 * <p>Parent order is normalized so both caller orderings resolve to the same key. The world ID is
 * caller-supplied because the registry deliberately has no dependency on live world objects.
 */
public record BreedingPairKey(@Nonnull String worldId,
                              @Nonnull UUID firstParentUuid,
                              @Nonnull UUID secondParentUuid) {
    public BreedingPairKey {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(firstParentUuid, "firstParentUuid");
        Objects.requireNonNull(secondParentUuid, "secondParentUuid");
        worldId = worldId.trim();
        if (worldId.isEmpty()) {
            throw new IllegalArgumentException("worldId must not be blank");
        }
        if (firstParentUuid.equals(secondParentUuid)) {
            throw new IllegalArgumentException("A breeding pair requires two distinct parents");
        }
        if (secondParentUuid.compareTo(firstParentUuid) < 0) {
            UUID swap = firstParentUuid;
            firstParentUuid = secondParentUuid;
            secondParentUuid = swap;
        }
    }

    /** Creates a canonical key regardless of parent argument order. */
    @Nonnull
    public static BreedingPairKey of(@Nonnull String worldId,
                                     @Nonnull UUID parentAUuid,
                                     @Nonnull UUID parentBUuid) {
        return new BreedingPairKey(worldId, parentAUuid, parentBUuid);
    }

    /** Returns whether the entity UUID belongs to this pair. */
    public boolean contains(@Nonnull UUID parentUuid) {
        Objects.requireNonNull(parentUuid, "parentUuid");
        return firstParentUuid.equals(parentUuid) || secondParentUuid.equals(parentUuid);
    }
}
