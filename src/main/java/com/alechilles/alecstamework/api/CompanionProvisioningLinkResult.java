package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Compound result; a successful result guarantees profile and roster membership committed together. */
public record CompanionProvisioningLinkResult(
        @Nonnull Status status,
        @Nonnull String reason,
        @Nonnull CompanionProvisioningResult provisioning,
        @Nullable CommandFamilyRosterView roster,
        @Nullable CommandFamilyRosterMembershipView membership,
        @Nullable CommandTimedSummoningResult initialProjection) {
    public CompanionProvisioningLinkResult {
        status = Objects.requireNonNull(status, "status");
        reason = Objects.requireNonNull(reason, "reason").trim();
        if (reason.isEmpty()) throw new IllegalArgumentException("reason is required.");
        provisioning = Objects.requireNonNull(provisioning, "provisioning");
        if ((status == Status.COMMITTED || status == Status.ALREADY_COMMITTED)
                && (!provisioning.accepted() || roster == null || membership == null)) {
            throw new IllegalArgumentException("Committed provision-and-link requires both authorities.");
        }
    }

    public boolean accepted() {
        return status == Status.COMMITTED || status == Status.ALREADY_COMMITTED;
    }

    public enum Status {
        COMMITTED,
        ALREADY_COMMITTED,
        DENIED,
        UNAVAILABLE,
        QUARANTINED
    }
}
