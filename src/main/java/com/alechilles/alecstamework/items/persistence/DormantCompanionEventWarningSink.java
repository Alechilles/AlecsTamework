package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Receives bounded best-effort event-publication warnings. */
@FunctionalInterface
public interface DormantCompanionEventWarningSink {
    void warn(@Nonnull Warning warning);

    /** Immutable warning without retained storage, ECS, player, or exception objects. */
    record Warning(
            @Nonnull String code,
            @Nonnull ProfileId profileId,
            @Nonnull String message
    ) {
        public Warning {
            if (code == null || code.isBlank()
                    || message == null || message.isBlank()) {
                throw new IllegalArgumentException(
                        "Dormant event warning code and message are required"
                );
            }
            code = code.trim();
            message = message.trim();
            Objects.requireNonNull(profileId, "Warning profile is required");
        }
    }
}
