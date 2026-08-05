package com.alechilles.alecstamework.companion.bonded.runtime;

import java.util.UUID;
import javax.annotation.Nullable;

final class ExpiryDismountRiderUuidResolver {
    private ExpiryDismountRiderUuidResolver() {
    }

    @Nullable
    static UUID resolve(
            @Nullable String rideRiderUuid,
            @Nullable String glideRiderUuid,
            @Nullable String avatarFlightRiderUuid
    ) {
        for (String riderUuid : new String[]{
                rideRiderUuid, glideRiderUuid, avatarFlightRiderUuid
        }) {
            if (riderUuid == null || riderUuid.isBlank()) continue;
            try {
                return UUID.fromString(riderUuid.trim());
            } catch (IllegalArgumentException ignored) {
                // Try the next mount representation.
            }
        }
        return null;
    }
}
