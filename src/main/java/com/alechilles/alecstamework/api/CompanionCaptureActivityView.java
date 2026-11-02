package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Typed payload for a player capturing an animal into an item such as a Soul Lantern.
 *
 * <p>{@code roleId} is the role the animal had when it was captured, so it tells a calf from a cow.
 */
public record CompanionCaptureActivityView(
        @Nonnull ActivityHeader header,
        @Nonnull UUID playerId,
        @Nonnull String roleId
) implements ActivityView {
    public CompanionCaptureActivityView {
        header = Objects.requireNonNull(header, "header");
        playerId = Objects.requireNonNull(playerId, "playerId");
        roleId = Objects.requireNonNull(roleId, "roleId").trim();
        if (roleId.isEmpty()) {
            throw new IllegalArgumentException("roleId is required.");
        }
    }

    @Override
    @Nonnull
    public ActivityDomain domain() {
        return ActivityDomain.MANAGED_CARE_PRODUCTION;
    }

    @Override
    @Nonnull
    public CompanionCaptureActivityView withHeader(@Nonnull ActivityHeader nextHeader) {
        return new CompanionCaptureActivityView(nextHeader, playerId, roleId);
    }
}
