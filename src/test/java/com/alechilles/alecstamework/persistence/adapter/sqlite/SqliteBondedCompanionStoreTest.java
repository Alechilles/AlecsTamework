package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshotCodec;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionPayload;
import java.nio.charset.StandardCharsets;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionSchemaManager;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavioral contract tests for owner-scoped bonded profile and lease storage. */
class SqliteBondedCompanionStoreTest {
    private static final UUID OWNER_A =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OWNER_B =
            UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID NPC_A =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID NPC_B =
            UUID.fromString("20000000-0000-0000-0000-000000000002");

    @TempDir
    Path tempDir;

    private Connection connection;
    private SqliteBondedCompanionStore store;

    @BeforeEach
    void setUp() {
        SqliteConnectionFactory connections = new SqliteConnectionFactory(
                tempDir.resolve("bonded-companions.sqlite")
        );
        assertTrue(new BondedCompanionSchemaManager(
                connections.databasePath(), () -> -20_000L)
                .initialize().availability().available());
        try {
            connection = connections.openWriterConnection();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        store = new SqliteBondedCompanionStore(connection);
    }

    @AfterEach
    void closeConnection() throws Exception {
        connection.close();
    }

    @Test
    void createsAndListsOnlyProfilesInTheExactOwnerRosterScope() {
        SqliteBondedCompanionProfileRow alpha = profile(
                "profile-a", OWNER_A, "roster-a", -10_000L
        );
        SqliteBondedCompanionProfileRow beta = profile(
                "profile-b", OWNER_A, "roster-b", -9_000L
        );
        SqliteBondedCompanionProfileRow foreign = profile(
                "profile-c", OWNER_B, "roster-a", -8_000L
        );

        assertEquals(SqliteBondedCompanionStore.MutationCode.APPLIED,
                store.createProfile(alpha).code());
        assertEquals(SqliteBondedCompanionStore.MutationCode.APPLIED,
                store.createProfile(beta).code());
        assertEquals(SqliteBondedCompanionStore.MutationCode.APPLIED,
                store.createProfile(foreign).code());

        assertEquals(List.of(alpha), store.listProfiles(OWNER_A, "roster-a"));
        assertEquals(List.of(), store.listProfiles(OWNER_B, "roster-b"));
        assertEquals(SqliteBondedCompanionStore.MutationCode.NOT_OWNER,
                store.updateSnapshot(
                        OWNER_B, "roster-a", "profile-a", 0,
                        "{\"encoding\":\"base64\",\"payload\":\"OQ==\"}", -7_000L
                ).code());
    }

    @Test
    void optimisticRevisionRejectsStaleProfileWritesAndEmptySnapshots() {
        SqliteBondedCompanionProfileRow initial = profile(
                "profile-a", OWNER_A, "roster-a", -10_000L
        );
        store.createProfile(initial);

        assertEquals(SqliteBondedCompanionStore.MutationCode.APPLIED,
                store.updateSnapshot(
                        OWNER_A, "roster-a", "profile-a", 0,
                        "{\"encoding\":\"base64\",\"payload\":\"OQ==\"}", -9_000L
                ).code());
        assertEquals(SqliteBondedCompanionStore.MutationCode.REVISION_CONFLICT,
                store.updateSnapshot(
                        OWNER_A, "roster-a", "profile-a", 0,
                        "{\"encoding\":\"base64\",\"payload\":\"OA==\"}", -8_000L
                ).code());
        assertEquals(1L, store.findProfile(
                OWNER_A, "roster-a", "profile-a"
        ).orElseThrow().revision());
        assertEquals(SqliteBondedCompanionStore.MutationCode.VALIDATION_FAILED,
                store.updateSnapshot(
                        OWNER_A, "roster-a", "profile-a", 1,
                        " ", -7_000L
                ).code());
        assertThrows(IllegalArgumentException.class, () ->
                new SqliteBondedCompanionProfileRow(
                        "empty", OWNER_A, "roster-a", "family:wolf",
                        "role:companion", BondedCompanionState.STORED, 0,
                        "{}", -10_000L, -10_000L, "{}", null, null,
                        null, null, 0L, 0L, null, null
                ));
    }

    @Test
    void extensionDataCompareAndSetIsOwnerScopedAndRevisionSafe() {
        store.createProfile(profile("profile-a", OWNER_A, "roster-a", -10_000L));
        SqliteBondedCompanionExtensionDataRow first =
                new SqliteBondedCompanionExtensionDataRow(
                        "profile-a", "example:stats", "{\"xp\":1}",
                        0, -9_000L
                );

        assertEquals(SqliteBondedCompanionStore.MutationCode.APPLIED,
                store.compareAndSetExtensionData(
                        OWNER_A, "roster-a", first, -1
                ).code());
        assertEquals(SqliteBondedCompanionStore.MutationCode.REVISION_CONFLICT,
                store.compareAndSetExtensionData(
                        OWNER_A, "roster-a",
                        new SqliteBondedCompanionExtensionDataRow(
                                "profile-a", "example:stats", "{\"xp\":2}",
                                1, -8_000L
                        ),
                        -1
                ).code());
        assertEquals(SqliteBondedCompanionStore.MutationCode.NOT_OWNER,
                store.compareAndSetExtensionData(
                        OWNER_B, "roster-a",
                        new SqliteBondedCompanionExtensionDataRow(
                                "profile-a", "example:stats", "{\"xp\":2}",
                                1, -8_000L
                        ),
                        0
                ).code());
        assertEquals(first, store.findExtensionData(
                OWNER_A, "roster-a", "profile-a", "example:stats"
        ).orElseThrow());
    }

    @Test
    void oneProfileCannotAcquireTwoLeasesAndExpirySupportsSignedTimeAndUnlimitedZero() {
        store.createProfile(profile("profile-a", OWNER_A, "roster-a", -10_000L));
        store.createProfile(profile("profile-b", OWNER_A, "roster-a", -9_000L));
        SqliteBondedCompanionLeaseRow lease = lease(
                "profile-a", "lease-a", NPC_A, -8_000L, -2_000L
        );

        assertEquals(SqliteBondedCompanionStore.MutationCode.APPLIED,
                store.acquireLease(OWNER_A, "roster-a", 0, lease).code());
        assertEquals(SqliteBondedCompanionStore.MutationCode.INVALID_STATE,
                store.acquireLease(
                        OWNER_A, "roster-a", 1,
                        lease("profile-a", "lease-b", NPC_B, -7_000L, 0L)
                ).code());
        assertEquals(SqliteBondedCompanionStore.MutationCode.CONFLICT,
                store.acquireLease(
                        OWNER_A, "roster-a", 0,
                        lease("profile-b", "lease-c", NPC_A, -7_000L, 0L)
                ).code());
        assertEquals(List.of(lease), store.findExpiredLeases(-1_000L, 10));
        assertEquals(List.of(), store.findExpiredLeases(-3_000L, 10));

        SqliteBondedCompanionLeaseRow unlimited = lease(
                "profile-b", "lease-unlimited", NPC_B, -7_000L, 0L
        );
        assertEquals(SqliteBondedCompanionStore.MutationCode.APPLIED,
                store.acquireLease(
                        OWNER_A, "roster-a", 0, unlimited
                ).code());
        assertTrue(unlimited.unlimited());
        assertEquals(List.of(lease), store.findExpiredLeases(50_000L, 10));
    }

    @Test
    void invalidTransitionsAreRejectedAndLeaseReleaseReturnsProfileToStored() {
        store.createProfile(profile("profile-a", OWNER_A, "roster-a", -10_000L));

        assertEquals(SqliteBondedCompanionStore.MutationCode.INVALID_STATE,
                store.reviveProfile(
                        OWNER_A, "roster-a", "profile-a", 0, -9_000L
                ).code());
        SqliteBondedCompanionLeaseRow lease = lease(
                "profile-a", "lease-a", NPC_A, -8_000L, 0L
        );
        store.acquireLease(OWNER_A, "roster-a", 0, lease);
        assertEquals(SqliteBondedCompanionStore.MutationCode.APPLIED,
                store.releaseLease(
                        OWNER_A, "roster-a", "profile-a", "lease-a",
                        1, -7_000L
                ).code());
        assertEquals(BondedCompanionState.STORED, store.findProfile(
                OWNER_A, "roster-a", "profile-a"
        ).orElseThrow().state());
    }

    @Test
    void paidReviveAtomicallyPersistsTheSummonSafeHealthSnapshot() {
        BondedCompanionSnapshotCodec snapshots = new BondedCompanionSnapshotCodec();
        BondedCompanionSnapshot deathSnapshot = BondedCompanionSnapshot.of(
                new CoopResidentStateSnapshot(NPC_A, null, -1,
                        "role:companion", null,
                        new TameworkOwnerComponent(OWNER_A, null), null, null,
                        null, null, null, null, null, null, null, null,
                        0.0D, 400.0D, 0.0D, -10_000L), java.util.Map.of());
        SqliteBondedCompanionMapper mapper = new SqliteBondedCompanionMapper();
        assertEquals(SqliteBondedCompanionStore.MutationCode.APPLIED,
                store.createProfile(new SqliteBondedCompanionProfileRow(
                "dead-health", OWNER_A, "roster-a", "family:wolf",
                "role:companion", BondedCompanionState.STORED, 0L,
                mapper.payloadJson(BondedCompanionPayload.of(snapshots.encode(
                        deathSnapshot).getBytes(StandardCharsets.UTF_8))),
                -10_000L, -10_000L, "{}", null, null, null, null,
                0L, 0L, null, null)).code());
        try (var statement = connection.prepareStatement("""
                UPDATE bonded_companion_profile
                SET state = 'DEAD', revision = 7, died_at_ms = -10_000
                WHERE profile_id = 'dead-health'
                """)) {
            assertEquals(1, statement.executeUpdate());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }

        assertEquals(SqliteBondedCompanionStore.MutationCode.APPLIED,
                store.reviveProfile(OWNER_A, "roster-a", "dead-health", 7L,
                        -9_000L).code());

        SqliteBondedCompanionProfileRow revived = store.findProfile(
                OWNER_A, "roster-a", "dead-health").orElseThrow();
        BondedCompanionSnapshot restored = snapshots.decode(new String(
                mapper.payload(revived.snapshotJson()).bytes(),
                StandardCharsets.UTF_8)).snapshot();
        assertEquals(BondedCompanionState.STORED, revived.state());
        assertEquals(400.0D, restored.fullState().currentHealth());
        assertEquals(400.0D, restored.fullState().maximumHealth());
        assertEquals(100.0D, restored.fullState().healthPercent());
    }

    @Test
    void cleanupAndOperationRetentionAreBoundedAndIdempotencyIsScoped() {
        store.createProfile(profile("profile-a", OWNER_A, "roster-a", -10_000L));
        SqliteBondedCompanionCleanupRow old = cleanup("cleanup-a", NPC_A, -6_000L);
        SqliteBondedCompanionCleanupRow future = cleanup("cleanup-b", NPC_B, 5_000L);
        assertEquals(SqliteBondedCompanionStore.MutationCode.APPLIED,
                store.enqueueCleanup(OWNER_A, "roster-a", old).code());
        assertEquals(SqliteBondedCompanionStore.MutationCode.APPLIED,
                store.enqueueCleanup(OWNER_A, "roster-a", future).code());
        assertEquals(1, store.pruneCleanup(0L, 1));
        assertEquals(List.of(future), store.listCleanup(OWNER_A, "roster-a", 10));

        String rejectedResult = """
                {"code":"CONFLICT","reason":"test-conflict",\
                "valueType":"PROFILE","value":null}
                """.replace("\\\n", "");
        SqliteBondedCompanionOperationRow operation =
                new SqliteBondedCompanionOperationRow(
                        "example", "request-1", OWNER_A, "roster-a",
                        "profile-a", "CAPTURE", "a".repeat(64), "REJECTED",
                        rejectedResult, -5_000L, -5_000L, 10_000L
                );
        assertEquals(SqliteBondedCompanionStore.MutationCode.APPLIED,
                store.recordOperation(operation).code());
        assertEquals(SqliteBondedCompanionStore.MutationCode.IDEMPOTENT_REPLAY,
                store.recordOperation(operation).code());
        assertEquals(SqliteBondedCompanionStore.MutationCode.CONFLICT,
                store.recordOperation(new SqliteBondedCompanionOperationRow(
                        "example", "request-1", OWNER_A, "roster-a",
                        "profile-a", "PROVISION", "b".repeat(64), "REJECTED",
                        rejectedResult, -4_000L, -4_000L, 10_000L
                )).code());
        assertEquals(SqliteBondedCompanionStore.MutationCode.APPLIED,
                store.recordOperation(new SqliteBondedCompanionOperationRow(
                        "example", "request-2", OWNER_A, "roster-a",
                        "profile-a", "STORE", "c".repeat(64), "REJECTED",
                        rejectedResult, -4_000L, -4_000L, 1_000L
                )).code());
        assertEquals(SqliteBondedCompanionStore.MutationCode.APPLIED,
                store.recordOperation(new SqliteBondedCompanionOperationRow(
                        "example", "request-3", OWNER_A, "roster-a",
                        "profile-a", "REVIVE", "d".repeat(64), "REJECTED",
                        rejectedResult, -3_000L, -3_000L, 1_000L
                )).code());
        assertEquals(2, store.pruneOperations(2_000L, 2));
        assertEquals(SqliteBondedCompanionStore.MutationCode.IDEMPOTENT_REPLAY,
                store.recordOperation(operation).code());
    }

    private SqliteBondedCompanionProfileRow profile(
            String id,
            UUID owner,
            String roster,
            long createdAt
    ) {
        return new SqliteBondedCompanionProfileRow(
                id, owner, roster, "family:wolf", "role:companion",
                BondedCompanionState.STORED, 0,
                "{\"encoding\":\"base64\",\"payload\":\"MTA=\"}",
                createdAt, createdAt, "{\"leaseMs\":0}", "Wolf",
                "Wolf", "Female", null, 0L, 0L, null, null
        );
    }

    private SqliteBondedCompanionLeaseRow lease(
            String profileId,
            String token,
            UUID npc,
            long startedAt,
            long expiresAt
    ) {
        return new SqliteBondedCompanionLeaseRow(
                profileId, token, npc, "world-a", startedAt, expiresAt,
                "LIVE"
        );
    }

    private SqliteBondedCompanionCleanupRow cleanup(
            String id,
            UUID npc,
            long retainedUntil
    ) {
        return new SqliteBondedCompanionCleanupRow(
                id, OWNER_A, "roster-a", "profile-a", null,
                "PROJECTION", npc, "world-a", "stale-projection", "COMPLETED",
                1, -7_000L, -7_000L, retainedUntil
        );
    }
}
