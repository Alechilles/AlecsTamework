package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Regression coverage for store-scoped companion needs runtime membership. */
class CompanionNeedsRuntimeRegistryTest {
    @Test
    void removalClearsScheduleAndSuppressionMembership() {
        CompanionNeedsRuntimeRegistry.WorldState state =
                CompanionNeedsRuntimeRegistry.newStateForTests();
        UUID npc = new UUID(10L, 20L);
        state.register(npc, 1_000L);
        state.setSuppressionActive(npc, true);

        state.remove(npc);

        assertNull(state.schedule().pollDue(Long.MAX_VALUE));
        assertFalse(state.membership().contains(npc));
        assertFalse(state.suppressionIds().contains(npc));
    }
}
