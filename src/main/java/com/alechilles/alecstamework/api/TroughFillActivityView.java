package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Typed payload for a player putting food or water in a feed trough.
 *
 * <p>{@code resource} is {@link #FOOD} or {@link #WATER}. Food is whatever the player moved in: the
 * trough does not check it against any animal's diet.
 */
public record TroughFillActivityView(
        @Nonnull ActivityHeader header,
        @Nonnull UUID playerId,
        @Nonnull String resource
) implements ActivityView {
    public static final String FOOD = "food";
    public static final String WATER = "water";

    public TroughFillActivityView {
        header = Objects.requireNonNull(header, "header");
        playerId = Objects.requireNonNull(playerId, "playerId");
        resource = Objects.requireNonNull(resource, "resource").trim();
        if (resource.isEmpty()) {
            throw new IllegalArgumentException("resource is required.");
        }
    }

    @Override
    @Nonnull
    public ActivityDomain domain() {
        return ActivityDomain.MANAGED_CARE_PRODUCTION;
    }

    @Override
    @Nonnull
    public TroughFillActivityView withHeader(@Nonnull ActivityHeader nextHeader) {
        return new TroughFillActivityView(nextHeader, playerId, resource);
    }
}
