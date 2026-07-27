package com.alechilles.alecstamework.companion.bonded;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                observer(), (cutoff, after, limit) -> leases.stream()
                        .filter(lease -> lease.startedAtMs() <= cutoff)
                        .filter(lease -> after == null
                                || lease.profileId().compareTo(after) > 0)
                        .sorted(Comparator.comparing(
                                BondedCompanionProjectionValidator.LeaseExpectation::profileId))
                        .limit(limit)
                        .toList(), 0L, 64
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
                observer(), (cutoff, after, limit) -> List.of(pending), 0L, 64
        );

        assertEquals(1, recovery.tick(-500L));

        assertEquals(BondedCompanionState.STORED,
                durability.states.get("profile-unavailable"));
        assertEquals("SPAWN_INTERRUPTED", durability.lastReason);
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
