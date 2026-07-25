package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Post-commit notification for one profile's durable group-classification change. */
public record PopulationGroupMembershipChangedEvent(@Nonnull UUID operationId,
                                                    @Nonnull String profileId,
                                                    @Nonnull UUID ownerUuid,
                                                    @Nonnull String roleId,
                                                    @Nonnull Set<String> oldGroupIds,
                                                    @Nonnull Set<String> newGroupIds,
                                                    long oldClassificationRevision,
                                                    long newClassificationRevision,
                                                    boolean recovered,
                                                    long changedAtMs,
                                                    long emittedAtMs) implements TameworkEvent {
    public PopulationGroupMembershipChangedEvent {
        operationId = Objects.requireNonNull(operationId, "operationId");
        profileId = requireText(profileId, "profileId");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        roleId = requireText(roleId, "roleId");
        oldGroupIds = copyIds(oldGroupIds);
        newGroupIds = copyIds(newGroupIds);
        if (oldClassificationRevision < 0L || newClassificationRevision < oldClassificationRevision) {
            throw new IllegalArgumentException("Classification revisions are invalid.");
        }
    }

    private static Set<String> copyIds(Set<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        return values.stream().map(value -> requireText(value, "groupId"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required.");
        return normalized;
    }
}
