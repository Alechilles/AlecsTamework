package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Typed payload for a summoning, recall, or expiry lifecycle action. */
public record SummoningActivityView(
        @Nonnull ActivityHeader header,
        @Nonnull UUID ownerId,
        @Nonnull String profileId,
        @Nonnull String commandFamilyId,
        @Nullable UUID companionId,
        @Nonnull String lifecycleSource,
        @Nullable Long expiresAtMs
) implements ActivityView {
    public SummoningActivityView {
        header = Objects.requireNonNull(header, "header");
        ownerId = Objects.requireNonNull(ownerId, "ownerId");
        profileId = requireText(profileId, "profileId");
        commandFamilyId = requireText(commandFamilyId, "commandFamilyId");
        lifecycleSource = requireText(lifecycleSource, "lifecycleSource");
    }

    public SummoningActivityView(
            @Nonnull ActivityHeader header,
            @Nonnull UUID ownerId,
            @Nonnull String profileId,
            @Nonnull String commandFamilyId,
            @Nullable UUID companionId,
            @Nonnull String lifecycleSource
    ) {
        this(header, ownerId, profileId, commandFamilyId, companionId,
                lifecycleSource, null);
    }

    @Override
    @Nonnull
    public ActivityDomain domain() {
        return ActivityDomain.SUMMONING;
    }

    @Override
    @Nonnull
    public SummoningActivityView withHeader(@Nonnull ActivityHeader nextHeader) {
        return new SummoningActivityView(
                nextHeader, ownerId, profileId, commandFamilyId, companionId,
                lifecycleSource, expiresAtMs);
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }
}
