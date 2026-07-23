package com.alechilles.alecstamework.companion.population.group;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Exact owner/group/scope capacity bucket. */
public record PopulationGroupBucket(
        @Nonnull OwnerId ownerId,
        @Nonnull String groupId,
        @Nonnull PopulationGroupScope scope,
        @Nullable String ownerWorldKey
) implements Comparable<PopulationGroupBucket> {
    public PopulationGroupBucket {
        if (ownerId == null || groupId == null || groupId.isBlank()
                || scope == null) {
            throw new IllegalArgumentException(
                    "Complete population group bucket is required"
            );
        }
        groupId = groupId.trim();
        ownerWorldKey = normalize(ownerWorldKey);
        if ((scope == PopulationGroupScope.PER_WORLD)
                != (ownerWorldKey != null)) {
            throw new IllegalArgumentException(
                    "Only per-world group buckets carry an owner world"
            );
        }
    }

    @Override
    public int compareTo(PopulationGroupBucket other) {
        if (other == null) {
            throw new NullPointerException("Other group bucket is required");
        }
        int owner = ownerId.toString().compareTo(
                other.ownerId.toString()
        );
        if (owner != 0) {
            return owner;
        }
        int group = groupId.compareTo(other.groupId);
        if (group != 0) {
            return group;
        }
        int kind = scope.compareTo(other.scope);
        if (kind != 0) {
            return kind;
        }
        if (ownerWorldKey == null) {
            return other.ownerWorldKey == null ? 0 : -1;
        }
        return other.ownerWorldKey == null
                ? 1
                : ownerWorldKey.compareTo(other.ownerWorldKey);
    }

    @Nonnull
    public String storedWorldKey() {
        return ownerWorldKey == null ? "" : ownerWorldKey;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
