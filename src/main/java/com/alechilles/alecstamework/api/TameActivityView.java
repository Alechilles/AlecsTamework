package com.alechilles.alecstamework.api;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Typed payload for a committed wild-to-tamed acquisition. */
public record TameActivityView(
        @Nonnull ActivityHeader header,
        @Nonnull String profileId,
        @Nonnull Set<String> groupIds,
        @Nonnull String roleId,
        @Nonnull UUID ownerId,
        @Nonnull UUID companionId,
        @Nonnull String mappedActivityId
) implements ActivityView {
    public TameActivityView {
        header = Objects.requireNonNull(header, "header");
        profileId = requireText(profileId, "profileId");
        groupIds = immutableTextSet(groupIds);
        roleId = requireText(roleId, "roleId");
        ownerId = Objects.requireNonNull(ownerId, "ownerId");
        companionId = Objects.requireNonNull(companionId, "companionId");
        mappedActivityId = ActivityHeader.requireNamespacedText(
                mappedActivityId, "mappedActivityId");
    }

    @Override
    @Nonnull
    public ActivityDomain domain() {
        return ActivityDomain.TAMING;
    }

    @Override
    @Nonnull
    public TameActivityView withHeader(@Nonnull ActivityHeader nextHeader) {
        return new TameActivityView(
                nextHeader, profileId, groupIds, roleId, ownerId, companionId,
                mappedActivityId);
    }

    private static Set<String> immutableTextSet(Set<String> values) {
        Set<String> source = Objects.requireNonNull(values, "groupIds");
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : source) {
            String text = requireText(value, "groupIds entry");
            if (!normalized.add(text)) {
                throw new IllegalArgumentException("groupIds contains duplicate identifier.");
            }
        }
        return Set.copyOf(normalized);
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }
}
