package com.alechilles.alecstamework.api;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Typed payload for a post-commit companion revival projection. */
public record RevivalActivityView(
        @Nonnull ActivityHeader header,
        @Nullable UUID actorId,
        @Nonnull UUID ownerId,
        @Nonnull UUID companionId,
        @Nonnull String profileId,
        @Nonnull String revivalSource,
        @Nonnull String resultingLifecycleState,
        @Nullable String paymentOutcome,
        boolean recovered,
        @Nullable String roleId,
        @Nonnull Set<String> groupIds
) implements ActivityView {
    public RevivalActivityView {
        header = Objects.requireNonNull(header, "header");
        ownerId = Objects.requireNonNull(ownerId, "ownerId");
        companionId = Objects.requireNonNull(companionId, "companionId");
        profileId = requireText(profileId, "profileId");
        revivalSource = requireText(revivalSource, "revivalSource");
        resultingLifecycleState = requireText(
                resultingLifecycleState, "resultingLifecycleState");
        paymentOutcome = optionalText(paymentOutcome);
        roleId = optionalText(roleId);
        groupIds = immutableTextSet(groupIds);
    }

    public RevivalActivityView(
            @Nonnull ActivityHeader header,
            @Nullable UUID actorId,
            @Nonnull UUID ownerId,
            @Nonnull UUID companionId,
            @Nonnull String profileId,
            @Nonnull String revivalSource,
            @Nonnull String resultingLifecycleState,
            @Nullable String paymentOutcome,
            boolean recovered
    ) {
        this(header, actorId, ownerId, companionId, profileId, revivalSource,
                resultingLifecycleState, paymentOutcome, recovered, null,
                Set.of());
    }

    public RevivalActivityView(
            @Nonnull ActivityHeader header,
            @Nullable UUID actorId,
            @Nonnull UUID ownerId,
            @Nonnull UUID companionId,
            @Nonnull String profileId,
            @Nonnull String revivalSource,
            @Nonnull String resultingLifecycleState,
            @Nullable String paymentOutcome
    ) {
        this(header, actorId, ownerId, companionId, profileId, revivalSource,
                resultingLifecycleState, paymentOutcome, false, null,
                Set.of());
    }

    @Override
    @Nonnull
    public ActivityDomain domain() {
        return ActivityDomain.REVIVAL;
    }

    @Override
    @Nonnull
    public RevivalActivityView withHeader(@Nonnull ActivityHeader nextHeader) {
        return new RevivalActivityView(
                nextHeader, actorId, ownerId, companionId, profileId,
                revivalSource, resultingLifecycleState, paymentOutcome,
                recovered, roleId, groupIds);
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Set<String> immutableTextSet(Set<String> values) {
        Set<String> source = Objects.requireNonNull(values, "groupIds");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : source) {
            if (!normalized.add(requireText(value, "groupIds entry"))) {
                throw new IllegalArgumentException(
                        "groupIds contains a duplicate identifier");
            }
        }
        return Set.copyOf(normalized);
    }
}
