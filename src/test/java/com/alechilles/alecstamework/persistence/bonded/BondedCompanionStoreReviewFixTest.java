package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionDatabase;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for the reviewed bonded storage contract corrections. */
class BondedCompanionStoreReviewFixTest {
    private static final UUID OWNER =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID NPC_A =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID NPC_B =
            UUID.fromString("20000000-0000-0000-0000-000000000002");

    @TempDir
    Path tempDir;

    private Path database;
    private BondedCompanionStore store;

    @BeforeEach
    void setUp() {
        database = tempDir.resolve("bonded-companions.sqlite");
        assertTrue(new BondedCompanionSchemaManager(database, () -> -20_000L)
                .initialize().availability().available());
        store = new SqliteBondedCompanionDatabase(database);
    }

    @Test
    void publicContractIsAdapterNeutralAndUnsafeStoreIsNotPublic() throws Exception {
        Class<?> unsafe = Class.forName(
                "com.alechilles.alecstamework.persistence.adapter.sqlite."
                        + "SqliteBondedCompanionStore");
        assertFalse(Modifier.isPublic(unsafe.getModifiers()));
        Arrays.stream(BondedCompanionStore.class.getMethods()).forEach(method -> {
            assertFalse(method.getReturnType().getName().contains(".adapter.sqlite."));
            Arrays.stream(method.getParameterTypes()).forEach(parameter ->
                    assertFalse(parameter.getName().contains(".adapter.sqlite."))
            );
        });
        assertEquals(
                Set.of(Path.class),
                Arrays.stream(SqliteBondedCompanionDatabase.class
                                .getConstructors())
                        .flatMap(constructor ->
                                Arrays.stream(constructor.getParameterTypes()))
                        .collect(java.util.stream.Collectors.toSet())
        );
    }

    @Test
    void atomicMutationReplaysTerminalResultIgnoringAttemptTimestamps() {
        var profile = profile("profile-a");
        var first = store.createProfile(
                operation("create-a", "a".repeat(64), "profile-a",
                        BondedCompanionOperation.Type.PROVISION, -10_000L, 10_000L),
                profile
        );
        var replay = store.createProfile(
                operation("create-a", "a".repeat(64), "profile-a",
                        BondedCompanionOperation.Type.PROVISION, -9_000L, 20_000L),
                profile
        );
        var conflict = store.createProfile(
                operation("create-a", "b".repeat(64), "profile-a",
                        BondedCompanionOperation.Type.PROVISION, -8_000L, 30_000L),
                profile
        );

        assertEquals(BondedCompanionStoreResult.Code.APPLIED, first.code());
        assertEquals(first.value(), replay.value());
        assertEquals(first.code(), replay.code());
        assertTrue(replay.replayed());
        assertEquals(BondedCompanionStoreResult.Code.IDEMPOTENCY_CONFLICT,
                conflict.code());
        assertEquals(1, store.listProfiles(OWNER, "roster-a").size());
    }

    @Test
    void concurrentDuplicateOperationReturnsOneStoredTerminalResult()
            throws Exception {
        store.createProfile(
                operation("create-a", "a".repeat(64), "profile-a",
                        BondedCompanionOperation.Type.PROVISION, -10_000L, 10_000L),
                profile("profile-a")
        );
        CyclicBarrier start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<BondedCompanionStoreResult<BondedCompanionRecord.Lease>> one =
                    executor.submit(() -> {
                        start.await();
                        return store.acquireLease(
                                operation("summon-a", "c".repeat(64), "profile-a",
                                        BondedCompanionOperation.Type.SUMMON,
                                        -9_000L, 10_000L),
                                0, lease("profile-a", "lease-a", NPC_A)
                        );
                    });
            Future<BondedCompanionStoreResult<BondedCompanionRecord.Lease>> two =
                    executor.submit(() -> {
                        start.await();
                        return store.acquireLease(
                                operation("summon-a", "c".repeat(64), "profile-a",
                                        BondedCompanionOperation.Type.SUMMON,
                                        -8_000L, 20_000L),
                                0, lease("profile-a", "lease-a", NPC_A)
                        );
                    });

            var results = Set.of(one.get(), two.get());
            assertTrue(results.stream().allMatch(result ->
                    result.code() == BondedCompanionStoreResult.Code.APPLIED));
            assertEquals(1, results.stream().filter(
                    BondedCompanionStoreResult::replayed).count());
        }
    }

    @Test
    void concurrentDifferentLeaseRequestsCannotBothSucceed() throws Exception {
        store.createProfile(
                operation("create-b", "d".repeat(64), "profile-b",
                        BondedCompanionOperation.Type.PROVISION, -10_000L, 10_000L),
                profile("profile-b")
        );
        CyclicBarrier start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<BondedCompanionStoreResult<BondedCompanionRecord.Lease>> one =
                    executor.submit(() -> {
                        start.await();
                        return store.acquireLease(
                                operation("summon-b1", "e".repeat(64), "profile-b",
                                        BondedCompanionOperation.Type.SUMMON,
                                        -9_000L, 10_000L),
                                0, lease("profile-b", "lease-b1", NPC_A)
                        );
                    });
            Future<BondedCompanionStoreResult<BondedCompanionRecord.Lease>> two =
                    executor.submit(() -> {
                        start.await();
                        return store.acquireLease(
                                operation("summon-b2", "f".repeat(64), "profile-b",
                                        BondedCompanionOperation.Type.SUMMON,
                                        -9_000L, 10_000L),
                                0, lease("profile-b", "lease-b2", NPC_B)
                        );
                    });

            Set<BondedCompanionStoreResult.Code> codes =
                    Set.of(one.get().code(), two.get().code());
            assertTrue(codes.contains(BondedCompanionStoreResult.Code.APPLIED));
            assertTrue(codes.contains(
                    BondedCompanionStoreResult.Code.REVISION_CONFLICT));
        }
        assertEquals(1, store.findActiveLeases(OWNER, "roster-a").size());
    }

    @Test
    void concurrentReviveRequiresExactlyOneRevisionFencedUpdate()
            throws Exception {
        store.createProfile(
                operation("create-c", "1".repeat(64), "profile-c",
                        BondedCompanionOperation.Type.PROVISION, -10_000L, 10_000L),
                profile("profile-c")
        );
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE bonded_companion_profile
                    SET state = 'DEAD', revision = 1, died_at_ms = -9000
                    WHERE profile_id = 'profile-c'
                    """);
        }
        CyclicBarrier start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<BondedCompanionStoreResult<BondedCompanionRecord.Profile>> one =
                    executor.submit(() -> {
                        start.await();
                        return store.reviveProfile(
                                operation("revive-c1", "2".repeat(64), "profile-c",
                                        BondedCompanionOperation.Type.REVIVE,
                                        -8_000L, 10_000L),
                                1, -8_000L
                        );
                    });
            Future<BondedCompanionStoreResult<BondedCompanionRecord.Profile>> two =
                    executor.submit(() -> {
                        start.await();
                        return store.reviveProfile(
                                operation("revive-c2", "3".repeat(64), "profile-c",
                                        BondedCompanionOperation.Type.REVIVE,
                                        -8_000L, 10_000L),
                                1, -8_000L
                        );
                    });

            Set<BondedCompanionStoreResult.Code> codes =
                    Set.of(one.get().code(), two.get().code());
            assertTrue(codes.contains(BondedCompanionStoreResult.Code.APPLIED));
            assertTrue(codes.contains(
                    BondedCompanionStoreResult.Code.REVISION_CONFLICT));
        }
        assertEquals(1L, store.findProfile(OWNER, "roster-a", "profile-c")
                .orElseThrow().reviveCount());
    }

    @Test
    void boundedRetentionRejectsZeroButPreservesNegativeWorldTime() {
        assertThrows(IllegalArgumentException.class, () -> operation(
                "zero", "4".repeat(64), "profile-a",
                BondedCompanionOperation.Type.PROVISION, -10_000L, 0L
        ));
        assertThrows(IllegalArgumentException.class, () -> cleanup(0L));
        assertEquals(-1L, cleanup(-1L).retainedUntilMs());
    }

    private BondedCompanionRecord.Profile profile(String profileId) {
        return new BondedCompanionRecord.Profile(
                profileId, OWNER, "roster-a", "family:wolf",
                "role:companion", BondedCompanionState.STORED, 0,
                BondedCompanionPayload.of("full-snapshot"
                        .getBytes(StandardCharsets.UTF_8)),
                -10_000L, -10_000L, Map.of("policy", "unlimited"),
                "Wolf", "Wolf", "Female", null, 0L, 0L,
                null, null
        );
    }

    private BondedCompanionRecord.Lease lease(
            String profileId,
            String token,
            UUID npc
    ) {
        return new BondedCompanionRecord.Lease(
                profileId, token, npc, "world-a", -9_000L, 0L,
                BondedCompanionRecord.ProjectionState.LIVE
        );
    }

    private BondedCompanionRecord.Cleanup cleanup(long retainedUntilMs) {
        return new BondedCompanionRecord.Cleanup(
                "cleanup-a", OWNER, "roster-a", "profile-a", null,
                BondedCompanionRecord.CleanupTarget.PROJECTION, NPC_A,
                "world-a", "stale-projection",
                BondedCompanionRecord.CleanupState.COMPLETED,
                1, -8_000L, -9_000L, retainedUntilMs
        );
    }

    private BondedCompanionOperation operation(
            String key,
            String hash,
            String profileId,
            BondedCompanionOperation.Type type,
            long attemptedAtMs,
            long retainedUntilMs
    ) {
        return new BondedCompanionOperation(
                "review-test", key, hash, OWNER, "roster-a", profileId,
                type, attemptedAtMs, retainedUntilMs
        );
    }
}
