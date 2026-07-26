package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionCleanupService;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionService;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionValidator;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionDatabase;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionProjectionDurability;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                snapshot(), "world-a", -90L, 0L
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
                lease, null, java.util.List.of(), "SPAWN_INTERRUPTED"
        ));
        assertEquals(BondedCompanionState.STORED,
                store.findProfile(OWNER, "roster-a", "profile-a")
                        .orElseThrow().state());
        assertTrue(store.findActiveLeases(OWNER, "roster-a").isEmpty());
    }

    private BondedCompanionRecord.Profile profile() {
        return new BondedCompanionRecord.Profile(
                "profile-a", OWNER, "roster-a", "family:wolf",
                "role:wolf", BondedCompanionState.STORED, 0L,
                BondedCompanionPayload.of("snapshot".getBytes(
                        StandardCharsets.UTF_8
                )), -100L, -100L, Map.of(), "Wolf", "Wolf", null,
                null, 0L, 0L, null, null
        );
    }

    private BondedCompanionOperation operation() {
        return new BondedCompanionOperation(
                "test", "profile-a", "a".repeat(64), OWNER,
                "roster-a", "profile-a", BondedCompanionOperation.Type.PROVISION,
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
