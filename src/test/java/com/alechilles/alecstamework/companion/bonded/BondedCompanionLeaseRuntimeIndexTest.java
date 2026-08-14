package com.alechilles.alecstamework.companion.bonded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Protects the post-commit lease view used by world-thread warning checks. */
class BondedCompanionLeaseRuntimeIndexTest {
    @Test
    void activationAndExactTokenRemovalPreserveOtherLeases() {
        BondedCompanionLeaseRuntimeIndex index = new BondedCompanionLeaseRuntimeIndex();
        var first = lease("profile-a", "token-a", "world-a", 1L);
        var replacement = lease("profile-a", "token-b", "world-a", 2L);

        index.activate(first);
        index.activate(replacement);
        index.remove(first);

        assertEquals(List.of(replacement), index.snapshotWorld("world-a", 64));
        assertTrue(index.hasWorldActivity("world-a"));
    }

    @Test
    void worldReplacementIsAtomicOrderedAndImmutable() {
        BondedCompanionLeaseRuntimeIndex index = new BondedCompanionLeaseRuntimeIndex();
        var later = lease("profile-b", "token-b", "world-a", 2L);
        var earlier = lease("profile-a", "token-a", "world-a", 1L);
        var foreign = lease("profile-c", "token-c", "world-b", 3L);
        index.activate(lease("profile-old", "token-old", "world-a", 4L));

        index.replaceWorld("world-a", List.of(later, foreign, earlier));
        List<BondedCompanionProjectionValidator.LeaseExpectation> snapshot =
                index.snapshotWorld("world-a", 64);

        assertEquals(List.of(earlier, later), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(foreign));
        assertFalse(index.hasWorldActivity("world-b"));

        index.clearWorld("world-a");
        assertTrue(index.snapshotWorld("world-a", 64).isEmpty());
    }

    private static BondedCompanionProjectionValidator.LeaseExpectation lease(
            String profileId,
            String token,
            String worldKey,
            long value
    ) {
        return new BondedCompanionProjectionValidator.LeaseExpectation(
                new UUID(0L, 100L + value),
                "roster-a",
                profileId,
                token,
                new UUID(0L, value),
                worldKey,
                -1_000L,
                0L,
                BondedCompanionProjectionValidator.LeasePhase.LIVE
        );
    }
}
