package com.alechilles.alecstamework.companion.revival;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import javax.annotation.Nonnull;

/** Immutable request to make the current generic death snapshot immediately revivable. */
public record ReviveReadyRequest(@Nonnull ProfileId profileId, long requestedAtMs) {
    public ReviveReadyRequest {
        if (profileId == null) {
            throw new IllegalArgumentException("Profile ID is required");
        }
    }
}
