package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRoster;
import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.command.CommandRosterProjectionSeed;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadPriority;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import java.util.List;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Async read-lane access to canonical command families and slots. */
public final class SqliteCommandRosterReader {
    private static final PersistenceReadKind ROSTERS =
            new PersistenceReadKind("command_rosters");
    private static final PersistenceReadKind PROFILE =
            new PersistenceReadKind("command_roster_profile");

    private final SqliteReadExecutor reads;

    public SqliteCommandRosterReader(@Nonnull SqliteReadExecutor reads) {
        if (reads == null) {
            throw new IllegalArgumentException(
                    "Command roster read executor is required"
            );
        }
        this.reads = reads;
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<CommandRoster>>
    findRoster(@Nonnull CommandFamilyKey familyKey) {
        if (familyKey == null) {
            throw new IllegalArgumentException(
                    "Command family is required"
            );
        }
        return reads.execute(new SqliteReadCommand<>(
                ROSTERS,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> new SqliteCommandRosterStore(connection)
                        .findRoster(familyKey)
                        .<PersistenceReadResult<CommandRoster>>map(
                                roster -> PersistenceReadResult.found(
                                        roster, roster.rosterRevision()
                                )
                        )
                        .orElseGet(PersistenceReadResult::absent)
        ));
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<
            CommandRosterMembership>> findByProfile(
            @Nonnull ProfileId profileId
    ) {
        if (profileId == null) {
            throw new IllegalArgumentException(
                    "Command roster profile is required"
            );
        }
        return reads.execute(new SqliteReadCommand<>(
                PROFILE,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> new SqliteCommandRosterStore(connection)
                        .findByProfile(profileId)
                        .<PersistenceReadResult<
                                CommandRosterMembership>>map(
                                membership ->
                                        PersistenceReadResult.found(
                                                membership,
                                                membership
                                                        .membershipRevision()
                                        )
                        )
                        .orElseGet(PersistenceReadResult::absent)
        ));
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<List<CommandRoster>>>
    findAllRosters() {
        return reads.execute(new SqliteReadCommand<>(
                ROSTERS,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> {
                    List<CommandRoster> rosters =
                            new SqliteCommandRosterStore(connection)
                                    .findAllRosters();
                    return PersistenceReadResult.found(
                            rosters, rosters.size()
                    );
                }
        ));
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<
            List<CommandRosterProjectionSeed>>> findAllProjectionSeeds() {
        return reads.execute(new SqliteReadCommand<>(
                ROSTERS,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> {
                    SqliteCommandRosterStore rosters =
                            new SqliteCommandRosterStore(connection);
                    SqliteCompanionIdentityStore identities =
                            new SqliteCompanionIdentityStore(connection);
                    SqliteCompanionLifecycleStore lifecycles =
                            new SqliteCompanionLifecycleStore(connection);
                    java.util.ArrayList<CommandRosterProjectionSeed> seeds =
                            new java.util.ArrayList<>();
                    for (CommandRoster roster : rosters.findAllRosters()) {
                        for (CommandRosterMembership membership
                                : roster.memberships()) {
                            var identity = identities.findProfile(
                                    membership.profileId()
                            ).orElseThrow(() ->
                                    new IllegalStateException(
                                            "command_roster_profile_missing"
                                    ));
                            var lifecycle = lifecycles.findByProfile(
                                    membership.profileId()
                            ).orElseThrow(() ->
                                    new IllegalStateException(
                                            "command_roster_lifecycle_missing"
                                    ));
                            seeds.add(new CommandRosterProjectionSeed(
                                    membership,
                                    identity,
                                    identities.findCurrentAlias(
                                            membership.profileId()
                                    ).orElse(null),
                                    lifecycle
                            ));
                        }
                    }
                    return PersistenceReadResult.found(
                            List.copyOf(seeds), seeds.size()
                    );
                }
        ));
    }
}
