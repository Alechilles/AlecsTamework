package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionActionContext;
import com.alechilles.alecstamework.api.BondedCompanionActionRequest;
import com.alechilles.alecstamework.api.BondedCompanionPlacement;
import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionActiveCapacity;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionPolicyResolver;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionCleanupService;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionService;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionValidator;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshotCodec;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionTransitionService;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionDatabase;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionProjectionDurability;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.diagnostics.BondedCompanionDiagnosticContributor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end API/SQLite coverage for the lease-only summon duplicate fence. */
class BondedCompanionSummonLeaseFenceTest {
    private static final UUID OWNER = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final UUID NPC = UUID.fromString(
            "20000000-0000-0000-0000-000000000001");
    private static final String ROSTER = "roster-a";
    private static final String PROFILE = "profile-a";

    @TempDir
    Path tempDir;

    @Test
    void runtimePendingLeaseReturnsInProgressWithoutSecondSummonPath()
            throws Exception {
        assertDuplicateFence(
                BondedCompanionProjectionValidator.LeasePhase.PENDING,
                "bonded-summon-in-progress");
    }

    @Test
    void liveLeaseReturnsAlreadyLiveWithoutSecondSummonPath()
            throws Exception {
        assertDuplicateFence(
                BondedCompanionProjectionValidator.LeasePhase.LIVE,
                "bonded-summon-already-live");
    }

    private void assertDuplicateFence(
            BondedCompanionProjectionValidator.LeasePhase phase,
            String reason
    ) throws Exception {
        Path database = tempDir.resolve(
                "summon-" + phase.name().toLowerCase() + ".sqlite");
        assertTrue(new BondedCompanionSchemaManager(database, () -> -1_000L)
                .initialize().availability().available());
        var store = new SqliteBondedCompanionDatabase(database);
        BondedCompanionSnapshot snapshot = snapshot();
        assertEquals(BondedCompanionStoreResult.Code.APPLIED,
                store.createProfile(provisionOperation(), profile(snapshot)).code());

        var durability = new SqliteBondedCompanionProjectionDurability(database);
        var lease = lease(BondedCompanionProjectionValidator.LeasePhase.PENDING);
        var recovery = BondedCompanionProjectionCleanupService.CleanupIntent
                .projection("spawn-recovery", OWNER, ROSTER, PROFILE,
                        lease.leaseToken(), NPC, "world-a", "spawn-recovery",
                        10_000L);
        assertTrue(durability.beginSummon(
                summonRequest(snapshot), lease, recovery));
        if (phase == BondedCompanionProjectionValidator.LeasePhase.LIVE) {
            assertTrue(durability.confirmSpawn(lease, NPC));
        }

        CountingWorld world = new CountingWorld();
        BondedCompanionRosterRegistry rosters =
                new BondedCompanionRosterRegistry();
        BondedCompanionPolicyResolver policies =
                new BondedCompanionPolicyResolver(rosters);
        var cleanup = new BondedCompanionProjectionCleanupService(
                ignored -> BondedCompanionProjectionCleanupService.Outcome
                        .ALREADY_MISSING);
        var projections = new BondedCompanionProjectionService(
                new BondedCompanionStorePlanner(store, rosters), durability,
                world, cleanup, () -> "forbidden-second-lease",
                () -> UUID.fromString(
                        "20000000-0000-0000-0000-000000000099"));
        var changes = new BondedCompanionChangePublisher(null);
        var diagnostics = new BondedCompanionDiagnosticContributor(
                BondedCompanionPersistenceReadiness::ready,
                BondedCompanionStoreDiagnostics::empty,
                BondedCompanionSchemaManager.VERSION);
        var operations = new BondedCompanionCoreApiOperations(
                store, rosters, policies,
                new BondedCompanionTransitionService(policies), projections,
                changes, diagnostics, () -> -500L);
        var api = new BondedCompanionApiFacade(
                BondedCompanionPersistenceReadiness::ready,
                store, changes, diagnostics, operations);
        long operationsBefore = operationCount(database);

        var result = api.summon(action()).join();

        assertEquals(BondedCompanionResultCode.INVALID_STATE, result.code());
        assertEquals(reason, result.reason());
        assertEquals(0, world.spawns.get());
        assertEquals(0, world.reads.get());
        assertEquals(1, store.findActiveLeases(OWNER, ROSTER).size());
        assertEquals(operationsBefore, operationCount(database));
        api.close();
        changes.close();
    }

    private BondedCompanionProjectionService.SummonRequest summonRequest(
            BondedCompanionSnapshot snapshot
    ) {
        return new BondedCompanionProjectionService.SummonRequest(
                OWNER, ROSTER, PROFILE, 0L, "role:wolf", snapshot,
                "world-a", null, -900L, 1_000L,
                new BondedCompanionActiveCapacity("family:wolf", 1));
    }

    private BondedCompanionActionRequest action() {
        return new BondedCompanionActionRequest(
                "test", "summon-duplicate", OWNER, ROSTER, PROFILE, 1L,
                "world-a", new BondedCompanionActionContext(
                new BondedCompanionPlacement(
                        "world-a", 0, 0, 0, 0, 0, 0), null));
    }

    private BondedCompanionProjectionValidator.LeaseExpectation lease(
            BondedCompanionProjectionValidator.LeasePhase phase
    ) {
        return new BondedCompanionProjectionValidator.LeaseExpectation(
                OWNER, ROSTER, PROFILE, "lease-a", NPC, "world-a",
                -900L, 1_000L, phase);
    }

    private BondedCompanionRecord.Profile profile(
            BondedCompanionSnapshot snapshot
    ) {
        String encoded = new BondedCompanionSnapshotCodec().encode(snapshot);
        return new BondedCompanionRecord.Profile(
                PROFILE, OWNER, ROSTER, "family:wolf", "role:wolf",
                BondedCompanionState.STORED, 0L,
                BondedCompanionPayload.of(encoded.getBytes(
                        StandardCharsets.UTF_8)),
                -1_000L, -1_000L, Map.of(), "Wolf", "Wolf", null,
                null, 0L, 0L, null, null);
    }

    private BondedCompanionSnapshot snapshot() {
        return BondedCompanionSnapshot.of(
                new CoopResidentStateSnapshotService.CoopResidentStateSnapshot(
                        NPC, null, -1, "role:wolf", null,
                        new TameworkOwnerComponent(OWNER, "Owner"),
                        new TameworkTamedComponent(true), null, null, null,
                        null, null, null, null, null, null, null, -1_000L),
                Map.of());
    }

    private BondedCompanionOperation provisionOperation() {
        return new BondedCompanionOperation(
                "test", "provision", "a".repeat(64), OWNER, ROSTER,
                PROFILE, BondedCompanionOperation.Type.PROVISION,
                -1_000L, 10_000L);
    }

    private long operationCount(Path database) throws Exception {
        try (Connection connection = new SqliteConnectionFactory(database)
                .openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(
                     "SELECT COUNT(*) FROM bonded_companion_operation")) {
            assertTrue(row.next());
            return row.getLong(1);
        }
    }

    private static final class CountingWorld
            implements BondedCompanionProjectionService.World {
        private final AtomicInteger spawns = new AtomicInteger();
        private final AtomicInteger reads = new AtomicInteger();

        @Override
        public BondedCompanionProjectionService.SpawnResult spawn(
                BondedCompanionProjectionService.SpawnPlan plan
        ) {
            spawns.incrementAndGet();
            return BondedCompanionProjectionService.SpawnResult.failed();
        }

        @Override
        public BondedCompanionProjectionValidator.Projection readExact(
                BondedCompanionProjectionValidator.LeaseExpectation lease
        ) {
            reads.incrementAndGet();
            return null;
        }
    }
}
