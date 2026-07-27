package com.alechilles.alecstamework.companion.bonded;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract coverage for finite startup recovery of interrupted bonded summons. */
class BondedCompanionStartupPendingRecoveryTest {
    private RecordingBondedDurability durability;
    private RecordingBondedWorld world;

    @BeforeEach
    void setUp() {
        durability = new RecordingBondedDurability();
        world = new RecordingBondedWorld();
    }

    @Test
    void paginatesPastTheFormer256LimitAndExcludesPostStartupLeases() {
        List<BondedCompanionProjectionValidator.LeaseExpectation> leases =
                new ArrayList<>();
        for (int index = 0; index < 300; index++) {
            var pending = pending("profile-%03d".formatted(index), -1_000L, index);
            leases.add(pending);
            durability.activate(pending);
        }
        var postStartup = pending("profile-after-startup", 1L, 400);
        leases.add(postStartup);
        durability.activate(postStartup);
        BondedCompanionStartupPendingRecovery recovery = new
                BondedCompanionStartupPendingRecovery(
                observer(), (highWater, after, limit) -> leases.stream()
                        .filter(lease -> lease.liveNpcUuid().getLeastSignificantBits()
                                <= highWater)
                        .filter(lease -> after == null
                                || lease.profileId().compareTo(after) > 0)
                        .sorted(Comparator.comparing(
                                BondedCompanionProjectionValidator.LeaseExpectation::profileId))
                        .limit(limit)
                        .toList(), 309L, 64
        );

        assertEquals(64, recovery.tick(-500L));
        assertEquals(64, recovery.tick(-400L));
        assertEquals(64, recovery.tick(-300L));
        assertEquals(64, recovery.tick(-200L));
        assertEquals(44, recovery.tick(-100L));
        assertEquals(0, recovery.tick(0L));

        for (int index = 0; index < 300; index++) {
            assertEquals(BondedCompanionState.STORED,
                    durability.states.get("profile-%03d".formatted(index)));
        }
        assertEquals(BondedCompanionState.ACTIVE,
                durability.states.get("profile-after-startup"));
    }

    @Test
    void settlesPendingLeaseWhenItsExpectedWorldIsUnavailable() {
        var pending = pending("profile-unavailable", -1_000L, 1);
        durability.activate(pending);
        BondedCompanionStartupPendingRecovery recovery = new
                BondedCompanionStartupPendingRecovery(
                observer(), (highWater, after, limit) -> List.of(pending),
                11L, 64
        );

        assertEquals(1, recovery.tick(-500L));

        assertEquals(BondedCompanionState.STORED,
                durability.states.get("profile-unavailable"));
        assertEquals("SPAWN_INTERRUPTED", durability.lastReason);
    }

    @Test
    void sameTimestampPostStartupLeaseIsExcludedByTheHighWaterMark() {
        var preStartup = pending("profile-before", 0L, 1);
        var postStartup = pending("profile-after", 0L, 2);
        durability.activate(preStartup);
        durability.activate(postStartup);
        BondedCompanionStartupPendingRecovery recovery = new
                BondedCompanionStartupPendingRecovery(
                observer(), (highWater, after, limit) -> List.of(
                        preStartup, postStartup
                ).stream().filter(lease -> lease.liveNpcUuid().getLeastSignificantBits()
                                <= highWater)
                        .toList(), 11L, 64
        );

        assertEquals(1, recovery.tick(-500L));

        assertEquals(BondedCompanionState.STORED,
                durability.states.get("profile-before"));
        assertEquals(BondedCompanionState.ACTIVE,
                durability.states.get("profile-after"));
    }

    @Test
    void persistentFirstPageFailureDoesNotStarveLaterStartupPages() {
        var failing = pending("profile-a", -1_000L, 1);
        var succeeding = pending("profile-b", -1_000L, 2);
        durability.activate(failing);
        durability.activate(succeeding);
        List<String> cursors = new ArrayList<>();
        BondedCompanionStartupPendingRecovery recovery = new
                BondedCompanionStartupPendingRecovery(
                observer(), (highWater, after, limit) -> {
                    cursors.add(after);
                    return List.of(failing, succeeding).stream()
                            .filter(lease -> lease.liveNpcUuid()
                                    .getLeastSignificantBits() <= highWater)
                            .filter(lease -> after == null
                                    || lease.profileId().compareTo(after) > 0)
                            .limit(limit).toList();
                }, 12L, 1
        );
        durability.reconcileSucceeds = false;

        assertEquals(0, recovery.tick(-500L));
        assertEquals(BondedCompanionState.ACTIVE,
                durability.states.get("profile-a"));
        assertEquals(BondedCompanionState.ACTIVE,
                durability.states.get("profile-b"));

        durability.reconcileSucceeds = true;
        assertEquals(1, recovery.tick(-400L));
        assertEquals(0, recovery.tick(-300L));
        assertEquals(1, recovery.tick(-200L));
        assertEquals(0, recovery.tick(-100L));

        assertEquals(java.util.Arrays.asList(null, "profile-a", "profile-b"), cursors);
        assertEquals(BondedCompanionState.STORED,
                durability.states.get("profile-a"));
        assertEquals(BondedCompanionState.STORED,
                durability.states.get("profile-b"));
        assertTrue(durability.spawnRecovery.isEmpty());
    }

    @Test
    void retriesAnUnavailablePendingLeaseReadWithoutAdvancingOrExhaustingTheCursor() {
        var pending = pending("profile-read-retry", -1_000L, 3);
        durability.activate(pending);
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        BondedCompanionStartupPendingRecovery recovery = new
                BondedCompanionStartupPendingRecovery(
                observer(), (highWater, after, limit) -> {
                    if (reads.incrementAndGet() == 1) {
                        throw new BondedCompanionLeaseEvidenceUnavailableException(
                                "sqlite_read", new java.sql.SQLException("unavailable"));
                    }
                    return List.of(pending);
                }, 13L, 64
        );

        assertEquals(0, recovery.tick(-500L));
        assertEquals(BondedCompanionState.ACTIVE,
                durability.states.get("profile-read-retry"));
        assertEquals(1, recovery.tick(-400L));
        assertEquals(2, reads.get());
        assertEquals(BondedCompanionState.STORED,
                durability.states.get("profile-read-retry"));
    }

    @Test
    void retriesDeferredPagesWithoutDiscardingEntriesBeyondTheFirstPage() {
        var first = pending("profile-deferred-a", -1_000L, 4);
        var second = pending("profile-deferred-b", -1_000L, 5);
        durability.activate(first);
        durability.activate(second);
        BondedCompanionStartupPendingRecovery recovery = new
                BondedCompanionStartupPendingRecovery(
                observer(), (highWater, after, limit) -> List.of(first, second).stream()
                        .filter(lease -> after == null
                                || lease.profileId().compareTo(after) > 0)
                        .limit(limit).toList(), 14L, 1
        );
        durability.reconcileSucceeds = false;

        assertEquals(0, recovery.tick(-500L));
        assertEquals(0, recovery.tick(-400L));
        assertEquals(0, recovery.tick(-300L));

        durability.reconcileSucceeds = true;
        assertEquals(1, recovery.tick(-200L));
        assertEquals(BondedCompanionState.ACTIVE,
                durability.states.get("profile-deferred-b"));
        assertEquals(1, recovery.tick(-100L));
        assertEquals(BondedCompanionState.STORED,
                durability.states.get("profile-deferred-a"));
        assertEquals(BondedCompanionState.STORED,
                durability.states.get("profile-deferred-b"));
    }

    @Test
    void doesNotMaskAnInvariantViolationAsUnavailableLeaseEvidence() {
        BondedCompanionStartupPendingRecovery recovery = new
                BondedCompanionStartupPendingRecovery(
                observer(), (highWater, after, limit) -> {
                    throw new IllegalArgumentException("invalid_pending_cursor");
                }, 15L, 1
        );

        assertThrows(IllegalArgumentException.class, () -> recovery.tick(-500L));
    }

    private BondedCompanionWorldLifecycleObserver observer() {
        var cleanup = new BondedCompanionProjectionCleanupService(world);
        var projections = new BondedCompanionProjectionService(
                durability, durability, world, cleanup,
                () -> "lease-new", () -> new UUID(0L, 900L)
        );
        return new BondedCompanionWorldLifecycleObserver(projections, world);
    }

    private static BondedCompanionProjectionValidator.LeaseExpectation pending(
            String profileId, long startedAtMs, long uuid
    ) {
        return new BondedCompanionProjectionValidator.LeaseExpectation(
                new UUID(0L, 1L), "roster-a", profileId, "lease-" + profileId,
                new UUID(0L, uuid + 10L), "unavailable-world", startedAtMs, 0L,
                BondedCompanionProjectionValidator.LeasePhase.PENDING
        );
    }
}
