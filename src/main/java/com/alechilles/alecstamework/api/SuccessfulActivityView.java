package com.alechilles.alecstamework.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Immutable public view of one committed successful companion activity. */
public record SuccessfulActivityView(
        @Nonnull UUID operationId,
        long globalSequence,
        @Nonnull UUID ownerId,
        @Nonnull UUID companionId,
        @Nonnull String sourceRoleId,
        @Nonnull Set<String> groupIds,
        @Nonnull String profileId,
        @Nonnull String activityId,
        @Nonnull Map<String, Integer> itemQuantities,
        @Nonnull Instant committedAt
) {
    public SuccessfulActivityView {
        operationId = Objects.requireNonNull(operationId, "operationId");
        ownerId = Objects.requireNonNull(ownerId, "ownerId");
        companionId = Objects.requireNonNull(companionId, "companionId");
        sourceRoleId = requireText(sourceRoleId, "sourceRoleId");
        groupIds = immutableTextSet(groupIds, "groupIds");
        profileId = requireText(profileId, "profileId");
        activityId = requireText(activityId, "activityId");
        itemQuantities = immutablePositiveQuantities(itemQuantities);
        committedAt = Objects.requireNonNull(committedAt, "committedAt");
        if (globalSequence < 0L) {
            throw new IllegalArgumentException("globalSequence cannot be negative.");
        }
    }

    private static Set<String> immutableTextSet(Set<String> values, String field) {
        Set<String> source = Objects.requireNonNull(values, field);
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : source) {
            String canonical = requireText(value, field + " entry");
            if (!normalized.add(canonical)) {
                throw new IllegalArgumentException(
                        field + " contains duplicate canonical identifier: " + canonical
                );
            }
        }
        return Set.copyOf(normalized);
    }

    private static Map<String, Integer> immutablePositiveQuantities(
            Map<String, Integer> quantities
    ) {
        Map<String, Integer> source = Objects.requireNonNull(quantities, "itemQuantities");
        LinkedHashMap<String, Integer> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            String canonical = requireText(entry.getKey(), "itemQuantities key");
            Integer quantity = Objects.requireNonNull(entry.getValue(), "itemQuantities value");
            if (quantity <= 0) {
                throw new IllegalArgumentException("itemQuantities values must be positive.");
            }
            if (normalized.put(canonical, quantity) != null) {
                throw new IllegalArgumentException(
                        "itemQuantities contains duplicate canonical identifier: " + canonical
                );
            }
        }
        return Map.copyOf(normalized);
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }
}
