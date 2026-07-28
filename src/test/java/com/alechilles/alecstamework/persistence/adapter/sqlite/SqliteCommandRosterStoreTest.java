package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterHome;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDraft;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Normalized command family, slot uniqueness, and lifecycle-fence tests. */
class SqliteCommandRosterStoreTest {
    private static final ProfileId PROFILE_A =
            ProfileId.parse("20000000-0000-0000-0000-000000000081");
    private static final ProfileId PROFILE_B =
            ProfileId.parse("20000000-0000-0000-0000-000000000082");
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000081");
    private static final CommandFamilyKey FAMILY =
            new CommandFamilyKey(OWNER, "mod:companions");
    private static final CommandFamilyKey OTHER_FAMILY =
            new CommandFamilyKey(OWNER, "mod:other");
    private static final CommandRosterSlotId SLOT_A =
            slot(81);
    private static final CommandRosterSlotId SLOT_B =
            slot(82);

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;

    @BeforeEach
    void setUp() {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("command-roster.db")
        );
        new SqliteSchemaV1Manager(connections, () -> -20_000)
                .initialize();
    }

    @Test
    void upsertIsRevisionFencedAndStoresOnlyCommandPreferences()
            throws Exception {
        try (Connection connection = transaction()) {
            createProfile(connection, PROFILE_A);
            SqliteCommandRosterStore store =
                    new SqliteCommandRosterStore(connection);

            var added = store.upsert(
                    0, null, draft(PROFILE_A, FAMILY, SLOT_A, -19_000)
            );
            assertTrue(added.applied());
            assertEquals(1, added.value().currentRosterRevision());
            assertEquals(1, added.value().after().membershipRevision());
            assertEquals(
                    new CommandRosterHome("world-a", 1, 2, 3),
                    added.value().after().home()
            );

            var unchanged = store.upsert(
                    1, 1L, draft(PROFILE_A, FAMILY, SLOT_A, -18_000)
            );
            assertTrue(unchanged.applied());
            assertEquals(1, unchanged.value().currentRosterRevision());
            assertEquals(1, unchanged.value().after().membershipRevision());

            var updated = store.upsert(
                    1,
                    1L,
                    new CommandRosterMembershipDraft(
                            SLOT_A,
                            FAMILY,
                            PROFILE_A,
                            "group-b",
                            false,
                            null,
                            -17_000
                    )
            );
            assertTrue(updated.applied());
            assertEquals(2, updated.value().currentRosterRevision());
            assertEquals(2, updated.value().after().membershipRevision());
            assertEquals(
                    updated.value().after(),
                    store.findBySlot(SLOT_A).orElseThrow()
            );
            assertEquals(
                    1,
                    store.findRoster(FAMILY).orElseThrow()
                            .memberships().size()
            );
            connection.commit();
        }
    }

    @Test
    void oneProfileAndSlotCannotOccupyConflictingFamilies()
            throws Exception {
        try (Connection connection = transaction()) {
            createProfile(connection, PROFILE_A);
            createProfile(connection, PROFILE_B);
            SqliteCommandRosterStore store =
                    new SqliteCommandRosterStore(connection);
            assertTrue(store.upsert(
                    0, null, draft(PROFILE_A, FAMILY, SLOT_A, -19_000)
            ).applied());

            assertEquals(
                    PersistenceMutationStatus.FENCE_MISMATCH,
                    store.upsert(
                            0,
                            1L,
                            draft(
                                    PROFILE_A,
                                    OTHER_FAMILY,
                                    SLOT_B,
                                    -18_000
                            )
                    ).status()
            );
            assertEquals(
                    PersistenceMutationStatus.CONFLICT,
                    store.upsert(
                            1,
                            null,
                            draft(PROFILE_B, FAMILY, SLOT_A, -18_000)
                    ).status()
            );
            assertEquals(1, store.findAllRosters().size());
            connection.commit();
        }
    }

    @Test
    void rosterStoredLifecyclePreventsOrphaningItsSlot()
            throws Exception {
        try (Connection connection = transaction()) {
            createProfile(connection, PROFILE_A);
            SqliteCommandRosterStore store =
                    new SqliteCommandRosterStore(connection);
            assertTrue(store.upsert(
                    0, null, draft(PROFILE_A, FAMILY, SLOT_A, -19_000)
            ).applied());
            CompanionLifecycle current =
                    new SqliteCompanionLifecycleStore(connection)
                            .findByProfile(PROFILE_A).orElseThrow();
            assertTrue(new SqliteCompanionLifecycleStore(connection)
                    .transition(new LifecycleTransition(
                            current.revision(),
                            null,
                            new CompanionLifecycle(
                                    PROFILE_A,
                                    OWNER,
                                    LifecycleState.ROSTER_STORED,
                                    LifecycleLocation.keyed(
                                            LifecycleLocationKind
                                                    .COMMAND_ROSTER,
                                            SLOT_A.toString()
                                    ),
                                    current.revision().next(),
                                    null,
                                    -18_000,
                                    current.lastReconciledGeneration(),
                                    null,
                                    "world-a"
                            )
                    )).applied());

            assertEquals(
                    PersistenceMutationStatus.FENCE_MISMATCH,
                    store.remove(
                            1, 1, FAMILY, PROFILE_A, -17_000
                    ).status()
            );

            CompanionLifecycle stored =
                    new SqliteCompanionLifecycleStore(connection)
                            .findByProfile(PROFILE_A).orElseThrow();
            assertTrue(new SqliteCompanionLifecycleStore(connection)
                    .transition(new LifecycleTransition(
                            stored.revision(),
                            null,
                            new CompanionLifecycle(
                                    PROFILE_A,
                                    OWNER,
                                    LifecycleState.UNLOADED,
                                    LifecycleLocation.none(),
                                    stored.revision().next(),
                                    null,
                                    -16_000,
                                    stored.lastReconciledGeneration(),
                                    null,
                                    "world-a"
                            )
                    )).applied());
            assertTrue(store.remove(
                    1, 1, FAMILY, PROFILE_A, -15_000
            ).applied());
            assertTrue(store.findByProfile(PROFILE_A).isEmpty());
            assertEquals(
                    2,
                    store.findRoster(FAMILY).orElseThrow()
                            .rosterRevision()
            );
            connection.commit();
        }
    }

    private CommandRosterMembershipDraft draft(
            ProfileId profileId,
            CommandFamilyKey family,
            CommandRosterSlotId slot,
            long changedAtMs
    ) {
        return new CommandRosterMembershipDraft(
                slot,
                family,
                profileId,
                "group-a",
                true,
                new CommandRosterHome("world-a", 1, 2, 3),
                changedAtMs
        );
    }

    private void createProfile(
            Connection connection,
            ProfileId profileId
    ) {
        assertTrue(new SqliteCompanionIdentityStore(connection)
                .createProfile(new CompanionIdentity(
                        profileId,
                        "Companion",
                        "Mini",
                        null,
                        null,
                        "world-a",
                        -20_000,
                        -20_000,
                        -20_000,
                        0
                )).applied());
        assertTrue(new SqliteCompanionLifecycleStore(connection)
                .create(new CompanionLifecycle(
                        profileId,
                        OWNER,
                        LifecycleState.UNLOADED,
                        LifecycleLocation.none(),
                        LifecycleRevision.INITIAL,
                        null,
                        -20_000,
                        ReconciliationGeneration.INITIAL,
                        null,
                        "world-a"
                )).applied());
    }

    private Connection transaction() throws Exception {
        Connection connection = connections.openWriterConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private static CommandRosterSlotId slot(int number) {
        return new CommandRosterSlotId(new UUID(0, number));
    }
}

