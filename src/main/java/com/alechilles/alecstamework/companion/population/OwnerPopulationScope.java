package com.alechilles.alecstamework.companion.population;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One normalized owner-capacity bucket derived from canonical lifecycle ownership.
 *
 * @param kind global or per-world capacity
 * @param ownerId owner whose capacity is measured
 * @param ownerWorldKey authoritative owner world for a per-world bucket
 */
public record OwnerPopulationScope(
        @Nonnull Kind kind,
        @Nonnull OwnerId ownerId,
        @Nullable String ownerWorldKey
) implements Comparable<OwnerPopulationScope> {
    public OwnerPopulationScope {
        if (kind == null || ownerId == null) {
            throw new IllegalArgumentException("Population scope kind and owner are required");
        }
        ownerWorldKey = normalize(ownerWorldKey);
        if ((kind == Kind.GLOBAL) != (ownerWorldKey == null)) {
            throw new IllegalArgumentException(
                    "Only per-world population scopes carry an owner world"
            );
        }
    }

    /** Creates the owner's global capacity bucket. */
    @Nonnull
    public static OwnerPopulationScope global(@Nonnull OwnerId ownerId) {
        return new OwnerPopulationScope(Kind.GLOBAL, ownerId, null);
    }

    /** Creates one owner-world capacity bucket. */
    @Nonnull
    public static OwnerPopulationScope perWorld(
            @Nonnull OwnerId ownerId,
            @Nonnull String ownerWorldKey
    ) {
        return new OwnerPopulationScope(Kind.PER_WORLD, ownerId, ownerWorldKey);
    }

    /** Stable non-null SQL representation for the optional world component. */
    @Nonnull
    public String storedWorldKey() {
        return ownerWorldKey == null ? "" : ownerWorldKey;
    }

    @Override
    public int compareTo(OwnerPopulationScope other) {
        if (other == null) {
            throw new NullPointerException("Other population scope is required");
        }
        int ownerOrder = ownerId.toString().compareTo(other.ownerId.toString());
        if (ownerOrder != 0) {
            return ownerOrder;
        }
        int kindOrder = kind.compareTo(other.kind);
        return kindOrder != 0
                ? kindOrder
                : storedWorldKey().compareTo(other.storedWorldKey());
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Supported owner limit dimensions. */
    public enum Kind {
        GLOBAL,
        PER_WORLD
    }
}
