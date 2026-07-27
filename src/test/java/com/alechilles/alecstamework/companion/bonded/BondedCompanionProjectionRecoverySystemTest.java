package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runtime-maintenance coverage for bounded bonded projection reconciliation. */
class BondedCompanionProjectionRecoverySystemTest {
    private RecordingBondedDurability durability;
    private RecordingBondedWorld world;

    @BeforeEach
    void setUp() {
        durability = new RecordingBondedDurability();
        world = new RecordingBondedWorld();
    }

    @Test
    void maintenanceStoresAnActiveLeaseWhenItsProjectionIsMissing() {
        var lease = lease("profile-missing", "lease-missing", uuid(40));
        durability.activate(lease);
        BondedCompanionProjectionRecoverySystem recovery =
                new BondedCompanionProjectionRecoverySystem(
                        observer(), ignored -> List.of(lease), 8
                );

        int reconciled = recovery.tick(-500L);

        assertEquals(1, reconciled);
        assertEquals(BondedCompanionState.STORED,
                durability.states.get("profile-missing"));
        assertFalse(durability.states.containsValue(BondedCompanionState.DEAD));
        assertTrue(world.removed.isEmpty());
        assertEquals(1, durability.reconciledCleanups.size());
        var cleanup = durability.reconciledCleanups.getFirst();
        assertEquals(lease.liveNpcUuid(), cleanup.targetNpcUuid());
        assertEquals(lease.worldKey(), cleanup.worldKey());
        assertEquals(lease.leaseToken(), cleanup.leaseToken());

        world.projections.add(projection(
                lease.liveNpcUuid(), lease.profileId(), lease.leaseToken()
        ));
        assertEquals(BondedCompanionProjectionCleanupService.Outcome.REMOVED,
                new BondedCompanionProjectionCleanupService(world).recover(cleanup));
        assertEquals(List.of(lease.liveNpcUuid()), world.removed);
    }

    @Test
    void maintenanceLeavesPendingSummonLeaseForItsSpawnPath() {
        var pending = new BondedCompanionProjectionValidator.LeaseExpectation(
                uuid(1), "roster-a", "profile-pending", "lease-pending",
                uuid(40), "world-a", -1_000L, 0L,
                BondedCompanionProjectionValidator.LeasePhase.PENDING
        );
        durability.activate(pending);
        BondedCompanionProjectionRecoverySystem recovery =
                new BondedCompanionProjectionRecoverySystem(
                        observer(), ignored -> List.of(pending), 8
                );

        int reconciled = recovery.tick(-500L);

        assertEquals(0, reconciled);
        assertEquals(BondedCompanionState.ACTIVE,
                durability.states.get("profile-pending"));
        assertTrue(world.removed.isEmpty());
    }

    @Test
    void maintenanceStoresAndRemovesEveryExactMarkerDuplicate() {
        var lease = lease("profile-a", "lease-a", uuid(40));
        durability.activate(lease);
        world.projections.add(projection(uuid(40), "profile-a", "lease-a"));
        world.projections.add(projection(uuid(41), "profile-a", "lease-a"));
        world.projections.add(projection(uuid(42), "profile-a", "other-lease"));
        BondedCompanionProjectionRecoverySystem recovery =
                new BondedCompanionProjectionRecoverySystem(
                        observer(), ignored -> List.of(lease), 8
                );

        int reconciled = recovery.tick(-500L);

        assertEquals(1, reconciled);
        assertEquals(BondedCompanionState.STORED,
                durability.states.get("profile-a"));
        assertFalse(durability.states.containsValue(BondedCompanionState.DEAD));
        assertEquals(List.of(uuid(40), uuid(41)), world.removed);
        assertFalse(world.removed.contains(uuid(42)));
    }

    private BondedCompanionWorldLifecycleObserver observer() {
        BondedCompanionProjectionCleanupService cleanup =
                new BondedCompanionProjectionCleanupService(world);
        BondedCompanionProjectionService projections =
                new BondedCompanionProjectionService(
                        durability, durability, world, cleanup,
                        () -> "lease-new", () -> uuid(90)
                );
        return new BondedCompanionWorldLifecycleObserver(projections, world);
    }

    private BondedCompanionProjectionValidator.LeaseExpectation lease(
            String profileId, String leaseToken, UUID npcUuid
    ) {
        return new BondedCompanionProjectionValidator.LeaseExpectation(
                uuid(1), "roster-a", profileId, leaseToken, npcUuid,
                "world-a", -1_000L, 0L,
                BondedCompanionProjectionValidator.LeasePhase.LIVE
        );
    }

    private BondedCompanionProjectionValidator.Projection projection(
            UUID npcUuid, String profileId, String leaseToken
    ) {
        return new BondedCompanionProjectionValidator.Projection(
                npcUuid, "world-a",
                TameworkProjectionIdentityComponent.bondedCompanion(
                        profileId, leaseToken
                ),
                null
        );
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
