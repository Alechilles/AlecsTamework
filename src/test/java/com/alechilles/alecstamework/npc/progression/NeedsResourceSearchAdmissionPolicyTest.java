package com.alechilles.alecstamework.npc.progression;

import static com.alechilles.alecstamework.performance.RuntimePressureLevel.EMERGENCY;
import static com.alechilles.alecstamework.performance.RuntimePressureLevel.HOT;
import static com.alechilles.alecstamework.performance.RuntimePressureLevel.NORMAL;
import static com.alechilles.alecstamework.performance.RuntimePressureLevel.WARM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class NeedsResourceSearchAdmissionPolicyTest {
    private final NeedsResourceSearchAdmissionPolicy policy = new NeedsResourceSearchAdmissionPolicy();

    @Test
    void pressureLevelsReduceAdmissionRate() {
        assertTrue(policy.maySearch(NORMAL, 8L));
        assertTrue(policy.maySearch(WARM, 8L));
        assertFalse(policy.maySearch(WARM, 9L));
        assertTrue(policy.maySearch(HOT, 8L));
        assertFalse(policy.maySearch(HOT, 10L));
        assertTrue(policy.maySearch(EMERGENCY, 8L));
        assertFalse(policy.maySearch(EMERGENCY, 12L));
    }

    @Test
    void deferredTtlIsStableAndBounded() {
        long ttl = policy.deferredTtlMs(new UUID(1L, 2L));

        assertTrue(ttl >= 100L && ttl <= 300L);
        assertEquals(ttl, policy.deferredTtlMs(new UUID(1L, 2L)));
    }
}
