package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionCleanupService;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionActiveCapacity;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionService;
import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionProjectionStorePlanner;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionValidator;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshotCodec;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.config.assets.TwBondedCompanionRosterConfig;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components
        .TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionDatabase;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionProjectionDurability;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards atomic Task 4 durability and restart-safe exact cleanup routing. */
class BondedCompanionProjectionDurabilityTest {
    private static final String POLICY_ROSTER = "test:roster-a";
    private static final String POLICY_FAMILY = "test:wolf";
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
        int recovered = restarted.replayPendingCleanupForWorld(
                new BondedCompanionProjectionCleanupService(
                        ignored -> BondedCompanionProjectionCleanupService
                                .Outcome.REMOVED
                ),
                "world-a",
                -80L,
                10
        );

        assertEquals(1, recovered);
        assertEquals(BondedCompanionRecord.CleanupState.COMPLETED,
                store.listCleanup(OWNER, "roster-a", 10)
                        .getFirst().state());
        assertTrue(restarted.reconcileStored(
                lease,
                new BondedCompanionProjectionStorePlanner.StorePlan(
                                1L, snapshot(), -20L
                        ),
                java.util.List.of(), "SPAWN_INTERRUPTED"
        ));
        assertEquals(BondedCompanionState.STORED,
                store.findProfile(OWNER, "roster-a", "profile-a")
                        .orElseThrow().state());
        assertEquals(-20L, store.findProfile(OWNER, "roster-a", "profile-a")
                .orElseThrow().summonCooldownUntilMs());
        assertTrue(store.findActiveLeases(OWNER, "roster-a").isEmpty());
    }

    @Test
    void retryRequiredCleanupBacksOffUntilItsBoundedRetentionThenAbandons() {
        Path database = temporaryDirectory.resolve("cleanup-retry.sqlite");
        assertTrue(new BondedCompanionSchemaManager(database, () -> 0L)
                .initialize().availability().available());
        SqliteBondedCompanionDatabase store =
                new SqliteBondedCompanionDatabase(database);
        assertEquals(BondedCompanionStoreResult.Code.APPLIED,
                store.createProfile(operation(), profile()).code());
        SqliteBondedCompanionProjectionDurability durability =
                new SqliteBondedCompanionProjectionDurability(database);
        var lease = new BondedCompanionProjectionValidator.LeaseExpectation(
                OWNER, "roster-a", "profile-a", "lease-a", NPC,
                "world-a", 0L, 0L,
                BondedCompanionProjectionValidator.LeasePhase.PENDING
        );
        var request = new BondedCompanionProjectionService.SummonRequest(
                OWNER, "roster-a", "profile-a", 0L, "role:wolf",
                snapshot(), "world-a", null, 0L, 0L,
                new BondedCompanionActiveCapacity("family:wolf", 1)
        );
        var cleanup = new BondedCompanionProjectionCleanupService.CleanupIntent(
                "cleanup-retry", OWNER, "roster-a", "profile-a", "lease-a",
                BondedCompanionProjectionCleanupService.Target.PROJECTION,
                NPC, "world-a", "spawn-recovery", 0L, 10_000L
        );
        assertTrue(durability.beginSummon(request, lease, cleanup));
        BondedCompanionProjectionCleanupService retry =
                new BondedCompanionProjectionCleanupService(
                        ignored -> BondedCompanionProjectionCleanupService.Outcome
                                .RETRY_REQUIRED
                );

        assertEquals(1, durability.replayPendingCleanupForWorld(
                retry, "world-a", 0L, 1));
        var first = store.listCleanup(OWNER, "roster-a", 1).getFirst();
        assertEquals(BondedCompanionRecord.CleanupState.PENDING, first.state());
        assertEquals(1, first.attemptCount());
        assertTrue(first.nextAttemptAtMs() > 0L);

        assertEquals(1, durability.replayPendingCleanupForWorld(
                retry, "world-a", first.nextAttemptAtMs(), 1
        ));
        var second = store.listCleanup(OWNER, "roster-a", 1).getFirst();
        assertEquals(2, second.attemptCount());
        assertTrue(second.nextAttemptAtMs() - first.nextAttemptAtMs()
                > first.nextAttemptAtMs());

        assertEquals(1, durability.replayPendingCleanupForWorld(
                retry, "world-a", 10_000L, 1));
        assertEquals(BondedCompanionRecord.CleanupState.ABANDONED,
                store.listCleanup(OWNER, "roster-a", 1).getFirst().state());
        assertEquals(1, store.pruneCleanup(10_000L, 1));
        assertTrue(store.listCleanup(OWNER, "roster-a", 1).isEmpty());
    }

    @Test
    void projectionCleanupMissingFromAnUnloadedChunkRemainsDurableUntilConfirmed() {
        Path database = temporaryDirectory.resolve("projection-cleanup-retry.sqlite");
        assertTrue(new BondedCompanionSchemaManager(database, () -> 0L)
                .initialize().availability().available());
        SqliteBondedCompanionDatabase store =
                new SqliteBondedCompanionDatabase(database);
        assertEquals(BondedCompanionStoreResult.Code.APPLIED,
                store.createProfile(operation(), profile()).code());
        SqliteBondedCompanionProjectionDurability durability =
                new SqliteBondedCompanionProjectionDurability(database);
        var lease = new BondedCompanionProjectionValidator.LeaseExpectation(
                OWNER, "roster-a", "profile-a", "lease-a", NPC,
                "world-a", 0L, 0L,
                BondedCompanionProjectionValidator.LeasePhase.PENDING
        );
        var request = new BondedCompanionProjectionService.SummonRequest(
                OWNER, "roster-a", "profile-a", 0L, "role:wolf",
                snapshot(), "world-a", null, 0L, 0L,
                new BondedCompanionActiveCapacity("family:wolf", 1)
        );
        var cleanup = new BondedCompanionProjectionCleanupService.CleanupIntent(
                "cleanup-unloaded", OWNER, "roster-a", "profile-a", "lease-a",
                BondedCompanionProjectionCleanupService.Target.PROJECTION,
                NPC, "world-a", "projection-cleanup", 0L, 10_000L
        );
        assertTrue(durability.beginSummon(request, lease, cleanup));
        BondedCompanionProjectionCleanupService initiallyUnavailable =
                new BondedCompanionProjectionCleanupService(
                        ignored -> BondedCompanionProjectionCleanupService.Outcome
                                .ALREADY_MISSING
                );

        assertEquals(BondedCompanionProjectionCleanupService.Outcome.RETRY_REQUIRED,
                durability.attemptCleanup(initiallyUnavailable, cleanup, 0L));
        var pending = store.listCleanup(OWNER, "roster-a", 1).getFirst();
        assertEquals(BondedCompanionRecord.CleanupState.PENDING, pending.state());

        assertEquals(1, durability.replayPendingCleanupForWorld(
                new BondedCompanionProjectionCleanupService(
                        ignored -> BondedCompanionProjectionCleanupService.Outcome
                                .REMOVED
                ), "world-a", pending.nextAttemptAtMs(), 1
        ));
        assertEquals(BondedCompanionRecord.CleanupState.COMPLETED,
                store.listCleanup(OWNER, "roster-a", 1).getFirst().state());
    }

    @Test
    void worldLocalLeaseReadIncludesLiveAlongsideEarlierPendingSummons() {
        Path database = temporaryDirectory.resolve("live-lease-window.sqlite");
        assertTrue(new BondedCompanionSchemaManager(database, () -> -100L)
                .initialize().availability().available());
        SqliteBondedCompanionDatabase store =
                new SqliteBondedCompanionDatabase(database);
        SqliteBondedCompanionProjectionDurability durability =
                new SqliteBondedCompanionProjectionDurability(database);
        for (int index = 0; index < 64; index++) {
            String profileId = "a-pending-%03d".formatted(index);
            String familyId = "family:" + profileId;
            create(store, profileId, familyId);
            assertTrue(begin(durability, profileId, familyId, 100 + index));
        }
        create(store, "z-live", "family:z-live");
        assertTrue(begin(durability, "z-live", "family:z-live", 200));
        var pendingLive = durability.activeLeases(128).stream()
                .filter(lease -> lease.profileId().equals("z-live"))
                .findFirst().orElseThrow();
        assertTrue(durability.confirmSpawn(pendingLive, pendingLive.liveNpcUuid()));

        List<BondedCompanionProjectionValidator.LeaseExpectation> live =
                durability.inWorld("world-a", 128).stream()
                        .filter(lease -> lease.phase()
                                == BondedCompanionProjectionValidator
                                .LeasePhase.LIVE)
                        .toList();

        assertEquals(1, live.size());
        assertEquals("z-live", live.getFirst().profileId());
        assertEquals(BondedCompanionProjectionValidator.LeasePhase.LIVE,
                live.getFirst().phase());
    }

    @Test
    void startupSettlementHasNoRowCeilingAndAuthorsExactCleanupWithoutWorldAccess()
            throws Exception {
        Path database = temporaryDirectory.resolve("startup-settlement.sqlite");
        assertTrue(new BondedCompanionSchemaManager(database, () -> 0L)
                .initialize().availability().available());
        SqliteBondedCompanionDatabase store =
                new SqliteBondedCompanionDatabase(database);
        SqliteBondedCompanionProjectionDurability durability =
                new SqliteBondedCompanionProjectionDurability(database);
        for (int index = 0; index < 257; index++) {
            String profileId = "profile-%03d".formatted(index);
            String familyId = "family:" + profileId;
            create(store, profileId, familyId);
            assertTrue(begin(durability, profileId, familyId, index + 1));
        }

        assertEquals(257, durability.settleResidualLeases(-80L));
        assertEquals(0, durability.settleResidualLeases(-70L));
        assertEquals(0L, queryCount(database, "bonded_companion_lease"));
        assertEquals(257L, queryCount(database, "bonded_companion_cleanup"));
        assertEquals(0L, queryCount(database,
                "bonded_companion_profile WHERE state <> 'STORED'"));
        assertFalse(BondedCompanionSchemaManager.requiredTables()
                .contains("bonded_companion_lease_admission"));
    }

    @Test
    void startupSettlementRetainsTheLastSnapshotAndExactWorldIdentity() throws Exception {
        Path database = temporaryDirectory.resolve("startup-snapshot.sqlite");
        assertTrue(new BondedCompanionSchemaManager(database, () -> 0L)
                .initialize().availability().available());
        SqliteBondedCompanionDatabase store =
                new SqliteBondedCompanionDatabase(database);
        SqliteBondedCompanionProjectionDurability durability =
                new SqliteBondedCompanionProjectionDurability(database);
        create(store, "profile-startup", "family:startup");
        assertTrue(begin(durability, "profile-startup", "family:startup", 500));
        var before = store.findProfile(OWNER, "roster-a", "profile-startup")
                .orElseThrow();

        assertEquals(1, durability.settleResidualLeases(-80L));

        var after = store.findProfile(OWNER, "roster-a", "profile-startup")
                .orElseThrow();
        assertEquals(before.snapshot(), after.snapshot());
        var cleanup = store.listCleanup(OWNER, "roster-a", 8).getFirst();
        assertEquals("world-a", cleanup.worldKey());
        assertEquals("lease-profile-startup", cleanup.leaseToken());
        assertEquals(UUID.fromString(
                "20000000-0000-0000-0000-000000000500"), cleanup.targetNpcUuid());
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
    void stalePendingRollbackCannotStoreOrDeleteAConfirmedLiveLease() {
        Path database = temporaryDirectory.resolve("stale-pending.sqlite");
        assertTrue(new BondedCompanionSchemaManager(database, () -> -100L)
                .initialize().availability().available());
        SqliteBondedCompanionDatabase store =
                new SqliteBondedCompanionDatabase(database);
        assertEquals(BondedCompanionStoreResult.Code.APPLIED,
                store.createProfile(operation(), profile()).code());
        SqliteBondedCompanionProjectionDurability durability =
                new SqliteBondedCompanionProjectionDurability(database);
        var pending = new BondedCompanionProjectionValidator.LeaseExpectation(
                OWNER, "roster-a", "profile-a", "lease-a", NPC,
                "world-a", -90L, 0L,
                BondedCompanionProjectionValidator.LeasePhase.PENDING
        );
        var request = new BondedCompanionProjectionService.SummonRequest(
                OWNER, "roster-a", "profile-a", 0L, "role:wolf",
                snapshot(), "world-a", null, -90L, 0L,
                new BondedCompanionActiveCapacity("family:wolf", 1)
        );
        var recovery = BondedCompanionProjectionCleanupService.CleanupIntent
                .projection(
                        "cleanup-stale", OWNER, "roster-a", "profile-a",
                        "lease-a", NPC, "world-a", "spawn-recovery", -90L
                );

        assertTrue(durability.beginSummon(request, pending, recovery));
        assertTrue(durability.confirmSpawn(pending, NPC));
        assertFalse(durability.failSpawnAndEnqueueCleanup(
                pending, List.of(), "STALE_ROLLBACK"));
        assertFalse(durability.confirmDeath(
                pending,
                new BondedCompanionProjectionStorePlanner.StorePlan(
                        1L, plannerSnapshot(
                                OWNER, "role:wolf", null, Map.of()), 0L),
                -80L
        ));

        assertEquals(BondedCompanionState.ACTIVE,
                store.findProfile(OWNER, "roster-a", "profile-a")
                        .orElseThrow().state());
        BondedCompanionRecord.Lease live = store.findActiveLeases(
                OWNER, "roster-a").getFirst();
        assertEquals(BondedCompanionRecord.ProjectionState.LIVE,
                live.projectionState());
    }

    @Test
    void removedFamilyFallsBackToZeroCooldownInsteadOfStrandingActiveProfile() {
        ActiveFixture fixture = activeFixture("removed-family.sqlite");
        var planner = new BondedCompanionStorePlanner(
                fixture.store(), new BondedCompanionRosterRegistry());
        var result = planner.plan(
                new BondedCompanionProjectionStorePlanner.PlanningRequest(
                        fixture.lease(), null, -80L, null,
                        BondedCompanionProjectionStorePlanner.Cause.RECONCILIATION
                ));

        assertEquals(0L, result.plan().summonCooldownUntilMs());
    }

    @Test
    void rolePolicyDriftStillAllowsNondeathStoreWithFamilyCooldown()
            throws Exception {
        ActiveFixture fixture = activeFixture("role-drift.sqlite");
        var planner = new BondedCompanionStorePlanner(
                fixture.store(), rosterRegistry("role:other", 5L));
        var result = planner.plan(
                new BondedCompanionProjectionStorePlanner.PlanningRequest(
                        fixture.lease(), null, -80L, null,
                        BondedCompanionProjectionStorePlanner.Cause.RECONCILIATION
                ));

        assertEquals(4_920L, result.plan().summonCooldownUntilMs());
    }

    @Test
    void explicitPlannerValidatesLiveIdentityAndPreservesDurableState()
            throws Exception {
        ActiveFixture fixture = liveFixture("explicit-planner.sqlite");
        var planner = new BondedCompanionStorePlanner(
                fixture.store(), rosterRegistry("role:wolf", 5L));
        var planned = planner.plan(
                new BondedCompanionProjectionStorePlanner.PlanningRequest(
                        fixture.lease(), 1L, -80L, snapshot(),
                        BondedCompanionProjectionStorePlanner.Cause.EXPLICIT
                ));

        assertEquals(BondedCompanionProjectionStorePlanner.Status.PLANNED,
                planned.status());
        assertEquals(4_920L, planned.plan().summonCooldownUntilMs());
        assertEquals("{\"attuned\":true}",
                planned.plan().snapshot().extensionData()
                        .get("hydragon.abilities"));
        assertEquals("Durable Wolf", planned.plan().snapshot().fullState()
                .npcName().getName());

        var rejected = planner.plan(
                new BondedCompanionProjectionStorePlanner.PlanningRequest(
                        fixture.lease(), 1L, -80L,
                        plannerSnapshot(
                                UUID.fromString(
                                        "10000000-0000-0000-0000-000000000099"
                                ),
                                "role:wolf", null, Map.of()),
                        BondedCompanionProjectionStorePlanner.Cause.EXPLICIT
                ));
        assertEquals(
                BondedCompanionProjectionStorePlanner.Status
                        .SNAPSHOT_IDENTITY_MISMATCH,
                rejected.status()
        );

        var wrongRole = planner.plan(
                new BondedCompanionProjectionStorePlanner.PlanningRequest(
                        fixture.lease(), 1L, -80L,
                        plannerSnapshot(OWNER, "role:other", null, Map.of()),
                        BondedCompanionProjectionStorePlanner.Cause.EXPLICIT
                ));
        assertEquals(
                BondedCompanionProjectionStorePlanner.Status
                        .SNAPSHOT_IDENTITY_MISMATCH,
                wrongRole.status()
        );
    }

    @Test
    void storedViewUsesPresentationFromTheMergedDurableSnapshot()
            throws Exception {
        ActiveFixture fixture = liveFixture("stored-presentation.sqlite");
        var planner = new BondedCompanionStorePlanner(
                fixture.store(), rosterRegistry("role:wolf", 5L));
        var request = new BondedCompanionProjectionService.StoreRequest(
                fixture.lease(), 1L, -80L);
        var planned = planner.plan(
                new BondedCompanionProjectionStorePlanner.PlanningRequest(
                        fixture.lease(), 1L, -80L,
                        plannerSnapshot(
                                OWNER, "role:wolf", "Renamed Wolf", Map.of()),
                        BondedCompanionProjectionStorePlanner.Cause.EXPLICIT
                ));
        var cleanup = BondedCompanionProjectionCleanupService.CleanupIntent
                .projection(
                        "cleanup-store-view", OWNER, POLICY_ROSTER,
                        "profile-a", "lease-a", NPC, "world-a", "store", -1L
                );

        assertTrue(fixture.durability().storeAndEnqueueCleanup(
                request, planned.plan(), cleanup));
        BondedCompanionRecord.Profile stored = fixture.store().findProfile(
                OWNER, POLICY_ROSTER, "profile-a").orElseThrow();

        assertEquals("Renamed Wolf",
                new BondedCompanionViewFactory().view(stored, null)
                        .displayName());
    }

    @Test
    void confirmedDeathPreservesDurableStateAndUsesRevisionPhaseFences()
            throws Exception {
        ActiveFixture fixture = liveFixture("planned-death.sqlite");
        var planner = new BondedCompanionStorePlanner(
                fixture.store(), rosterRegistry("role:wolf", 5L));
        var service = new BondedCompanionProjectionService(
                planner,
                fixture.durability(),
                new BondedCompanionProjectionService.World() {
                    @Override
                    public BondedCompanionProjectionService.SpawnResult spawn(
                            BondedCompanionProjectionService.SpawnPlan plan
                    ) {
                        return BondedCompanionProjectionService.SpawnResult
                                .failed();
                    }

                    @Override
                    public BondedCompanionProjectionValidator.Projection
                            readExact(
                            BondedCompanionProjectionValidator.LeaseExpectation
                                    lease
                    ) {
                        return null;
                    }
                },
                new BondedCompanionProjectionCleanupService(
                        ignored -> BondedCompanionProjectionCleanupService
                                .Outcome.ALREADY_MISSING),
                () -> "unused-lease",
                () -> UUID.fromString(
                        "20000000-0000-0000-0000-000000000099")
        );
        var projection = new BondedCompanionProjectionValidator.Projection(
                NPC,
                "world-a",
                TameworkProjectionIdentityComponent.bondedCompanion(
                        "profile-a", "lease-a"),
                snapshot()
        );

        var wrongOwner = new BondedCompanionProjectionValidator.Projection(
                NPC,
                "world-a",
                TameworkProjectionIdentityComponent.bondedCompanion(
                        "profile-a", "lease-a"),
                plannerSnapshot(
                        UUID.fromString(
                                "10000000-0000-0000-0000-000000000099"),
                        "role:wolf", null, Map.of())
        );
        assertEquals(
                BondedCompanionProjectionService.ReconcileStatus
                        .IDENTITY_MISMATCH,
                service.confirmDeath(fixture.lease(), wrongOwner, -80L)
                        .status()
        );
        assertEquals(BondedCompanionState.ACTIVE,
                fixture.store().findProfile(
                        OWNER, POLICY_ROSTER, "profile-a"
                ).orElseThrow().state());

        var result = service.confirmDeath(fixture.lease(), projection, -70L);

        assertEquals(BondedCompanionProjectionService.ReconcileStatus.DEAD,
                result.status());
        BondedCompanionRecord.Profile dead = fixture.store().findProfile(
                OWNER, POLICY_ROSTER, "profile-a").orElseThrow();
        assertEquals(BondedCompanionState.DEAD, dead.state());
        assertEquals(2L, dead.revision());
        assertEquals(0L, dead.summonCooldownUntilMs());
        BondedCompanionSnapshot durable = new BondedCompanionSnapshotCodec()
                .decode(new String(
                        dead.snapshot().bytes(), StandardCharsets.UTF_8
                )).snapshot();
        assertEquals("{\"attuned\":true}", durable.extensionData()
                .get("hydragon.abilities"));
        assertEquals("Durable Wolf",
                durable.fullState().npcName().getName());
        assertTrue(fixture.store().findActiveLeases(
                OWNER, POLICY_ROSTER).isEmpty());
        assertFalse(fixture.durability().confirmDeath(
                fixture.lease(),
                new BondedCompanionProjectionStorePlanner.StorePlan(
                        1L, snapshot(), 0L),
                -60L
        ));
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

    private long queryCount(Path database, String fromClause) throws Exception {
        try (var connection = new com.alechilles.alecstamework.persistence
                .adapter.sqlite.SqliteConnectionFactory(database)
                .openReadConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery(
                     "SELECT COUNT(*) FROM " + fromClause)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
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

    private ActiveFixture activeFixture(String fileName) {
        Path database = temporaryDirectory.resolve(fileName);
        assertTrue(new BondedCompanionSchemaManager(database, () -> -100L)
                .initialize().availability().available());
        SqliteBondedCompanionDatabase store =
                new SqliteBondedCompanionDatabase(database);
        assertEquals(BondedCompanionStoreResult.Code.APPLIED,
                store.createProfile(
                        operation("profile-a", POLICY_ROSTER),
                        plannerProfile()
                ).code());
        SqliteBondedCompanionProjectionDurability durability =
                new SqliteBondedCompanionProjectionDurability(database);
        var lease = new BondedCompanionProjectionValidator.LeaseExpectation(
                OWNER, POLICY_ROSTER, "profile-a", "lease-a", NPC,
                "world-a", -90L, 0L,
                BondedCompanionProjectionValidator.LeasePhase.PENDING
        );
        var request = new BondedCompanionProjectionService.SummonRequest(
                OWNER, POLICY_ROSTER, "profile-a", 0L, "role:wolf",
                snapshot(), "world-a", null, -90L, 0L,
                new BondedCompanionActiveCapacity(POLICY_FAMILY, 1)
        );
        var cleanup = BondedCompanionProjectionCleanupService.CleanupIntent
                .projection(
                        "cleanup-active", OWNER, POLICY_ROSTER, "profile-a",
                        "lease-a", NPC, "world-a", "spawn-recovery", -90L
                );
        assertTrue(durability.beginSummon(request, lease, cleanup));
        return new ActiveFixture(store, durability, lease);
    }

    private ActiveFixture liveFixture(String fileName) {
        ActiveFixture pending = activeFixture(fileName);
        assertTrue(pending.durability().confirmSpawn(
                pending.lease(), pending.lease().liveNpcUuid()));
        var live = new BondedCompanionProjectionValidator.LeaseExpectation(
                pending.lease().ownerUuid(), pending.lease().rosterId(),
                pending.lease().profileId(), pending.lease().leaseToken(),
                pending.lease().liveNpcUuid(), pending.lease().worldKey(),
                pending.lease().startedAtMs(), pending.lease().expiresAtMs(),
                BondedCompanionProjectionValidator.LeasePhase.LIVE
        );
        return new ActiveFixture(pending.store(), pending.durability(), live);
    }

    private BondedCompanionRosterRegistry rosterRegistry(
            String allowedRole,
            long cooldownSeconds
    ) throws Exception {
        TwBondedCompanionRosterConfig config =
                TwBondedCompanionRosterConfig.CODEC.decode(
                        BsonDocument.parse("""
                                {
                                  "RosterId": "%s",
                                  "FamilyId": "%s",
                                  "AllowedRoles": ["%s"],
                                  "SummonCooldownSeconds": %d,
                                  "Features": {"Dismiss": true}
                                }
                                """.formatted(
                                POLICY_ROSTER, POLICY_FAMILY,
                                allowedRole, cooldownSeconds
                        )),
                        new ExtraInfo()
                );
        Field id = config.getClass().getDeclaredField("id");
        id.setAccessible(true);
        id.set(config, "WolfFamily");
        BondedCompanionRosterRegistry registry =
                new BondedCompanionRosterRegistry();
        BondedCompanionRosterRegistry.ReloadResult loaded =
                registry.replace(List.of(config), 4L);
        assertTrue(loaded.applied(), loaded.error());
        return registry;
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
        return profile(profileId, familyId, "roster-a");
    }

    private BondedCompanionRecord.Profile profile(
            String profileId,
            String familyId,
            String rosterId
    ) {
        return new BondedCompanionRecord.Profile(
                profileId, OWNER, rosterId, familyId,
                "role:wolf", BondedCompanionState.STORED, 0L,
                BondedCompanionPayload.of("snapshot".getBytes(
                        StandardCharsets.UTF_8
                )), -100L, -100L, Map.of(), "Wolf", "Wolf", null,
                null, 0L, 0L, null, null
        );
    }

    private BondedCompanionRecord.Profile plannerProfile() {
        String encoded = new BondedCompanionSnapshotCodec().encode(
                plannerSnapshot(
                        OWNER, "role:wolf", "Durable Wolf",
                        Map.of("hydragon.abilities", "{\"attuned\":true}")
                ));
        return new BondedCompanionRecord.Profile(
                "profile-a", OWNER, POLICY_ROSTER, POLICY_FAMILY,
                "role:wolf", BondedCompanionState.STORED, 0L,
                BondedCompanionPayload.of(encoded.getBytes(
                        StandardCharsets.UTF_8)),
                -100L, -100L, Map.of(), "Wolf", "Wolf", null,
                null, 0L, 0L, null, null
        );
    }

    private BondedCompanionOperation operation() {
        return operation("profile-a");
    }

    private BondedCompanionOperation operation(String profileId) {
        return operation(profileId, "roster-a");
    }

    private BondedCompanionOperation operation(
            String profileId,
            String rosterId
    ) {
        return new BondedCompanionOperation(
                "test", profileId, "a".repeat(64), OWNER,
                rosterId, profileId, BondedCompanionOperation.Type.PROVISION,
                -100L, -1L
        );
    }

    private BondedCompanionSnapshot snapshot() {
        return plannerSnapshot(OWNER, "role:wolf", null, Map.of());
    }

    private BondedCompanionSnapshot plannerSnapshot(
            UUID owner,
            String role,
            String name,
            Map<String, String> extensions
    ) {
        return BondedCompanionSnapshot.of(
                new CoopResidentStateSnapshotService.CoopResidentStateSnapshot(
                        NPC, null, -1, role, null,
                        new TameworkOwnerComponent(owner, "Owner"),
                        new TameworkTamedComponent(true),
                        name == null ? null : new TameworkNpcNameComponent(
                                name, owner, -100L,
                                TameworkNpcNameComponent.NameSource.System
                        ),
                        null, null, null, null, null, null, null,
                        null, null, -100L
                ),
                extensions
        );
    }

    private record ActiveFixture(
            SqliteBondedCompanionDatabase store,
            SqliteBondedCompanionProjectionDurability durability,
            BondedCompanionProjectionValidator.LeaseExpectation lease
    ) { }
}
