package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Typed payload for an autonomous food or water consumption state change. */
public record NeedSatisfiedActivityView(
        @Nonnull ActivityHeader header,
        @Nonnull UUID companionId,
        @Nonnull UUID ownerId,
        @Nonnull String profileId,
        @Nonnull Set<String> groupIds,
        @Nonnull String roleId,
        @Nonnull String mappedActivityId,
        @Nonnull String needType,
        @Nonnull String resourceSource,
        @Nonnull String resourceId,
        double previousValue,
        double currentValue,
        double restoredAmount,
        @Nullable CompanionXpOutcomeView companionXpOutcome,
        @Nullable CareCreditOutcomeView careCreditOutcome
) implements ActivityView {
    public NeedSatisfiedActivityView {
        header = Objects.requireNonNull(header, "header");
        companionId = Objects.requireNonNull(companionId, "companionId");
        ownerId = Objects.requireNonNull(ownerId, "ownerId");
        profileId = requireText(profileId, "profileId");
        groupIds = immutableTextSet(groupIds, "groupIds");
        roleId = requireText(roleId, "roleId");
        mappedActivityId = ActivityHeader.requireNamespacedText(
                mappedActivityId, "mappedActivityId");
        needType = requireText(needType, "needType");
        resourceSource = requireText(resourceSource, "resourceSource");
        resourceId = requireText(resourceId, "resourceId");
        if (!Double.isFinite(previousValue) || !Double.isFinite(currentValue)
                || !Double.isFinite(restoredAmount) || restoredAmount < 0.0) {
            throw new IllegalArgumentException("Need values must be finite and non-negative.");
        }
    }

    public NeedSatisfiedActivityView(
            @Nonnull ActivityHeader header,
            @Nonnull UUID companionId,
            @Nonnull UUID ownerId,
            @Nonnull String profileId,
            @Nonnull Set<String> groupIds,
            @Nonnull String roleId,
            @Nonnull String mappedActivityId,
            @Nonnull String needType,
            @Nonnull String resourceSource,
            @Nonnull String resourceId,
            double previousValue,
            double currentValue,
            double restoredAmount
    ) {
        this(header, companionId, ownerId, profileId, groupIds, roleId,
                mappedActivityId, needType, resourceSource, resourceId,
                previousValue, currentValue, restoredAmount, null, null);
    }

    @Override
    @Nonnull
    public ActivityDomain domain() {
        return ActivityDomain.MANAGED_CARE_PRODUCTION;
    }

    @Override
    @Nonnull
    public NeedSatisfiedActivityView withHeader(@Nonnull ActivityHeader nextHeader) {
        return new NeedSatisfiedActivityView(
                nextHeader, companionId, ownerId, profileId, groupIds, roleId,
                mappedActivityId, needType, resourceSource, resourceId,
                previousValue, currentValue, restoredAmount,
                companionXpOutcome, careCreditOutcome);
    }

    @Nullable
    public CompanionXpOutcomeView xpOutcome() {
        return companionXpOutcome;
    }

    private static Set<String> immutableTextSet(Set<String> values, String field) {
        Set<String> source = Objects.requireNonNull(values, field);
        if (source.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty.");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : source) {
            if (!normalized.add(requireText(value, field + " entry"))) {
                throw new IllegalArgumentException(field + " contains a duplicate identifier.");
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
