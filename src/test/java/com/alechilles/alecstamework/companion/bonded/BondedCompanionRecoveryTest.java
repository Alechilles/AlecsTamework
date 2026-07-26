package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import java.util.ArrayList;
import java.util.HashMap;
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
    private RecordingDurability durability;
    private RecordingWorld world;
    private BondedCompanionProjectionCleanupService cleanup;
    private BondedCompanionProjectionService projections;
    private BondedCompanionWorldLifecycleObserver observer;

    @BeforeEach
    void setUp() {
        durability = new RecordingDurability();
        world = new RecordingWorld();
        cleanup = new BondedCompanionProjectionCleanupService(world);
        projections = new BondedCompanionProjectionService(
                durability, world, cleanup,
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
                snapshot(uuid(20)), "world-a", -5_000L, -3_000L
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
    void retryableSpawnPersistsRollbackCleanupBeforeWorldRemoval() {
        durability.events = world.events;
        world.spawnMode = RecordingWorld.SpawnMode.RETRYABLE;

        var result = projections.summon(new BondedCompanionProjectionService.SummonRequest(
                uuid(1), "roster-a", "profile-a", 4L, "role-a",
                snapshot(uuid(20)), "world-a", -5_000L, -3_000L
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
        world.spawnMode = RecordingWorld.SpawnMode.THROW;
        world.removeSucceeds = false;

        var result = projections.summon(new BondedCompanionProjectionService.SummonRequest(
                uuid(1), "roster-a", "profile-a", 4L, "role-a",
                snapshot(uuid(20)), "world-a", -5_000L, -3_000L
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
        world.spawnMode = RecordingWorld.SpawnMode.IDENTITY_MISMATCH;

        var result = projections.summon(new BondedCompanionProjectionService.SummonRequest(
                uuid(1), "roster-a", "profile-a", 4L, "role-a",
                snapshot(uuid(20)), "world-a", -5_000L, -3_000L
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
        world.spawnMode = RecordingWorld.SpawnMode.IDENTITY_MISMATCH;

        var result = projections.summon(new BondedCompanionProjectionService.SummonRequest(
                uuid(1), "roster-a", "profile-a", 4L, "role-a",
                snapshot(uuid(20)), "world-a", -5_000L, -3_000L
        ));

        assertEquals(BondedCompanionProjectionService.SummonStatus.SPAWN_ROLLBACK_PENDING,
                result.status());
        assertEquals(BondedCompanionState.ACTIVE, durability.states.get("profile-a"));
        assertTrue(durability.spawnRecovery.containsKey("profile-a"));
        assertTrue(world.removed.isEmpty());
        assertEquals(3, world.events.size());
    }

    @Test
    void startupRecoversInterruptionAfterLeaseCreationBeforeSpawn() {
        var pending = lease(uuid(40), "world-a",
                BondedCompanionProjectionValidator.LeasePhase.PENDING, 0L);
        durability.activate(pending);

        observer.onStartup(List.of(pending), -900L);

        assertEquals(BondedCompanionState.STORED, durability.states.get("profile-a"));
        assertEquals("SPAWN_INTERRUPTED", durability.lastReason);
        assertEquals(List.of(), durability.cleanupRetentions);
        assertTrue(world.removed.isEmpty());
    }

    @Test
    void storeReadsSnapshotThenCommitsBeforeExactProjectionRemoval() {
        durability.events = world.events;
        var lease = lease(uuid(40), "world-a",
                BondedCompanionProjectionValidator.LeasePhase.LIVE, -1_000L);
        var live = projection(uuid(40), "world-a", snapshot(uuid(40)));
        durability.activate(lease);
        world.projections.add(live);

        var result = projections.store(new BondedCompanionProjectionService.StoreRequest(
                lease, 5L, -900L
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
        assertEquals(List.of(299_100L, 299_100L), durability.cleanupRetentions);
        assertFalse(world.removed.contains(uuid(42)));
    }

    @Test
    void expiryUsesZeroOnlyForUnlimitedAndPreservesNegativeWorldTime() {
        var finite = lease(uuid(40), "world-a",
                BondedCompanionProjectionValidator.LeasePhase.LIVE, -1_000L);
        var unlimited = lease(uuid(41), "world-a",
                BondedCompanionProjectionValidator.LeasePhase.LIVE, 0L);
        durability.activate(finite);
        durability.activate(unlimited);
        world.projections.add(projection(uuid(40), "world-a", snapshot(uuid(40))));
        world.projections.add(projection(uuid(41), "world-a", snapshot(uuid(41)),
                "profile-a", "lease-a"));
        var expiry = new BondedCompanionExpirySystem(observer,
                (now, limit) -> List.of(finite, unlimited), 8);

        int expired = expiry.tick(-900L);

        assertEquals(1, expired);
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
    void onlyConfirmedDeathAuthorsDead() {
        var lease = lease(uuid(40), "world-a",
                BondedCompanionProjectionValidator.LeasePhase.LIVE, -1_000L);
        durability.activate(lease);
        var projection = projection(uuid(40), "world-a", snapshot(uuid(40)));

        observer.onConfirmedDeath(lease, projection, -800L);

        assertEquals(BondedCompanionState.DEAD, durability.states.get("profile-a"));
        assertEquals("CONFIRMED_DEATH", durability.lastReason);
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

    private BondedCompanionSnapshot snapshot(UUID npcUuid) {
        return BondedCompanionSnapshot.of(new CoopResidentStateSnapshot(
                npcUuid, null, -1, "role-a", null, null, null, null, null,
                null, null, null, null, null, null, null, -1.0, -1L
        ), Map.of());
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }

    private static final class RecordingDurability
            implements BondedCompanionProjectionService.Durability {
        private final Map<String, BondedCompanionState> states = new HashMap<>();
        private final Map<String, BondedCompanionProjectionValidator.LeaseExpectation>
                spawnRecovery = new HashMap<>();
        private List<String> events = new ArrayList<>();
        private final List<Long> cleanupRetentions = new ArrayList<>();
        private final List<BondedCompanionProjectionCleanupService.CleanupIntent>
                spawnFailureCleanups = new ArrayList<>();
        private String lastReason;
        private boolean rollbackSucceeds = true;

        void activate(BondedCompanionProjectionValidator.LeaseExpectation lease) {
            states.put(lease.profileId(), BondedCompanionState.ACTIVE);
            spawnRecovery.put(lease.profileId(), lease);
        }

        @Override
        public boolean beginSummon(
                BondedCompanionProjectionService.SummonRequest request,
                BondedCompanionProjectionValidator.LeaseExpectation lease,
                BondedCompanionProjectionCleanupService.CleanupIntent recovery
        ) {
            events.add("begin:" + lease.profileId() + ":" + lease.leaseToken()
                    + ":" + lease.liveNpcUuid());
            states.put(lease.profileId(), BondedCompanionState.ACTIVE);
            spawnRecovery.put(lease.profileId(), lease);
            return true;
        }

        @Override
        public boolean confirmSpawn(
                BondedCompanionProjectionValidator.LeaseExpectation lease,
                UUID spawnedNpcUuid
        ) {
            events.add("confirm:" + lease.profileId() + ":" + lease.leaseToken()
                    + ":" + spawnedNpcUuid);
            spawnRecovery.remove(lease.profileId());
            return lease.liveNpcUuid().equals(spawnedNpcUuid);
        }

        @Override
        public boolean failSpawnAndEnqueueCleanup(
                BondedCompanionProjectionValidator.LeaseExpectation lease,
                List<BondedCompanionProjectionCleanupService.CleanupIntent> cleanups,
                String reason
        ) {
            events.add("rollback:" + lease.profileId() + ":" + reason);
            if (!rollbackSucceeds) {
                return false;
            }
            states.put(lease.profileId(), BondedCompanionState.STORED);
            spawnRecovery.remove(lease.profileId());
            spawnFailureCleanups.addAll(cleanups);
            lastReason = reason;
            return true;
        }

        @Override
        public boolean storeAndEnqueueCleanup(
                BondedCompanionProjectionService.StoreRequest request,
                BondedCompanionSnapshot snapshot,
                BondedCompanionProjectionCleanupService.CleanupIntent cleanup
        ) {
            events.add("store:" + request.lease().profileId());
            states.put(request.lease().profileId(), BondedCompanionState.STORED);
            return true;
        }

        @Override
        public boolean reconcileStored(
                BondedCompanionProjectionValidator.LeaseExpectation lease,
                BondedCompanionSnapshot snapshot,
                List<BondedCompanionProjectionCleanupService.CleanupIntent> cleanups,
                String reason
        ) {
            states.put(lease.profileId(), BondedCompanionState.STORED);
            spawnRecovery.remove(lease.profileId());
            cleanups.forEach(cleanup -> cleanupRetentions.add(
                    cleanup.retainedUntilMs()));
            lastReason = reason;
            return true;
        }

        @Override
        public boolean confirmDeath(
                BondedCompanionProjectionValidator.LeaseExpectation lease,
                BondedCompanionSnapshot snapshot, long diedAtMs
        ) {
            states.put(lease.profileId(), BondedCompanionState.DEAD);
            spawnRecovery.remove(lease.profileId());
            lastReason = "CONFIRMED_DEATH";
            return true;
        }
    }

    private static final class RecordingWorld
            implements BondedCompanionProjectionService.World,
            BondedCompanionProjectionCleanupService.WorldGateway,
            BondedCompanionWorldLifecycleObserver.ProjectionSource {
        private final List<String> events = new ArrayList<>();
        private final List<BondedCompanionProjectionValidator.Projection> projections =
                new ArrayList<>();
        private final Map<UUID, String> sources = new HashMap<>();
        private final List<UUID> removed = new ArrayList<>();
        private SpawnMode spawnMode = SpawnMode.SPAWNED;
        private boolean removeSucceeds = true;

        @Override
        public BondedCompanionProjectionService.SpawnResult spawn(
                BondedCompanionProjectionService.SpawnPlan plan
        ) {
            events.add("spawn:" + plan.lease().profileId() + ":"
                    + plan.lease().leaseToken());
            if (spawnMode == SpawnMode.THROW) {
                throw new IllegalStateException("spawn failed");
            }
            if (spawnMode == SpawnMode.RETRYABLE) {
                return BondedCompanionProjectionService.SpawnResult.retryRequired();
            }
            UUID spawnedUuid = spawnMode == SpawnMode.IDENTITY_MISMATCH
                    ? uuid(91) : plan.lease().liveNpcUuid();
            projections.add(new BondedCompanionProjectionValidator.Projection(
                    spawnedUuid, plan.lease().worldKey(),
                    plan.marker(), plan.snapshot()
            ));
            return spawnMode == SpawnMode.IDENTITY_MISMATCH
                    ? BondedCompanionProjectionService.SpawnResult.identityMismatch(
                            spawnedUuid)
                    : BondedCompanionProjectionService.SpawnResult.spawned(spawnedUuid);
        }

        @Override
        public BondedCompanionProjectionValidator.Projection readExact(
                BondedCompanionProjectionValidator.LeaseExpectation lease
        ) {
            events.add("read:" + lease.liveNpcUuid());
            return projections.stream().filter(projection ->
                    projection.npcUuid().equals(lease.liveNpcUuid()))
                    .findFirst().orElse(null);
        }

        @Override
        public List<BondedCompanionProjectionValidator.Projection> projections() {
            return List.copyOf(projections);
        }

        @Override
        public BondedCompanionProjectionCleanupService.Outcome removeIfExact(
                BondedCompanionProjectionCleanupService.CleanupIntent intent) {
            events.add("remove-if-exact:" + intent.worldKey() + ":"
                    + intent.targetNpcUuid());
            for (int index = 0; index < projections.size(); index++) {
                var projection = projections.get(index);
                if (projection.npcUuid().equals(intent.targetNpcUuid())) {
                    boolean exact = projection.worldKey().equals(intent.worldKey())
                            && intent.target()
                            == BondedCompanionProjectionCleanupService.Target.PROJECTION
                            && projection.marker().isBondedCompanion()
                            && intent.profileId().equals(projection.marker().getProfileId())
                            && intent.leaseToken().equals(
                            projection.marker().getBondedLeaseToken());
                    if (!exact) {
                        return BondedCompanionProjectionCleanupService.Outcome.IDENTITY_MISMATCH;
                    }
                    if (!removeSucceeds) {
                        return BondedCompanionProjectionCleanupService.Outcome.RETRY_REQUIRED;
                    }
                    projections.remove(index);
                    removed.add(intent.targetNpcUuid());
                    return BondedCompanionProjectionCleanupService.Outcome.REMOVED;
                }
            }
            String sourceWorld = sources.get(intent.targetNpcUuid());
            if (sourceWorld == null) {
                return BondedCompanionProjectionCleanupService.Outcome.ALREADY_MISSING;
            }
            if (intent.target()
                    != BondedCompanionProjectionCleanupService.Target.SOURCE
                    || !sourceWorld.equals(intent.worldKey())) {
                return BondedCompanionProjectionCleanupService.Outcome.IDENTITY_MISMATCH;
            }
            if (!removeSucceeds) {
                return BondedCompanionProjectionCleanupService.Outcome.RETRY_REQUIRED;
            }
            sources.remove(intent.targetNpcUuid());
            removed.add(intent.targetNpcUuid());
            return BondedCompanionProjectionCleanupService.Outcome.REMOVED;
        }

        private enum SpawnMode { SPAWNED, RETRYABLE, IDENTITY_MISMATCH, THROW }
    }
}
