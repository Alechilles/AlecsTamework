package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.identity.ProfileId;

/** Shared exact command-membership evidence checks for composed operations. */
final class SqliteCommandRosterEvidence {
    private SqliteCommandRosterEvidence() {
    }

    static CommandRosterMembership requireExact(
            SqlitePersistenceTransactionContext transaction,
            ProfileId profileId,
            CommandFamilyKey familyKey,
            CommandRosterSlotId slotId,
            long membershipRevision
    ) {
        CommandRosterMembership membership =
                transaction.commandRosters()
                        .findByProfile(profileId)
                        .orElseThrow(() -> new IllegalStateException(
                                "command_roster_membership_missing"
                        ));
        if (!membership.familyKey().equals(familyKey)
                || !membership.slotId().equals(slotId)
                || membership.membershipRevision()
                != membershipRevision) {
            throw new IllegalStateException(
                    "command_roster_membership_source_mismatch"
            );
        }
        return membership;
    }
}
