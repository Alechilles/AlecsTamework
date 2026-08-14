package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionOperation;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Restart and lifecycle recovery matrix for disposable bonded projections. */
class BondedCompanionRecoveryTest {
    private RecordingBondedDurability durability;
    private RecordingBondedWorld world;
    private BondedCompanionProjectionCleanupService cleanup;
    private BondedCompanionProjectionService projections;
    private BondedCompanionWorldLifecycleObserver observer;

    @BeforeEach
    void setUp() {
        durability = new RecordingBondedDurability();
        world = new RecordingBondedWorld();
        cleanup = new BondedCompanionProjectionCleanupService(world);
        projections = new BondedCompanionProjectionService(
                durability, durability, world, cleanup,
                () -> "lease-new", () -> uuid(90)
        );
        observer = new BondedCompanionWorldLifecycleObserver(
                projections, world
        );
    }

    @Test
    void summonOrdersDurableLeaseBeforeWorldSpawnAndConfirmsTheSameUuid() {
        durability.events = world.events;
        var result = projections.summon(new BondedCompanionProjectionService.SummonRequest(
                uuid(1), "roster-a", "profile-a", 4L, "role-a",
                snapshot(uuid(20)), "world-a", null, -5_000L, -3_000L,
                new BondedCompanionActiveCapacity("family-a", 1)
        ));

        assertEquals(BondedCompanionProjectionService.SummonStatus.ACTIVE,
                result.status());
        assertEquals(List.of("begin:profile-a:lease-new:00000000-0000-0000-0000-00000000005a",
                "spawn:profile-a:lease-new", "confirm:profile-a:lease-new:00000000-0000-0000-0000-00000000005a"),
                world.events);
        assertTrue(durability.states.get("profile-a") == BondedCompanionState.ACTIVE);
        assertTrue(durability.spawnRecovery.isEmpty());
    }

    @Test
    void lifecycleObserverRunsOnlyAfterDurableLeaseTransitions() {
        durability.events = world.events;
        List<String> leaseEvents = new java.util.ArrayList<>();
        BondedCompanionProjectionService observed = new BondedCompanionProjectionService(
                durability,
                durability,
                world,
                cleanup,
                () -> "lease-observed",
                () -> uuid(91),
                new BondedCompanionProjectionService.LeaseLifecycleObserver() {
                    @Override
                    public void activated(
                            BondedCompanionProjectionValidator.LeaseExpectation lease
                    ) {
                        leaseEvents.add("activated:" + lease.leaseToken());
                    }

                    @Override
                    public void ended(
                            BondedCompanionProjectionValidator.LeaseExpectation lease
                    ) {
                        leaseEvents.add("ended:" + lease.leaseToken());
                    }
                }
        );
        var summoned = observed.summon(new BondedCompanionProjectionService.SummonRequest(
                uuid(1), "roster-a", "profile-a", 4L, "role-a",
                snapshot(uuid(20)), "world-a", null, -5_000L, -3_000L,
                new BondedCompanionActiveCapacity("family-a", 1)
        ));
        durability.snapshots.put("profile-a", snapshot(uuid(20)));

        observed.store(new BondedCompanionProjectionService.StoreRequest(
                summoned.lease(), 4L, -2_900L, new BondedCompanionOperation(
                        "test", "store-observed", "0".repeat(64),
                        uuid(1), "roster-a", "profile-a",
                        BondedCompanionOperation.Type.STORE, -2_900L, 60_000L,
                        new BondedCompanionOperation.StoreLeaseIdentity(
                                summoned.lease().leaseToken(),
                                summoned.lease().liveNpcUuid(),
                                summoned.lease().worldKey()
                        )
                )
        ));

        assertEquals(List.of(
                "activated:lease-observed",
                "ended:lease-observed"
        ), leaseEvents);
        assertTrue(world.events.indexOf("confirm:profile-a:lease-observed:"
                        + summoned.lease().liveNpcUuid())
                < world.events.indexOf("store:profile-a"));
    }

    @Test
    void retryableSpawnPersistsRollbackCleanupBeforeWorldRemoval() {
        durability.events = world.events;
        world.spawnMode = RecordingBondedWorld.SpawnMode.RETRYABLE;

        var result = projections.summon(new BondedCompanionProjectionService.SummonRequest(
                uuid(1), "roster-a", "profile-a", 4L, "role-a",
                snapshot(uuid(20)), "world-a", null, -5_000L, -3_000L,
                new BondedCompanionActiveCapacity("family-a", 1)
        ));

        assertEquals(BondedCompanionProjectionService.SummonStatus.SPAWN_FAILED_STORED,
                result.status());
        assertEquals(List.of(
                "begin:profile-a:lease-new:00000000-0000-0000-0000-00000000005a",
                "spawn:profile-a:lease-new",
                "rollback:profile-a:SPAWN_RETRY_REQUIRED",
                "remove-if-exact:world-a:00000000-0000-0000-0000-00000000005a"
        ), world.events);
        assertEquals(BondedCompanionState.STORED, durability.states.get("profile-a"));
        assertTrue(durability.spawnRecovery.isEmpty());
        assertEquals(1, durability.spawnFailureCleanups.size());
    }

    @Test
    void spawnExceptionLeavesDurableStoredCleanupForRestartRecovery() {
        durability.events = world.events;
        world.spawnMode = RecordingBondedWorld.SpawnMode.THROW;
        world.removeSucceeds = false;

        var result = projections.summon(new BondedCompanionProjectionService.SummonRequest(
                uuid(1), "roster-a", "profile-a", 4L, "role-a",
                snapshot(uuid(20)), "world-a", null, -5_000L, -3_000L,
                new BondedCompanionActiveCapacity("family-a", 1)
        ));

        assertEquals(BondedCompanionProjectionService.SummonStatus.SPAWN_FAILED_STORED,
                result.status());
        assertEquals(BondedCompanionState.STORED, durability.states.get("profile-a"));
        assertEquals(1, durability.spawnFailureCleanups.size());
        assertEquals("world-a",
                durability.spawnFailureCleanups.getFirst().worldKey());
        assertEquals(List.of(
                "begin:profile-a:lease-new:00000000-0000-0000-0000-00000000005a",
                "spawn:profile-a:lease-new",
                "rollback:profile-a:SPAWN_FAILED",
                "remove-if-exact:world-a:00000000-0000-0000-0000-00000000005a"
        ), world.events);
    }

    @Test
    void identityMismatchRollsBackAndPersistsCleanupBeforeRemovingSpawn() {
        durability.events = world.events;
        world.spawnMode = RecordingBondedWorld.SpawnMode.IDENTITY_MISMATCH;

        var result = projections.summon(new BondedCompanionProjectionService.SummonRequest(
                uuid(1), "roster-a", "profile-a", 4L, "role-a",
                snapshot(uuid(20)), "world-a", null, -5_000L, -3_000L,
                new BondedCompanionActiveCapacity("family-a", 1)
        ));

        assertEquals(BondedCompanionProjectionService.SummonStatus.SPAWN_FAILED_STORED,
                result.status());
        assertEquals("rollback:profile-a:SPAWN_IDENTITY_MISMATCH",
                world.events.get(2));
        assertTrue(world.events.get(3).startsWith("remove-if-exact:"));
        assertFalse(durability.spawnFailureCleanups.isEmpty());
    }

    @Test
    void rollbackDurabilityFailureLeavesSpawnRecoveryAndDoesNotTouchWorld() {
        durability.events = world.events;
        durability.rollbackSucceeds = false;
        world.spawnMode = RecordingBondedWorld.SpawnMode.IDENTITY_MISMATCH;

        var result = projections.summon(new BondedCompanionProjectionService.SummonRequest(
                uuid(1), "roster-a", "profile-a", 4L, "role-a",
                snapshot(uuid(20)), "world-a", null, -5_000L, -3_000L,
                new BondedCompanionActiveCapacity("family-a", 1)
        ));

        assertEquals(BondedCompanionProjectionService.SummonStatus.SPAWN_ROLLBACK_PENDING,
                result.status());
        assertEquals(BondedCompanionState.ACTIVE, durability.states.get("profile-a"));
        assertTrue(durability.spawnRecovery.containsKey("profile-a"));
        assertTrue(world.removed.isEmpty());
        assertEquals(3, world.events.size());
    }

    @Test
    void runtimeReconciliationPreservesPendingSummonForStartupSettlement() {
        var pending = lease(uuid(40), "world-a",
                BondedCompanionProjectionValidator.LeasePhase.PENDING, 0L);
        durability.activate(pending);

        var result = projections.reconcile(
                pending, List.of(),
                BondedCompanionProjectionService.RecoveryCause.MISSING_SCAN,
                -900L);

        assertEquals(
                BondedCompanionProjectionService.ReconcileStatus
                        .PENDING_IN_PROGRESS,
                result.status());
        assertEquals(BondedCompanionState.ACTIVE,
                durability.states.get("profile-a"));
        assertTrue(durability.reconciledCleanups.isEmpty());
        assertTrue(durability.spawnRecovery.containsKey("profile-a"));
        assertEquals(List.of(), durability.cleanupRetentions);
        assertTrue(world.removed.isEmpty());
    }

    @Test
    void storeReadsSnapshotThenCommitsBeforeExactProjectionRemoval() {
        durability.events = world.events;
        var lease = lease(uuid(40), "world-a",
                BondedCompanionProjectionValidator.LeasePhase.LIVE, -1_000L);
        var live = projection(uuid(40), "world-a", snapshot(uuid(40)));
        durability.activate(lease, snapshot(uuid(20)));
        world.projections.add(live);

        var result = projections.store(new BondedCompanionProjectionService.StoreRequest(
                lease, 5L, -900L, storeOperation("store-order")
        ));

        assertEquals(BondedCompanionProjectionService.StoreStatus.STORED,
                result.status());
        assertEquals(List.of("read:00000000-0000-0000-0000-000000000028",
                "store:profile-a",
                "remove-if-exact:world-a:00000000-0000-0000-0000-000000000028"),
                world.events);
        assertEquals(BondedCompanionState.STORED, durability.states.get("profile-a"));
    }

    @Test
    void matchingStoreReplayReturnsBeforeWorldSnapshotReadOrCleanup() {
        var lease = lease(uuid(40), "world-a",
                BondedCompanionProjectionValidator.LeasePhase.LIVE, -1_000L);
        durability.activate(lease, snapshot(uuid(20)));
        durability.storeProbe = new BondedCompanionProjectionService
                .StoreDurabilityResult(
                BondedCompanionProjectionService.StoreDurabilityStatus.REPLAYED);

        var result = projections.store(
                new BondedCompanionProjectionService.StoreRequest(
                        lease, 5L, -900L, storeOperation("store-replay")));

        assertEquals(BondedCompanionProjectionService.StoreStatus.STORED,
                result.status());
        assertTrue(world.events.isEmpty());
        assertTrue(world.removed.isEmpty());
    }

    @Test
    void storePreservesSnapshotStateAbsentFromTheLiveProjection() {
        var lease = lease(uuid(40), "world-a",
                BondedCompanionProjectionValidator.LeasePhase.LIVE, -1_000L);
        world.projections.add(projection(
                uuid(40), "world-a", snapshot(uuid(40))));
        BondedCompanionSnapshot stored = BondedCompanionSnapshot.of(
                snapshot(uuid(20)).fullState(),
                Map.of("hydragon.abilities", "{\"attuned\":true}")
        );
        durability.activate(lease, stored);

        var result = projections.store(
                new BondedCompanionProjectionService.StoreRequest(
                        lease, 5L, -900L, storeOperation("store-preserve")
                )
        );

        assertEquals(BondedCompanionProjectionService.StoreStatus.STORED,
                result.status());
        assertEquals(
                "{\"attuned\":true}",
                durability.lastStoredSnapshot.extensionData()
                        .get("hydragon.abilities")
        );
    }

    @Test
    void manualStoreRejectsLiveSnapshotOwnedByAnotherPlayer() {
        var lease = lease(uuid(40), "world-a",
                BondedCompanionProjectionValidator.LeasePhase.LIVE, -1_000L);
        durability.activate(lease, snapshot(uuid(20)));
        world.projections.add(projection(
                uuid(40), "world-a",
                snapshot(uuid(40), uuid(2), "role-a", null, Map.of())));

        var result = projections.store(
                new BondedCompanionProjectionService.StoreRequest(
                        lease, 5L, -900L, storeOperation("store-owner")
                )
        );

        assertEquals(
                BondedCompanionProjectionService.StoreStatus.DURABILITY_REJECTED,
                result.status()
        );
        assertEquals(BondedCompanionState.ACTIVE,
                durability.states.get("profile-a"));
        assertTrue(world.removed.isEmpty());
    }

    @Test
    void manualStoreRejectsLiveSnapshotWithAnotherRole() {
        var lease = lease(uuid(40), "world-a",
                BondedCompanionProjectionValidator.LeasePhase.LIVE, -1_000L);
        durability.activate(lease, snapshot(uuid(20)));
        world.projections.add(projection(
                uuid(40), "world-a",
                snapshot(uuid(40), uuid(1), "role-b", null, Map.of())));

        var result = projections.store(
                new BondedCompanionProjectionService.StoreRequest(
                        lease, 5L, -900L, storeOperation("store-role")
                )
        );

        assertEquals(
                BondedCompanionProjectionService.StoreStatus.DURABILITY_REJECTED,
                result.status()
        );
        assertEquals(BondedCompanionState.ACTIVE,
                durability.states.get("profile-a"));
        assertTrue(world.removed.isEmpty());
    }

    @Test
    void recoversInterruptionAfterStoreDurabilityBeforeRemoval() {
        var projection = projection(uuid(40), "world-a", snapshot(uuid(40)));
        world.projections.add(projection);
        var intent = BondedCompanionProjectionCleanupService.CleanupIntent.projection(
                "cleanup-store", uuid(1), "roster-a", "profile-a",
                "lease-a", uuid(40), "world-a", "store", -900L
        );

        var outcome = cleanup.recover(intent);

        assertEquals(BondedCompanionProjectionCleanupService.Outcome.REMOVED, outcome);
        assertEquals(List.of(uuid(40)), world.removed);
    }

    @Test
    void recoversInterruptionAfterDurableCaptureBeforeExactSourceRemoval() {
        world.sources.put(uuid(70), "world-a");
        var intent = BondedCompanionProjectionCleanupService.CleanupIntent.source(
                "cleanup-capture", uuid(1), "roster-a", "profile-a",
                uuid(70), "world-a", "capture", -900L
        );

        var outcome = cleanup.recover(intent);

        assertEquals(BondedCompanionProjectionCleanupService.Outcome.REMOVED, outcome);
        assertEquals(List.of(uuid(70)), world.removed);
        assertFalse(world.removed.contains(uuid(71)));
    }

    @Test
    void delayedCleanupCannotRemoveReplacementMarkerOrOtherWorldEntity() {
        var intent = BondedCompanionProjectionCleanupService.CleanupIntent.projection(
                "cleanup-store", uuid(1), "roster-a", "profile-a",
                "lease-a", uuid(40), "world-a", "store", -900L
        );
        world.projections.add(projection(
                uuid(40), "world-a", snapshot(uuid(40)),
                "profile-a", "replacement-lease"));

        var replacement = cleanup.recover(intent);
        world.projections.clear();
        world.projections.add(projection(
                uuid(40), "world-b", snapshot(uuid(40))));
        var otherWorld = cleanup.recover(intent);

        assertEquals(BondedCompanionProjectionCleanupService.Outcome.IDENTITY_MISMATCH,
                replacement);
        assertEquals(BondedCompanionProjectionCleanupService.Outcome.IDENTITY_MISMATCH,
                otherWorld);
        assertTrue(world.removed.isEmpty());
    }

    @Test
    void duplicateExactProjectionsStoreProfileAndRemoveOnlyThoseDuplicates() {
        var lease = lease(uuid(40), "world-a",
                BondedCompanionProjectionValidator.LeasePhase.LIVE, -1_000L);
        durability.activate(lease);
        world.projections.add(projection(uuid(40), "world-a", snapshot(uuid(40))));
        world.projections.add(projection(uuid(41), "world-a", snapshot(uuid(41))));
        world.projections.add(new BondedCompanionProjectionValidator.Projection(
                uuid(42), "world-a",
                TameworkProjectionIdentityComponent.bondedCompanion(
                        "profile-a", "other-lease"), null
        ));

        observer.onProjectionMissingScan(List.of(lease), -900L);

        assertEquals(BondedCompanionState.STORED, durability.states.get("profile-a"));
        assertEquals(List.of(uuid(40), uuid(41)), world.removed);
        assertEquals(List.of(2_591_999_100L, 2_591_999_100L),
                durability.cleanupRetentions);
        assertFalse(world.removed.contains(uuid(42)));
    }

    @Test
    void expiryUsesZeroOnlyForUnlimitedAndPreservesNegativeWorldTime() {
        var finite = lease(uuid(40), "world-a",
                BondedCompanionProjectionValidator.LeasePhase.LIVE, -1_000L);
        durability.activate(finite);
        var observed = projection(uuid(40), "world-a", snapshot(uuid(40)));

        var result = projections.reconcile(
                finite, List.of(observed),
                BondedCompanionProjectionService.RecoveryCause.MISSING_SCAN,
                -900L);

        assertEquals(BondedCompanionProjectionService.ReconcileStatus.STORED,
                result.status());
        assertTrue(BondedCompanionExpirySystem.isExpired(-1_000L, -900L));
        assertFalse(BondedCompanionExpirySystem.isExpired(0L, Long.MAX_VALUE));
        assertEquals(BondedCompanionState.STORED, durability.states.get("profile-a"));
    }

    @Test
    void startupIndependentlyStoresAValidProjectionWithSignedExpiredLease() {
        var expired = lease(uuid(40), "world-a",
                BondedCompanionProjectionValidator.LeasePhase.LIVE, -1_000L);
        durability.activate(expired);
        world.projections.add(projection(uuid(40), "world-a", snapshot(uuid(40))));

        observer.onStartup(List.of(expired), -900L);

        assertEquals(BondedCompanionState.STORED, durability.states.get("profile-a"));
        assertEquals("LEASE_EXPIRED", durability.lastReason);
        assertEquals(List.of(uuid(40)), world.removed);
    }

    @Test
    void transferLogoutAndMissingProjectionAreAlwaysStored() {
        var transfer = lease("profile-transfer", "lease-transfer", uuid(50), "world-a");
        var logout = lease("profile-logout", "lease-logout", uuid(51), "world-a");
        var missing = lease("profile-missing", "lease-missing", uuid(52), "world-a");
        durability.activate(transfer);
        durability.activate(logout);
        durability.activate(missing);
        world.projections.add(projection(uuid(50), "world-a", snapshot(uuid(50)),
                "profile-transfer", "lease-transfer"));
        world.projections.add(projection(uuid(51), "world-a", snapshot(uuid(51)),
                "profile-logout", "lease-logout"));

        observer.onPlayerWorldTransfer(
                uuid(1), "world-a", "world-b", List.of(transfer), -700L);
        observer.onPlayerLogout(uuid(1), List.of(logout), -600L);
        observer.onProjectionMissingScan(List.of(missing), -500L);

        assertEquals(BondedCompanionState.STORED, durability.states.get("profile-transfer"));
        assertEquals(BondedCompanionState.STORED, durability.states.get("profile-logout"));
        assertEquals(BondedCompanionState.STORED, durability.states.get("profile-missing"));
        assertFalse(durability.states.containsValue(BondedCompanionState.DEAD));
    }

    @Test
    void logoutPreservesDurableExtensionsAndUnobservedOptionalState() {
        var lease = lease(uuid(40), "world-a",
                BondedCompanionProjectionValidator.LeasePhase.LIVE, 0L);
        durability.activate(lease, durableSnapshot(uuid(20)));
        world.projections.add(projection(
                uuid(40), "world-a", snapshot(uuid(40))));

        observer.onPlayerLogout(uuid(1), List.of(lease), -600L);

        assertDurableSnapshotSurvived("profile-a");
    }

    @Test
    void worldTransferPreservesDurableExtensionsAndUnobservedOptionalState() {
        var lease = lease(uuid(40), "world-a",
                BondedCompanionProjectionValidator.LeasePhase.LIVE, 0L);
        durability.activate(lease, durableSnapshot(uuid(20)));
        world.projections.add(projection(
                uuid(40), "world-a", snapshot(uuid(40))));

        observer.onPlayerWorldTransfer(
                uuid(1), "world-a", "world-b", List.of(lease), -600L);

        assertDurableSnapshotSurvived("profile-a");
    }

    @Test
    void expiryPreservesDurableExtensionsAndUnobservedOptionalState() {
        var lease = lease(uuid(40), "world-a",
                BondedCompanionProjectionValidator.LeasePhase.LIVE, -1_000L);
        durability.activate(lease, durableSnapshot(uuid(20)));
        world.projections.add(projection(
                uuid(40), "world-a", snapshot(uuid(40))));

        observer.onLeaseExpired(lease, -900L);

        assertDurableSnapshotSurvived("profile-a");
    }

    @Test
    void reconciliationRejectsPresentSnapshotWithWrongOwner() {
        var lease = lease(uuid(40), "world-a",
                BondedCompanionProjectionValidator.LeasePhase.LIVE, 0L);
        durability.activate(lease, durableSnapshot(uuid(20)));
        var observed = projection(
                uuid(40), "world-a",
                snapshot(uuid(40), uuid(2), "role-a", null, Map.of()));

        var result = projections.reconcile(
                lease, List.of(observed),
                BondedCompanionProjectionService.RecoveryCause.LOGOUT,
                -600L
        );

        assertEquals(
                BondedCompanionProjectionService.ReconcileStatus.IDENTITY_MISMATCH,
                result.status()
        );
        assertEquals(BondedCompanionState.ACTIVE,
                durability.states.get("profile-a"));
    }

    @Test
    void onlyConfirmedDeathAuthorsDead() {
        var lease = lease(uuid(40), "world-a",
                BondedCompanionProjectionValidator.LeasePhase.LIVE, -1_000L);
        durability.activate(lease);
        var projection = projection(uuid(40), "world-a", snapshot(uuid(40)));

        observer.onConfirmedDeath(lease, projection, -800L);

        assertEquals(BondedCompanionState.DEAD, durability.states.get("profile-a"));
        assertEquals("CONFIRMED_DEATH", durability.lastReason);
    }

    @Test
    void confirmedDeathMergesSparseLiveEvidenceIntoDurableSnapshot() {
        var lease = lease(uuid(40), "world-a",
                BondedCompanionProjectionValidator.LeasePhase.LIVE, -1_000L);
        durability.activate(lease, durableSnapshot(uuid(20)));

        var result = projections.confirmDeath(
                lease,
                projection(uuid(40), "world-a", snapshot(uuid(40))),
                -800L
        );

        assertEquals(BondedCompanionProjectionService.ReconcileStatus.DEAD,
                result.status());
        assertDurableSnapshotSurvived("profile-a");
    }

    @Test
    void confirmedDeathRejectsLiveSnapshotWithAnotherOwner() {
        var lease = lease(uuid(40), "world-a",
                BondedCompanionProjectionValidator.LeasePhase.LIVE, -1_000L);
        durability.activate(lease, durableSnapshot(uuid(20)));
        var wrongOwner = snapshot(
                uuid(40), uuid(2), "role-a", null, Map.of());

        var result = projections.confirmDeath(
                lease, projection(uuid(40), "world-a", wrongOwner), -800L
        );

        assertEquals(
                BondedCompanionProjectionService.ReconcileStatus
                        .IDENTITY_MISMATCH,
                result.status()
        );
        assertEquals(BondedCompanionState.ACTIVE,
                durability.states.get("profile-a"));
    }

    private BondedCompanionProjectionValidator.LeaseExpectation lease(
            UUID npcUuid, String worldKey,
            BondedCompanionProjectionValidator.LeasePhase phase,
            long expiresAtMs
    ) {
        return lease("profile-a", "lease-a", npcUuid, worldKey, phase, expiresAtMs);
    }

    private BondedCompanionProjectionValidator.LeaseExpectation lease(
            String profileId, String leaseToken, UUID npcUuid, String worldKey
    ) {
        return lease(profileId, leaseToken, npcUuid, worldKey,
                BondedCompanionProjectionValidator.LeasePhase.LIVE, -1_000L);
    }

    private BondedCompanionProjectionValidator.LeaseExpectation lease(
            String profileId, String leaseToken, UUID npcUuid, String worldKey,
            BondedCompanionProjectionValidator.LeasePhase phase, long expiresAtMs
    ) {
        return new BondedCompanionProjectionValidator.LeaseExpectation(
                uuid(1), "roster-a", profileId, leaseToken, npcUuid,
                worldKey, -2_000L, expiresAtMs, phase
        );
    }

    private BondedCompanionProjectionValidator.Projection projection(
            UUID npcUuid, String worldKey, BondedCompanionSnapshot snapshot
    ) {
        return projection(npcUuid, worldKey, snapshot, "profile-a", "lease-a");
    }

    private BondedCompanionProjectionValidator.Projection projection(
            UUID npcUuid, String worldKey, BondedCompanionSnapshot snapshot,
            String profileId, String leaseToken
    ) {
        return new BondedCompanionProjectionValidator.Projection(
                npcUuid, worldKey,
                TameworkProjectionIdentityComponent.bondedCompanion(
                        profileId, leaseToken), snapshot
        );
    }

    private BondedCompanionSnapshot durableSnapshot(UUID npcUuid) {
        return snapshot(
                npcUuid, uuid(1), "role-a", "Durable Drake",
                Map.of("hydragon.abilities", "{\"attuned\":true}")
        );
    }

    private void assertDurableSnapshotSurvived(String profileId) {
        BondedCompanionSnapshot stored = durability.snapshots.get(profileId);
        assertEquals(
                "{\"attuned\":true}",
                stored.extensionData().get("hydragon.abilities")
        );
        assertEquals("Durable Drake",
                stored.fullState().npcName().getName());
    }

    private BondedCompanionSnapshot snapshot(UUID npcUuid) {
        return snapshot(npcUuid, uuid(1), "role-a", null, Map.of());
    }

    private BondedCompanionSnapshot snapshot(
            UUID npcUuid,
            UUID ownerUuid,
            String roleId,
            String name,
            Map<String, String> extensions
    ) {
        return BondedCompanionSnapshot.of(new CoopResidentStateSnapshot(
                npcUuid, null, -1, roleId, null,
                new TameworkOwnerComponent(ownerUuid, "Owner"),
                new TameworkTamedComponent(true),
                name == null ? null : new TameworkNpcNameComponent(
                        name, ownerUuid, -1L,
                        TameworkNpcNameComponent.NameSource.System
                ),
                null, null, null, null, null, null, null, null, -1.0, -1L
        ), extensions);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }

    private BondedCompanionOperation storeOperation(String key) {
        return new BondedCompanionOperation(
                "test", key, "a".repeat(64), uuid(1), "roster-a",
                "profile-a", BondedCompanionOperation.Type.STORE,
                -900L, 10_000L,
                new BondedCompanionOperation.StoreLeaseIdentity(
                        "lease-a", uuid(40), "world-a"));
    }
}
