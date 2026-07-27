package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.npc.components
        .TameworkProjectionIdentityComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for exact current-world bonded projection recovery. */
class BondedCompanionLocalProjectionLifecycleTest {
    private RecordingBondedDurability durability;
    private RecordingBondedWorld world;
    private RecordingLeaseSource leases;
    private RecordingObservationSource observations;
    private BondedCompanionLocalProjectionLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        durability = new RecordingBondedDurability();
        world = new RecordingBondedWorld();
        leases = new RecordingLeaseSource();
        observations = new RecordingObservationSource(world);
        BondedCompanionProjectionCleanupService cleanup =
                new BondedCompanionProjectionCleanupService(world);
        BondedCompanionProjectionService projections =
                new BondedCompanionProjectionService(
                        durability, durability, world, cleanup,
                        () -> "lease-new", () -> uuid(900)
                );
        lifecycle = new BondedCompanionLocalProjectionLifecycle(
                new BondedCompanionWorldLifecycleObserver(projections, world),
                leases, observations, 64, 128
        );
    }

    @Test
    void maintenanceInspectsOnlyTheCurrentWorldAndLeavesForeignMarkersUntouched() {
        var local = lease("profile-local", "lease-local", uuid(40), "world-a");
        var foreign = lease("profile-foreign", "lease-foreign", uuid(50), "world-b");
        durability.activate(local);
        durability.activate(foreign);
        leases.add(local);
        leases.add(foreign);
        world.projections.add(projection(local, local.liveNpcUuid(), "world-a"));
        world.projections.add(projection(local, uuid(41), "world-a"));
        world.projections.add(new BondedCompanionProjectionValidator.Projection(
                uuid(42), "world-a",
                TameworkProjectionIdentityComponent.bondedCompanion(
                        local.profileId(), "replacement-token"), null));
        world.projections.add(projection(foreign, foreign.liveNpcUuid(), "world-b"));

        int submitted = lifecycle.reconcileCurrentWorld(
                "world-a", BondedCompanionProjectionService.RecoveryCause.MISSING_SCAN,
                -500L
        );

        assertEquals(1, submitted);
        assertEquals(List.of("world-a"), leases.worldQueries);
        assertEquals(List.of("world-a"), observations.worldQueries);
        assertEquals(BondedCompanionState.STORED,
                durability.states.get(local.profileId()));
        assertEquals(BondedCompanionState.ACTIVE,
                durability.states.get(foreign.profileId()));
        assertEquals(List.of(local.liveNpcUuid(), uuid(41)), world.removed);
        assertTrue(world.projections.stream().anyMatch(projection ->
                projection.npcUuid().equals(uuid(42))));
        assertTrue(world.projections.stream().anyMatch(projection ->
                projection.npcUuid().equals(foreign.liveNpcUuid())));
    }

    @Test
    void inconclusiveCurrentWorldInspectionNeverInfersMissingOrDeath() {
        var lease = lease("profile-inconclusive", "lease-inconclusive",
                uuid(60), "world-a");
        durability.activate(lease);
        leases.add(lease);
        observations.conclusive = false;

        lifecycle.reconcileCurrentWorld(
                "world-a", BondedCompanionProjectionService.RecoveryCause.MISSING_SCAN,
                -500L
        );

        assertEquals(BondedCompanionState.ACTIVE,
                durability.states.get(lease.profileId()));
        assertTrue(durability.reconciledCleanups.isEmpty());
        assertFalse(durability.states.containsValue(BondedCompanionState.DEAD));
    }

    @Test
    void logoutCapturesReadableLiveSnapshotAndUnavailableWorldRetainsDurableSnapshot() {
        var readable = lease("profile-readable", "lease-readable",
                uuid(70), "world-a");
        var unavailable = lease("profile-unavailable", "lease-unavailable",
                uuid(71), "world-b");
        durability.activate(readable);
        durability.activate(unavailable);
        leases.add(readable);
        leases.add(unavailable);
        BondedCompanionSnapshot readableDurable =
                durability.snapshots.get(readable.profileId());
        BondedCompanionSnapshot unavailableDurable =
                durability.snapshots.get(unavailable.profileId());
        BondedCompanionSnapshot live = BondedCompanionSnapshot.of(
                readableDurable.fullState(), Map.of("test.live", "{\"value\":2}"));
        world.projections.add(new BondedCompanionProjectionValidator.Projection(
                readable.liveNpcUuid(), readable.worldKey(),
                TameworkProjectionIdentityComponent.bondedCompanion(
                        readable.profileId(), readable.leaseToken()), live));
        observations.unavailableWorlds.add("world-b");

        int submitted = lifecycle.storeOwner(
                readable.ownerUuid(),
                BondedCompanionProjectionService.RecoveryCause.LOGOUT, -400L
        );

        assertEquals(2, submitted);
        assertEquals(BondedCompanionState.STORED,
                durability.states.get(readable.profileId()));
        assertEquals(BondedCompanionState.STORED,
                durability.states.get(unavailable.profileId()));
        assertEquals("{\"value\":2}", durability.snapshots
                .get(readable.profileId()).extensionData().get("test.live"));
        assertEquals(unavailableDurable.extensionData(), durability.snapshots
                .get(unavailable.profileId()).extensionData());
        assertEquals(unavailableDurable.fullState().npcUuid(), durability.snapshots
                .get(unavailable.profileId()).fullState().npcUuid());
        assertFalse(durability.states.containsValue(BondedCompanionState.DEAD));
        assertEquals(unavailable.liveNpcUuid(), durability.reconciledCleanups.stream()
                .filter(cleanup -> cleanup.profileId().equals(unavailable.profileId()))
                .findFirst().orElseThrow().targetNpcUuid());
    }

    @Test
    void confirmedDeathResolvesTheExactMarkerWithoutAGlobalLeasePage() {
        var lease = lease("profile-death", "lease-death", uuid(80), "world-a");
        durability.activate(lease);
        leases.add(lease);
        var death = projection(lease, lease.liveNpcUuid(), "world-a");

        lifecycle.onConfirmedDeath(death, -300L);

        assertEquals(List.of("profile-death:lease-death"), leases.exactQueries);
        assertTrue(leases.worldQueries.isEmpty());
        assertEquals(BondedCompanionState.DEAD,
                durability.states.get(lease.profileId()));
    }

    private static BondedCompanionProjectionValidator.LeaseExpectation lease(
            String profileId, String token, UUID npcUuid, String worldKey
    ) {
        return new BondedCompanionProjectionValidator.LeaseExpectation(
                uuid(1), "roster-a", profileId, token, npcUuid, worldKey,
                -1_000L, 0L, BondedCompanionProjectionValidator.LeasePhase.LIVE
        );
    }

    private static BondedCompanionProjectionValidator.Projection projection(
            BondedCompanionProjectionValidator.LeaseExpectation lease,
            UUID npcUuid,
            String worldKey
    ) {
        return new BondedCompanionProjectionValidator.Projection(
                npcUuid, worldKey,
                TameworkProjectionIdentityComponent.bondedCompanion(
                        lease.profileId(), lease.leaseToken()), null
        );
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }

    private static final class RecordingLeaseSource implements
            BondedCompanionLocalProjectionLifecycle.LeaseSource {
        private final List<BondedCompanionProjectionValidator.LeaseExpectation>
                leases = new ArrayList<>();
        private final List<String> worldQueries = new ArrayList<>();
        private final List<String> exactQueries = new ArrayList<>();

        void add(BondedCompanionProjectionValidator.LeaseExpectation lease) {
            leases.add(lease);
        }

        @Override
        public List<BondedCompanionProjectionValidator.LeaseExpectation> inWorld(
                String worldKey, int limit
        ) {
            worldQueries.add(worldKey);
            return leases.stream().filter(lease -> lease.worldKey().equals(worldKey))
                    .limit(limit).toList();
        }

        @Override
        public List<BondedCompanionProjectionValidator.LeaseExpectation> forOwner(
                UUID ownerUuid, int limit
        ) {
            return leases.stream().filter(lease -> lease.ownerUuid().equals(ownerUuid))
                    .limit(limit).toList();
        }

        @Override
        public List<BondedCompanionProjectionValidator.LeaseExpectation>
        forOwnerInWorld(UUID ownerUuid, String worldKey, int limit) {
            return leases.stream().filter(lease ->
                    lease.ownerUuid().equals(ownerUuid)
                            && lease.worldKey().equals(worldKey))
                    .limit(limit).toList();
        }

        @Override
        public Optional<BondedCompanionProjectionValidator.LeaseExpectation> exact(
                String profileId, String leaseToken
        ) {
            exactQueries.add(profileId + ":" + leaseToken);
            return leases.stream().filter(lease ->
                    lease.profileId().equals(profileId)
                            && lease.leaseToken().equals(leaseToken)).findFirst();
        }
    }

    private static final class RecordingObservationSource implements
            BondedCompanionLocalProjectionLifecycle.ObservationSource {
        private final RecordingBondedWorld world;
        private final List<String> worldQueries = new ArrayList<>();
        private final List<String> unavailableWorlds = new ArrayList<>();
        private boolean conclusive = true;

        private RecordingObservationSource(RecordingBondedWorld world) {
            this.world = world;
        }

        @Override
        public java.util.concurrent.CompletionStage<
                BondedCompanionLocalProjectionLifecycle.WorldObservation> inspect(
                String worldKey,
                List<BondedCompanionProjectionValidator.LeaseExpectation> leases,
                int maximumObservations
        ) {
            worldQueries.add(worldKey);
            if (unavailableWorlds.contains(worldKey)) {
                return CompletableFuture.completedFuture(
                        BondedCompanionLocalProjectionLifecycle.WorldObservation
                                .inconclusive());
            }
            List<BondedCompanionProjectionValidator.Projection> found =
                    world.projections.stream().filter(projection ->
                            projection.worldKey().equals(worldKey)).limit(maximumObservations)
                            .toList();
            return CompletableFuture.completedFuture(
                    new BondedCompanionLocalProjectionLifecycle.WorldObservation(
                            found, conclusive));
        }
    }
}
