package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonPolicy;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonSessionId;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SQLite contract tests for normalized timed summon lease authority. */
class SqliteTimedSummonLeaseStoreTest {
    private static final ProfileId PROFILE_A =
            ProfileId.parse("20000000-0000-0000-0000-000000000091");
    private static final ProfileId PROFILE_B =
            ProfileId.parse("20000000-0000-0000-0000-000000000092");
    private static final TimedSummonSessionId SESSION =
            new TimedSummonSessionId(new UUID(0, 91));

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;

    @BeforeEach
    void setUp() {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("timed-summon.db")
        );
        new SqliteSchemaV1Manager(connections, () -> -20_000)
                .initialize();
    }

    @Test
    void insertAndUpdatePreserveSignedTimeAndSessionEvidence()
            throws Exception {
        try (Connection connection = transaction()) {
            createProfile(connection, PROFILE_A);
            SqliteTimedSummonLeaseStore store =
                    new SqliteTimedSummonLeaseStore(connection);
            TimedSummonLease dormant = dormant(PROFILE_A, 1);

            assertTrue(store.replace(null, dormant).applied());
            assertEquals(
                    -2_000L,
                    store.find(PROFILE_A).orElseThrow().cooldownUntilMs()
            );

            TimedSummonLease active = new TimedSummonLease(
                    PROFILE_A,
                    2,
                    SESSION,
                    8_000L,
                    null,
                    policy(),
                    Set.of(5_000L),
                    -1_500L,
                    -5_000,
                    -1_500
            );
            assertTrue(store.replace(1L, active).applied());
            assertEquals(active, store.find(PROFILE_A).orElseThrow());
            assertEquals(List.of(active), store.findAll());
            connection.commit();
        }
    }

    @Test
    void staleRevisionAndDuplicateSessionAreRejected()
            throws Exception {
        try (Connection connection = transaction()) {
            createProfile(connection, PROFILE_A);
            createProfile(connection, PROFILE_B);
            SqliteTimedSummonLeaseStore store =
                    new SqliteTimedSummonLeaseStore(connection);
            assertTrue(store.replace(null, dormant(PROFILE_A, 1))
                    .applied());
            TimedSummonLease activeA = active(PROFILE_A, 2, SESSION);
            assertTrue(store.replace(1L, activeA).applied());

            assertEquals(
                    PersistenceMutationStatus.REVISION_MISMATCH,
                    store.replace(1L, activeA).status()
            );
            assertTrue(store.replace(null, dormant(PROFILE_B, 1))
                    .applied());
            assertEquals(
                    PersistenceMutationStatus.CONFLICT,
                    store.replace(
                            1L,
                            active(PROFILE_B, 2, SESSION)
                    ).status()
            );
            connection.commit();
        }
    }

    private TimedSummonLease dormant(
            ProfileId profileId,
            long revision
    ) {
        return new TimedSummonLease(
                profileId,
                revision,
                null,
                null,
                -2_000L,
                policy(),
                Set.of(),
                null,
                -5_000,
                -2_500
        );
    }

    private TimedSummonLease active(
            ProfileId profileId,
            long revision,
            TimedSummonSessionId sessionId
    ) {
        return new TimedSummonLease(
                profileId,
                revision,
                sessionId,
                9_000L,
                null,
                policy(),
                Set.of(),
                -1_000L,
                -5_000,
                -1_000
        );
    }

    private TimedSummonPolicy policy() {
        return new TimedSummonPolicy(
                "role:timed",
                2L,
                10_000,
                2_000,
                true,
                List.of(5_000L, 1_000L)
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
                        -5_000,
                        -5_000,
                        -5_000,
                        0
                )).applied());
    }

    private Connection transaction() throws Exception {
        Connection connection = connections.openWriterConnection();
        connection.setAutoCommit(false);
        return connection;
    }
}

