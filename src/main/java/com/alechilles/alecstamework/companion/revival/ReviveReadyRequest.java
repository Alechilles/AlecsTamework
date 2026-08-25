package com.alechilles.alecstamework.companion.revival;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import javax.annotation.Nonnull;

/** Immutable request to make the current generic death snapshot immediately revivable. */
public record ReviveReadyRequest(
        @Nonnull ProfileId profileId,
        @Nonnull OwnerId ownerId,
        long requestedAtMs
) {
    public ReviveReadyRequest {
        if (profileId == null || ownerId == null) {
            throw new IllegalArgumentException(
                    "Revive-ready profile and owner are required"
            );
        }
    }
}
