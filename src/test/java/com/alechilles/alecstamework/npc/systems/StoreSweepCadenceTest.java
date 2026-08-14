package com.alechilles.alecstamework.npc.systems;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for per-store tick sweep throttling. */
class StoreSweepCadenceTest {
    @Test
    void claimThrottlesEachStoreWithoutBlockingAnotherWorld() {
        AtomicLong clock = new AtomicLong(-100L);
        StoreSweepCadence cadence = new StoreSweepCadence(100L, clock::get);
        Object firstStore = new Object();
        Object secondStore = new Object();

        assertTrue(cadence.claim(firstStore));
        assertFalse(cadence.claim(firstStore));
        assertTrue(cadence.claim(secondStore));

        clock.set(-1L);
        assertFalse(cadence.claim(firstStore));
        clock.set(0L);
        assertTrue(cadence.claim(firstStore));
    }
}
