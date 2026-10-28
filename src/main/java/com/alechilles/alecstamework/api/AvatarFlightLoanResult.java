package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/** The outcome of a loan call. {@code detail} is an English diagnostic for logs, not player text. */
public record AvatarFlightLoanResult(@Nonnull Status status, @Nonnull String detail) {
    public AvatarFlightLoanResult {
        Objects.requireNonNull(status, "status");
        detail = detail == null ? "" : detail;
    }

    public boolean ok() {
        return status == Status.STARTED || status == Status.ENDED;
    }

    public enum Status {
        STARTED,
        ENDED,
        /** Loans are not available in this API composition. */
        UNAVAILABLE,
        /** The player ref is not a live player in this store. */
        INVALID_PLAYER,
        /** No enabled flight config has the requested id. */
        CONFIG_UNAVAILABLE,
        /** The player is dead, already in avatar flight (lent or their own), or riding a mount. */
        BUSY,
        /** The player has no loan from this lender. */
        NO_LOAN,
        /** Flight could not be started; {@code detail} says why. */
        FAILED
    }
}
