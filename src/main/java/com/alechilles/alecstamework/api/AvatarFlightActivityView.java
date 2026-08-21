package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Typed payload for one accepted avatar-flight action. */
public record AvatarFlightActivityView(
        @Nonnull ActivityHeader header,
        @Nonnull UUID playerId,
        @Nonnull String flightConfigId,
        @Nullable String abilitySlot,
        @Nullable String rootInteractionId
) implements ActivityView {
    public AvatarFlightActivityView {
        header = Objects.requireNonNull(header, "header");
        playerId = Objects.requireNonNull(playerId, "playerId");
        flightConfigId = requireText(flightConfigId, "flightConfigId");
        abilitySlot = optionalText(abilitySlot);
        rootInteractionId = optionalText(rootInteractionId);
    }

    public AvatarFlightActivityView(
            @Nonnull ActivityHeader header,
            @Nonnull UUID playerId,
            @Nonnull String flightConfigId
    ) {
        this(header, playerId, flightConfigId, (String) null, null);
    }

    public AvatarFlightActivityView(
            @Nonnull ActivityHeader header,
            @Nonnull UUID playerId,
            @Nonnull String flightConfigId,
            @Nullable Integer abilitySlot,
            @Nullable String rootInteractionId
    ) {
        this(header, playerId, flightConfigId,
                abilitySlot == null ? null : Integer.toString(abilitySlot),
                rootInteractionId);
    }

    @Override
    @Nonnull
    public ActivityDomain domain() {
        return ActivityDomain.AVATAR_FLIGHT;
    }

    @Override
    @Nonnull
    public AvatarFlightActivityView withHeader(@Nonnull ActivityHeader nextHeader) {
        return new AvatarFlightActivityView(
                nextHeader, playerId, flightConfigId, abilitySlot, rootInteractionId);
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
}
