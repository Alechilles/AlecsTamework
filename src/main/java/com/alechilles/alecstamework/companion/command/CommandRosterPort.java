package com.alechilles.alecstamework.companion.command;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Canonical command-family and one-slot-per-profile persistence contract. */
public interface CommandRosterPort {
    Optional<CommandRoster> findRoster(
            @Nonnull CommandFamilyKey familyKey
    );

    Optional<CommandRosterMembership> findByProfile(
            @Nonnull ProfileId profileId
    );

    Optional<CommandRosterMembership> findBySlot(
            @Nonnull CommandRosterSlotId slotId
    );

    List<CommandRoster> findAllRosters();

    PersistenceMutationResult<CommandRosterMutationOutcome> upsert(
            long expectedRosterRevision,
            @Nullable Long expectedMembershipRevision,
            @Nonnull CommandRosterMembershipDraft target
    );

    PersistenceMutationResult<CommandRosterMutationOutcome> remove(
            long expectedRosterRevision,
            long expectedMembershipRevision,
            @Nonnull CommandFamilyKey familyKey,
            @Nonnull ProfileId profileId,
            long changedAtMs
    );
}

