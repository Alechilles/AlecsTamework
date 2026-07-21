package com.alechilles.alecstamework.ownership.groups;

import com.alechilles.alecstamework.api.PopulationGroupDefinitionView;
import com.alechilles.alecstamework.api.PopulationGroupScope;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Stable owner/group/scope count key. */
public record PopulationGroupBucket(@Nonnull UUID ownerUuid,
                                    @Nonnull String groupId,
                                    @Nullable String ownershipWorldName) implements Comparable<PopulationGroupBucket> {
    public PopulationGroupBucket {
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        groupId = Objects.requireNonNull(groupId, "groupId").trim();
        if (groupId.isEmpty()) throw new IllegalArgumentException("groupId is required.");
        ownershipWorldName = ownershipWorldName == null || ownershipWorldName.isBlank()
                ? null : ownershipWorldName.trim();
    }

    public static PopulationGroupBucket of(UUID ownerUuid,
                                           PopulationGroupDefinitionView definition,
                                           @Nullable String ownershipWorldName) {
        Objects.requireNonNull(definition, "definition");
        String bucketWorld = definition.scope() == PopulationGroupScope.PER_WORLD
                ? requireWorld(ownershipWorldName) : null;
        return new PopulationGroupBucket(ownerUuid, definition.groupId(), bucketWorld);
    }

    private static String requireWorld(@Nullable String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Per-world population groups require authoritative ownership world.");
        }
        return value.trim();
    }

    @Override
    public int compareTo(PopulationGroupBucket other) {
        int owner = ownerUuid.toString().compareTo(other.ownerUuid.toString());
        if (owner != 0) return owner;
        int group = groupId.compareTo(other.groupId);
        if (group != 0) return group;
        if (ownershipWorldName == null) return other.ownershipWorldName == null ? 0 : -1;
        if (other.ownershipWorldName == null) return 1;
        return ownershipWorldName.compareTo(other.ownershipWorldName);
    }
}
