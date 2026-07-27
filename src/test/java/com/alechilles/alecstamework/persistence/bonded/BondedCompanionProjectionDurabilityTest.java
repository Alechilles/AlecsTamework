package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionCleanupService;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionActiveCapacity;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionService;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionValidator;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionDatabase;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionProjectionDurability;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards atomic Task 4 durability and restart-safe exact cleanup routing. */
class BondedCompanionProjectionDurabilityTest {
    private static final UUID OWNER = UUID.fromString(
            "10000000-0000-0000-0000-000000000006"
    );
    private static final UUID NPC = UUID.fromString(
            "20000000-0000-0000-0000-000000000006"
    );

    @TempDir
    Path temporaryDirectory;

    @Test
    void summonAndCleanupWorldRouteSurviveAdapterRestart() {
        Path database = temporaryDirectory.resolve("bonded.sqlite");
        assertTrue(new BondedCompanionSchemaManager(database, () -> -100L)
                .initialize().availability().available());
        SqliteBondedCompanionDatabase store =
                new SqliteBondedCompanionDatabase(database);
        assertEquals(BondedCompanionStoreResult.Code.APPLIED,
                store.createProfile(operation(), profile()).code());

        var lease = new BondedCompanionProjectionValidator.LeaseExpectation(
                OWNER, "roster-a", "profile-a", "lease-a", NPC,
                "world-a", -90L, 0L,
                BondedCompanionProjectionValidator.LeasePhase.PENDING
        );
        var request = new BondedCompanionProjectionService.SummonRequest(
                OWNER, "roster-a", "profile-a", 0L, "role:wolf",
                snapshot(), "world-a", null, -90L, 0L,
                new BondedCompanionActiveCapacity("family:wolf", 1)
        );
        var cleanup = BondedCompanionProjectionCleanupService.CleanupIntent
                .projection(
                        "cleanup-a", OWNER, "roster-a", "profile-a",
                        "lease-a", NPC, "world-a", "spawn-recovery", -1L
                );
        SqliteBondedCompanionProjectionDurability first =
                new SqliteBondedCompanionProjectionDurability(database);

        assertTrue(first.beginSummon(request, lease, cleanup));
        assertEquals("world-a", store.listCleanup(
                OWNER, "roster-a", 10).getFirst().worldKey());

        SqliteBondedCompanionProjectionDurability restarted =
                new SqliteBondedCompanionProjectionDurability(database);
        int recovered = restarted.replayPendingCleanup(
                new BondedCompanionProjectionCleanupService(
                        ignored -> BondedCompanionProjectionCleanupService
                                .Outcome.REMOVED
                ),
                -80L,
                10
        );

        assertEquals(1, recovered);
        assertEquals(BondedCompanionRecord.CleanupState.COMPLETED,
                store.listCleanup(OWNER, "roster-a", 10)
                        .getFirst().state());
        assertTrue(restarted.reconcileStored(
                lease, null, -20L, java.util.List.of(), "SPAWN_INTERRUPTED"
        ));
        assertEquals(BondedCompanionState.STORED,
                store.findProfile(OWNER, "roster-a", "profile-a")
                        .orElseThrow().state());
        assertEquals(-20L, store.findProfile(OWNER, "roster-a", "profile-a")
                .orElseThrow().reviveCooldownUntilMs());
        assertTrue(store.findActiveLeases(OWNER, "roster-a").isEmpty());
    }

    @Test
    void summonCapacityIsAtomicAndScopedToRosterFamily() {
        Path database = temporaryDirectory.resolve("family-capacity.sqlite");
        assertTrue(new BondedCompanionSchemaManager(database, () -> -100L)
                .initialize().availability().available());
        SqliteBondedCompanionDatabase store =
                new SqliteBondedCompanionDatabase(database);
        create(store, "dragon-a", "family:dragon");
        create(store, "dragon-b", "family:dragon");
        create(store, "mini-a", "family:mini");
        SqliteBondedCompanionProjectionDurability durability =
                new SqliteBondedCompanionProjectionDurability(database);

        assertTrue(begin(durability, "dragon-a", "family:dragon", 11));
        assertTrue(begin(durability, "mini-a", "family:mini", 12));
        assertFalse(begin(durability, "dragon-b", "family:dragon", 13));

        assertEquals(BondedCompanionState.STORED,
                store.findProfile(OWNER, "roster-a", "dragon-b")
                        .orElseThrow().state());
        assertEquals(2, store.findActiveLeases(OWNER, "roster-a").size());
    }

    @Test
    void concurrentSummonsCannotBothCrossOneFamilyCapacityFence()
            throws Exception {
        Path database = temporaryDirectory.resolve(
                "concurrent-family-capacity.sqlite");
        assertTrue(new BondedCompanionSchemaManager(database, () -> -100L)
                .initialize().availability().available());
        SqliteBondedCompanionDatabase store =
                new SqliteBondedCompanionDatabase(database);
        create(store, "dragon-a", "family:dragon");
        create(store, "dragon-b", "family:dragon");
        SqliteBondedCompanionProjectionDurability first =
                new SqliteBondedCompanionProjectionDurability(database);
        SqliteBondedCompanionProjectionDurability second =
                new SqliteBondedCompanionProjectionDurability(database);
        CyclicBarrier start = new CyclicBarrier(2);

        List<Boolean> results;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> {
                start.await();
                return begin(first, "dragon-a", "family:dragon", 21);
            });
            var two = executor.submit(() -> {
                start.await();
                return begin(second, "dragon-b", "family:dragon", 22);
            });
            results = List.of(one.get(), two.get());
        }

        assertEquals(1L, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, store.findActiveLeases(OWNER, "roster-a").size());
    }

    private void create(
            SqliteBondedCompanionDatabase store,
            String profileId,
            String familyId
    ) {
        assertEquals(BondedCompanionStoreResult.Code.APPLIED,
                store.createProfile(
                        operation(profileId), profile(profileId, familyId)
                ).code());
    }

    private boolean begin(
            SqliteBondedCompanionProjectionDurability durability,
            String profileId,
            String familyId,
            int uuidSuffix
    ) {
        UUID npcUuid = UUID.fromString(String.format(
                "20000000-0000-0000-0000-%012d", uuidSuffix
        ));
        var lease = new BondedCompanionProjectionValidator.LeaseExpectation(
                OWNER, "roster-a", profileId, "lease-" + profileId, npcUuid,
                "world-a", -90L, 0L,
                BondedCompanionProjectionValidator.LeasePhase.PENDING
        );
        var request = new BondedCompanionProjectionService.SummonRequest(
                OWNER, "roster-a", profileId, 0L, "role:wolf",
                snapshot(), "world-a", null, -90L, 0L,
                new BondedCompanionActiveCapacity(familyId, 1)
        );
        var cleanup = BondedCompanionProjectionCleanupService.CleanupIntent
                .projection(
                        "cleanup-" + profileId, OWNER, "roster-a", profileId,
                        lease.leaseToken(), npcUuid, "world-a",
                        "spawn-recovery", -90L
                );
        return durability.beginSummon(request, lease, cleanup);
    }

    private BondedCompanionRecord.Profile profile() {
        return profile("profile-a", "family:wolf");
    }

    private BondedCompanionRecord.Profile profile(
            String profileId,
            String familyId
    ) {
        return new BondedCompanionRecord.Profile(
                profileId, OWNER, "roster-a", familyId,
                "role:wolf", BondedCompanionState.STORED, 0L,
                BondedCompanionPayload.of("snapshot".getBytes(
                        StandardCharsets.UTF_8
                )), -100L, -100L, Map.of(), "Wolf", "Wolf", null,
                null, 0L, 0L, null, null
        );
    }

    private BondedCompanionOperation operation() {
        return operation("profile-a");
    }

    private BondedCompanionOperation operation(String profileId) {
        return new BondedCompanionOperation(
                "test", profileId, "a".repeat(64), OWNER,
                "roster-a", profileId, BondedCompanionOperation.Type.PROVISION,
                -100L, -1L
        );
    }

    private BondedCompanionSnapshot snapshot() {
        return BondedCompanionSnapshot.of(
                new CoopResidentStateSnapshotService.CoopResidentStateSnapshot(
                        NPC, null, -1, "role:wolf", null, null, null,
                        null, null, null, null, null, null, null, null,
                        null, null, -100L
                ),
                Map.of()
        );
    }
}
