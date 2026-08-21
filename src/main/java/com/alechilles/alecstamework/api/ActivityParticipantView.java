package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Stable companion participant identity used by low-frequency activity payloads. */
public record ActivityParticipantView(
        @Nonnull UUID companionId,
        @Nullable UUID ownerId,
        @Nullable String profileId,
        @Nullable String roleId
) {
    public ActivityParticipantView {
        companionId = Objects.requireNonNull(companionId, "companionId");
        profileId = optionalText(profileId);
        roleId = optionalText(roleId);
    }

    public ActivityParticipantView(
            @Nonnull UUID companionId,
            @Nullable UUID ownerId
    ) {
        this(companionId, ownerId, null, null);
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
