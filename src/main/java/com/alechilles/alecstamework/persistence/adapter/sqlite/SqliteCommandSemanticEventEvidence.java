package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipChangeEvidence;
import com.alechilles.alecstamework.companion.command.CommandRosterMutationOutcome;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseChange;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseChangeEvidence;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;

/**
 * Authors replay-complete command events from facts in the durable transaction.
 *
 * <p>These reads occur before the outbox insert in the same SQLite
 * transaction. Delivery-time observers never join mutable canonical tables.</p>
 */
final class SqliteCommandSemanticEventEvidence {
    private SqliteCommandSemanticEventEvidence() {
    }

    static CommandRosterMembershipChangeEvidence roster(
            SqlitePersistenceTransactionContext transaction,
            CommandRosterMutationOutcome mutation,
            CompanionLifecycle lifecycle,
            CommandRosterMembershipChangeEvidence.Reason reason
    ) {
        CompanionIdentity identity = identity(
                transaction,
                mutation.after() == null
                        ? mutation.before()
                        : mutation.after()
        );
        return CommandRosterMembershipChangeEvidence.from(
                mutation, identity, lifecycle, reason
        );
    }

    static TimedSummonLeaseChangeEvidence timed(
            SqlitePersistenceTransactionContext transaction,
            TimedSummonLeaseChange change,
            CompanionLifecycle previousLifecycle,
            CompanionLifecycle currentLifecycle,
            TimedSummonLeaseChangeEvidence.Reason reason
    ) {
        CommandRosterMembership membership =
                transaction.commandRosters()
                        .findByProfile(change.after().profileId())
                        .orElseThrow(() -> new IllegalStateException(
                                "timed_summon_event_roster_missing"
                        ));
        CompanionIdentity identity = identity(
                transaction, membership
        );
        return TimedSummonLeaseChangeEvidence.from(
                change,
                membership,
                identity,
                previousLifecycle,
                currentLifecycle,
                reason
        );
    }

    private static CompanionIdentity identity(
            SqlitePersistenceTransactionContext transaction,
            CommandRosterMembership membership
    ) {
        return transaction.identities()
                .findProfile(membership.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "command_event_identity_missing"
                ));
    }
}
