package com.alechilles.alecstamework.companion.lifecycle;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exhaustive contract tests for the canonical state/location vocabulary. */
class LifecycleVocabularyTest {
    @Test
    void everyStateHasExactlyOneLocationKind() {
        Map<LifecycleState, LifecycleLocation> valid = Map.ofEntries(
                Map.entry(LifecycleState.ACTIVE, LifecycleLocation.liveEntity("entity-a", "world-a")),
                Map.entry(LifecycleState.UNLOADED, LifecycleLocation.none()),
                Map.entry(LifecycleState.CAPTURED,
                        LifecycleLocation.keyed(LifecycleLocationKind.CAPTURE_ITEM, "claim-a")),
                Map.entry(LifecycleState.COOP,
                        LifecycleLocation.keyed(LifecycleLocationKind.COOP_SLOT, "coop-a:0")),
                Map.entry(LifecycleState.DEAD_REVIVABLE, LifecycleLocation.none()),
                Map.entry(LifecycleState.LOST, LifecycleLocation.none()),
                Map.entry(LifecycleState.RELEASED, LifecycleLocation.none()),
                Map.entry(LifecycleState.UNRESOLVED, LifecycleLocation.unresolved())
        );

        assertEquals(LifecycleState.values().length, valid.size());
        valid.forEach((state, location) -> assertEquals(location, state.requireCompatible(location)));

        for (LifecycleState state : LifecycleState.values()) {
            for (LifecycleLocation location : valid.values()) {
                if (location.kind() != state.requiredLocation()) {
                    assertThrows(IllegalArgumentException.class, () -> state.requireCompatible(location));
                }
            }
        }
    }

    @Test
    void locationShapeIsValidatedBeforeStorage() {
        assertThrows(IllegalArgumentException.class,
                () -> new LifecycleLocation(LifecycleLocationKind.LIVE_ENTITY, "entity-a", null));
        assertThrows(IllegalArgumentException.class,
                () -> new LifecycleLocation(LifecycleLocationKind.COOP_SLOT, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new LifecycleLocation(LifecycleLocationKind.CAPTURE_ITEM, "claim-a", "world-a"));
        assertThrows(IllegalArgumentException.class,
                () -> new LifecycleLocation(LifecycleLocationKind.NONE, "unexpected", null));
        assertThrows(IllegalArgumentException.class,
                () -> LifecycleLocation.keyed(LifecycleLocationKind.NONE, "unexpected"));
    }

    @Test
    void revisionZeroIsValidAndOverflowCannotWrap() {
        assertEquals(0, LifecycleRevision.INITIAL.value());
        assertEquals(1, LifecycleRevision.INITIAL.next().value());
        assertThrows(IllegalArgumentException.class, () -> new LifecycleRevision(-1));
        assertThrows(IllegalStateException.class, () -> new LifecycleRevision(Long.MAX_VALUE).next());
    }

    @Test
    void ownerWorldIsCanonicalAndIndependentOfPhysicalLocation() {
        CompanionLifecycle dormant = new CompanionLifecycle(
                ProfileId.parse("10000000-0000-0000-0000-000000000001"),
                OwnerId.parse("20000000-0000-0000-0000-000000000001"),
                LifecycleState.CAPTURED,
                LifecycleLocation.keyed(
                        LifecycleLocationKind.CAPTURE_ITEM,
                        "capture-a"
                ),
                LifecycleRevision.INITIAL,
                null,
                -1,
                ReconciliationGeneration.INITIAL,
                null,
                " world-a "
        );

        assertEquals("world-a", dormant.ownerWorldKey());
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompanionLifecycle(
                        dormant.profileId(),
                        null,
                        dormant.state(),
                        dormant.location(),
                        dormant.revision(),
                        null,
                        dormant.stateChangedAtMs(),
                        dormant.lastReconciledGeneration(),
                        null,
                        "world-a"
                )
        );
    }
}
