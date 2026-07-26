package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Immutable revision-fenced request to commit a bonded revive quote. */
public record BondedCompanionReviveRequest(
        @Nonnull BondedCompanionActionRequest action,
        long quoteRevision
) {
    public BondedCompanionReviveRequest {
        action = Objects.requireNonNull(action, "action");
        if (quoteRevision < 0L) {
            throw new IllegalArgumentException(
                    "quoteRevision cannot be negative."
            );
        }
    }
}
