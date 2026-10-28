package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Sent after a loan has ended and the player has been put back (see {@link AvatarFlightLoanRequest}). */
public record AvatarFlightLoanEnded(@Nonnull AvatarFlightLoan loan, @Nonnull Reason reason) {
    public AvatarFlightLoanEnded {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(reason, "reason");
    }

    public enum Reason {
        /** The lender called {@code endLoan}. */
        LENDER,
        /** The player held the dismount input on the ground. */
        DISMOUNT,
        DEATH,
        DISCONNECT,
        WORLD_CHANGE,
        /** The flight config was removed or disabled mid-loan. */
        CONFIG_UNAVAILABLE,
        /** A loan saved before a restart is ended when the player loads. */
        SERVER_RESTART,
        OTHER
    }
}
