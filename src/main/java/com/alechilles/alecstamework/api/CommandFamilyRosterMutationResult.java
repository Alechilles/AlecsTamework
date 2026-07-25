package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable result and post-mutation snapshot for one roster operation. */
public record CommandFamilyRosterMutationResult(
        @Nonnull CommandFamilyRosterMutationStatus status,
        @Nullable String reason,
        @Nullable CommandFamilyRosterView roster,
        @Nullable CommandFamilyRosterMembershipView membership,
        boolean idempotentReplay) {
    public CommandFamilyRosterMutationResult {
        status = Objects.requireNonNull(status, "status");
        reason = reason == null || reason.isBlank() ? null : reason.trim();
    }

    public boolean accepted() {
        return status == CommandFamilyRosterMutationStatus.APPLIED
                || status == CommandFamilyRosterMutationStatus.IDEMPOTENT;
    }

    public static CommandFamilyRosterMutationResult unavailable(String reason) {
        return new CommandFamilyRosterMutationResult(
                CommandFamilyRosterMutationStatus.UNAVAILABLE, reason, null, null, false);
    }
}
