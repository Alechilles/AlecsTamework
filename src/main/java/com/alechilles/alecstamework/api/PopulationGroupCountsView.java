package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable committed/pending counts for one owner and group scope bucket. */
public record PopulationGroupCountsView(@Nonnull UUID ownerUuid,
                                        @Nonnull String groupId,
                                        @Nonnull PopulationGroupScope scope,
                                        @Nullable String ownershipWorldName,
                                        long committedOwned,
                                        long pendingOwned,
                                        long committedActive,
                                        long pendingActive,
                                        long maxOwned,
                                        long maxActive,
                                        boolean overOwnedLimit,
                                        boolean overActiveLimit,
                                        long classificationRevision) {
    public PopulationGroupCountsView {
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        groupId = requireText(groupId, "groupId");
        scope = Objects.requireNonNull(scope, "scope");
        ownershipWorldName = ownershipWorldName == null || ownershipWorldName.isBlank()
                ? null : ownershipWorldName.trim();
        if (scope == PopulationGroupScope.PER_WORLD && ownershipWorldName == null) {
            throw new IllegalArgumentException("Per-world counts require an ownership world.");
        }
        if (committedOwned < 0L || pendingOwned < 0L || committedActive < 0L || pendingActive < 0L
                || maxOwned < 0L || maxActive < 0L || classificationRevision < 0L) {
            throw new IllegalArgumentException("Population-group counts, limits, and revisions cannot be negative.");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required.");
        return normalized;
    }
}
