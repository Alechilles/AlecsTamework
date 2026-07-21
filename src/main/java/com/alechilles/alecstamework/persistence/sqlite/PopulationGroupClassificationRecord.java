package com.alechilles.alecstamework.persistence.sqlite;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Canonical, revision-pinned role-to-group classification for one companion profile. */
public record PopulationGroupClassificationRecord(
        @Nonnull String profileId,
        @Nullable String roleId,
        @Nonnull List<String> groupIds,
        long classificationRevision,
        @Nonnull Status status,
        @Nonnull String source,
        long createdAtMs,
        long updatedAtMs
) {
    public enum Status {
        RESOLVED,
        UNRESOLVED,
        OVER_CAP,
        QUARANTINED
    }

    public PopulationGroupClassificationRecord {
        profileId = requireText(profileId, "profileId");
        roleId = normalize(roleId);
        status = Objects.requireNonNull(status, "status");
        source = requireText(source, "source");
        if (classificationRevision < 0L) {
            throw new IllegalArgumentException("classificationRevision must be non-negative.");
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String groupId : Objects.requireNonNull(groupIds, "groupIds")) {
            normalized.add(requireText(groupId, "groupId"));
        }
        groupIds = List.copyOf(normalized);
        if (status != Status.UNRESOLVED && roleId == null) {
            throw new IllegalArgumentException("Resolved classifications require a roleId.");
        }
    }

    @Nonnull
    private static String requireText(@Nonnull String value, @Nonnull String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank.");
        return normalized;
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
