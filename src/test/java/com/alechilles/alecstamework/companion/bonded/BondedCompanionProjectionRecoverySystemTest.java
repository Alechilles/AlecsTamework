package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

    @Test
    void maintenanceDoesNotDemoteWhenExpectedWorldScanIsIncomplete() {
        var lease = lease("profile-incomplete", "lease-incomplete", uuid(50));
        durability.activate(lease);
        BondedCompanionProjectionRecoverySystem recovery = conclusiveRecovery(
                (ignored, maximum) -> List.of(lease),
                ignored -> new BondedCompanionProjectionRecoverySystem.ScanResult(
                        List.of(), List.of()
                ), 8
        );

        assertEquals(0, recovery.tick(-500L));

        assertEquals(BondedCompanionState.ACTIVE,
                durability.states.get("profile-incomplete"));
        assertTrue(durability.reconciledCleanups.isEmpty());
    }

    @Test
    void maintenanceReconcilesObservedWrongWorldMarkerAfterExpectedWorldCompletes() {
        var lease = lease("profile-wrong-world", "lease-wrong-world", uuid(51));
        durability.activate(lease);
        var wrongWorld = new BondedCompanionProjectionValidator.Projection(
                uuid(52), "world-b",
                TameworkProjectionIdentityComponent.bondedCompanion(
                        lease.profileId(), lease.leaseToken()
                ), null
        );
        world.projections.add(wrongWorld);
        BondedCompanionProjectionRecoverySystem recovery = conclusiveRecovery(
                (ignored, maximum) -> List.of(lease),
                ignored -> new BondedCompanionProjectionRecoverySystem.ScanResult(
                        List.of(wrongWorld), List.of(lease)
                ), 8
        );

        assertEquals(1, recovery.tick(-500L));

        assertEquals(BondedCompanionState.STORED,
                durability.states.get("profile-wrong-world"));
        assertEquals(List.of(uuid(52)), world.removed);
        assertEquals("world-b", durability.reconciledCleanups.getFirst().worldKey());
    }

    @Test
    void maintenanceRotatesPastAStableFirstLiveLeasePage() {
        var first = lease("profile-a", "lease-a", uuid(60));
        var second = lease("profile-b", "lease-b", uuid(61));
        durability.activate(first);
        durability.activate(second);
        ArrayList<String> cursors = new ArrayList<>();
        BondedCompanionProjectionRecoverySystem recovery = conclusiveRecovery(
                (after, ignored) -> {
                    cursors.add(after);
                    return after == null ? List.of(first) : List.of(second);
                },
                leases -> new BondedCompanionProjectionRecoverySystem.ScanResult(
                        List.of(), leases
                ), 1
        );

        assertEquals(1, recovery.tick(-500L));
        assertEquals(1, recovery.tick(-400L));

        assertEquals(java.util.Arrays.asList(null, "profile-a"), cursors);
        assertEquals(BondedCompanionState.STORED,
                durability.states.get("profile-a"));
        assertEquals(BondedCompanionState.STORED,
                durability.states.get("profile-b"));
    }

    @Test
    void failedAsyncScanDoesNotDemoteAndAllowsTheCursorToAdvance() {
        var first = lease("profile-a", "lease-a", uuid(70));
        var second = lease("profile-b", "lease-b", uuid(71));
        durability.activate(first);
        durability.activate(second);
        ArrayList<String> cursors = new ArrayList<>();
        BondedCompanionProjectionRecoverySystem recovery = new
                BondedCompanionProjectionRecoverySystem(
                observer(), (after, ignored) -> {
                    cursors.add(after);
                    return after == null ? List.of(first) : List.of(second);
                }, (batch, ignored) -> {
                    if (batch.equals(List.of(first))) {
                        CompletableFuture<BondedCompanionProjectionRecoverySystem.ScanResult>
                                failed = new CompletableFuture<>();
                        failed.completeExceptionally(new IllegalStateException("scan"));
                        return failed;
                    }
                    return CompletableFuture.completedFuture(
                            new BondedCompanionProjectionRecoverySystem.ScanResult(
                                    List.of(), batch
                            )
                    );
                }, 1, 16
        );

        assertEquals(0, recovery.tick(-500L));
        assertEquals(1, recovery.tick(-400L));

        assertEquals(java.util.Arrays.asList(null, "profile-a"), cursors);
        assertEquals(BondedCompanionState.ACTIVE, durability.states.get("profile-a"));
        assertEquals(BondedCompanionState.STORED, durability.states.get("profile-b"));
    }

    @Test
    void stalledAsyncScanNeverDemotesAndEventuallyReleasesTheCursor() {
        var first = lease("profile-a", "lease-a", uuid(72));
        var second = lease("profile-b", "lease-b", uuid(73));
        durability.activate(first);
        durability.activate(second);
        CompletableFuture<BondedCompanionProjectionRecoverySystem.ScanResult> stalled =
                new CompletableFuture<>();
        ArrayList<String> cursors = new ArrayList<>();
        BondedCompanionProjectionRecoverySystem recovery = new
                BondedCompanionProjectionRecoverySystem(
                observer(), (after, ignored) -> {
                    cursors.add(after);
                    return after == null ? List.of(first) : List.of(second);
                }, (batch, ignored) -> batch.equals(List.of(first))
                        ? stalled : CompletableFuture.completedFuture(
                                new BondedCompanionProjectionRecoverySystem.ScanResult(
                                        List.of(), batch
                                )
                        ), 1, 16
        );

        assertEquals(0, recovery.tick(-500L));
        assertEquals(0, recovery.tick(9_501L));
        assertEquals(1, recovery.tick(9_502L));

        assertEquals(java.util.Arrays.asList(null, "profile-a"), cursors);
        assertEquals(BondedCompanionState.ACTIVE, durability.states.get("profile-a"));
        assertEquals(BondedCompanionState.STORED, durability.states.get("profile-b"));
    }

    @Test
    void scanSourceThrowingOrReturningNullDoesNotDemote() {
        var lease = lease("profile-failed", "lease-failed", uuid(74));
        durability.activate(lease);
        BondedCompanionProjectionRecoverySystem throwing = new
                BondedCompanionProjectionRecoverySystem(
                observer(), (after, ignored) -> List.of(lease),
                (batch, ignored) -> {
                    throw new IllegalStateException("scan");
                }, 1, 16
        );
        BondedCompanionProjectionRecoverySystem nullStage = new
                BondedCompanionProjectionRecoverySystem(
                observer(), (after, ignored) -> List.of(lease),
                (batch, ignored) -> null, 1, 16
        );

        assertEquals(0, throwing.tick(-500L));
        assertEquals(0, nullStage.tick(-400L));
        assertEquals(BondedCompanionState.ACTIVE, durability.states.get("profile-failed"));
        assertTrue(durability.reconciledCleanups.isEmpty());
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

    private BondedCompanionProjectionRecoverySystem conclusiveRecovery(
            BondedCompanionProjectionRecoverySystem.PagedLeaseSource leases,
            java.util.function.Function<List<
                    BondedCompanionProjectionValidator.LeaseExpectation>,
                    BondedCompanionProjectionRecoverySystem.ScanResult> scans,
            int maximumLeases
    ) {
        return new BondedCompanionProjectionRecoverySystem(
                observer(), leases, (batch, ignored) -> java.util.concurrent
                        .CompletableFuture.completedFuture(scans.apply(batch)),
                maximumLeases, 16
        );
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
