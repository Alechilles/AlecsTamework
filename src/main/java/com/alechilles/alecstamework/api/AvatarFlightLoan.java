package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** A player's current lent avatar flight. */
public record AvatarFlightLoan(@Nonnull UUID playerId, @Nonnull String lenderId, @Nonnull String configId) {
    public AvatarFlightLoan {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(lenderId, "lenderId");
        Objects.requireNonNull(configId, "configId");
    }
}
