package com.alechilles.alecstamework.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Immutable authoritative roster snapshot for one owner and command family. */
public record CommandFamilyRosterView(
        @Nonnull UUID ownerUuid,
        @Nonnull String commandFamilyId,
        long revision,
        @Nonnull List<CommandFamilyRosterMembershipView> memberships,
        long updatedAtMs) {
    public CommandFamilyRosterView {
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        commandFamilyId = Objects.requireNonNull(commandFamilyId, "commandFamilyId").trim();
        if (commandFamilyId.isEmpty()) throw new IllegalArgumentException("commandFamilyId is required.");
        if (revision < 0L) throw new IllegalArgumentException("revision cannot be negative.");
        memberships = List.copyOf(Objects.requireNonNull(memberships, "memberships"));
    }
}
