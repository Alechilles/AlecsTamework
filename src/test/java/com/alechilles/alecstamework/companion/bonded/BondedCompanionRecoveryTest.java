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
    void failedSpawnInvalidatesLeaseAndReturnsProfileToStored() {
        world.spawnSucceeds = false;

        var result = projections.summon(new BondedCompanionProjectionService.SummonRequest(
                uuid(1), "roster-a", "profile-a", 4L, "role-a",
                snapshot(uuid(20)), "world-a", -5_000L, -3_000L
        ));

        assertEquals(BondedCompanionProjectionService.SummonStatus.SPAWN_FAILED_STORED,
                result.status());
        assertEquals(BondedCompanionState.STORED, durability.states.get("profile-a"));
        assertTrue(durability.spawnRecovery.isEmpty());
    }

    @Test
    void startupRecoversInterruptionAfterLeaseCreationBeforeSpawn() {
        var pending = lease(uuid(40), "world-a",
                BondedCompanionProjectionValidator.LeasePhase.PENDING, -1_000L);
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
                "store:profile-a", "remove:00000000-0000-0000-0000-000000000028"),
                world.events);
        assertEquals(BondedCompanionState.STORED, durability.states.get("profile-a"));
    }

    @Test
    void recoversInterruptionAfterStoreDurabilityBeforeRemoval() {
        var projection = projection(uuid(40), "world-a", snapshot(uuid(40)));
        world.projections.add(projection);
        var intent = BondedCompanionProjectionCleanupService.CleanupIntent.projection(
                "cleanup-store", uuid(1), "roster-a", "profile-a",
                "lease-a", uuid(40), "store", -900L
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
                uuid(70), "capture", -900L
        );

        var outcome = cleanup.recover(intent);

        assertEquals(BondedCompanionProjectionCleanupService.Outcome.REMOVED, outcome);
        assertEquals(List.of(uuid(70)), world.removed);
        assertFalse(world.removed.contains(uuid(71)));
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
        private String lastReason;

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
        public void failSpawn(
                BondedCompanionProjectionValidator.LeaseExpectation lease,
                String reason
        ) {
            states.put(lease.profileId(), BondedCompanionState.STORED);
            spawnRecovery.remove(lease.profileId());
            lastReason = reason;
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
        private boolean spawnSucceeds = true;

        @Override
        public BondedCompanionProjectionService.SpawnResult spawn(
                BondedCompanionProjectionService.SpawnPlan plan
        ) {
            events.add("spawn:" + plan.lease().profileId() + ":"
                    + plan.lease().leaseToken());
            if (!spawnSucceeds) {
                return BondedCompanionProjectionService.SpawnResult.failed();
            }
            projections.add(new BondedCompanionProjectionValidator.Projection(
                    plan.lease().liveNpcUuid(), plan.lease().worldKey(),
                    plan.marker(), plan.snapshot()
            ));
            return BondedCompanionProjectionService.SpawnResult.spawned(
                    plan.lease().liveNpcUuid()
            );
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
        public BondedCompanionProjectionCleanupService.ObservedTarget find(
                UUID targetNpcUuid
        ) {
            for (var projection : projections) {
                if (projection.npcUuid().equals(targetNpcUuid)) {
                    return new BondedCompanionProjectionCleanupService.ObservedTarget(
                            targetNpcUuid, projection.worldKey(), projection.marker()
                    );
                }
            }
            String world = sources.get(targetNpcUuid);
            return world == null ? null
                    : new BondedCompanionProjectionCleanupService.ObservedTarget(
                            targetNpcUuid, world, null
                    );
        }

        @Override
        public boolean remove(UUID targetNpcUuid) {
            events.add("remove:" + targetNpcUuid);
            removed.add(targetNpcUuid);
            projections.removeIf(projection ->
                    projection.npcUuid().equals(targetNpcUuid));
            sources.remove(targetNpcUuid);
            return true;
        }
    }
}
