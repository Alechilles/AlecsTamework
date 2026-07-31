package com.alechilles.alecstamework.npc.actions;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for flying NPCs touching terrain before Land reaches its positional goal. */
class LandingContactTransitionTest {
    @Test
    void groundedFlyControllerSwitchesToWalk() {
        AtomicInteger switchAttempts = new AtomicInteger();

        boolean switched = LandingContactTransition.confirm(
                "Fly",
                true,
                () -> {
                    switchAttempts.incrementAndGet();
                    return true;
                }
        );

        assertTrue(switched);
        assertEquals(1, switchAttempts.get());
    }

    @Test
    void airborneOrNonFlyingControllerCannotConfirmLanding() {
        AtomicInteger switchAttempts = new AtomicInteger();

        assertFalse(LandingContactTransition.confirm("Fly", false, () -> {
            switchAttempts.incrementAndGet();
            return true;
        }));
        assertFalse(LandingContactTransition.confirm("Walk", true, () -> {
            switchAttempts.incrementAndGet();
            return true;
        }));
        assertFalse(LandingContactTransition.confirm(null, true, () -> {
            switchAttempts.incrementAndGet();
            return true;
        }));
        assertEquals(0, switchAttempts.get());
    }

    @Test
    void failedControllerSwitchDoesNotReportLandingConfirmation() {
        assertFalse(LandingContactTransition.confirm("Fly", true, () -> false));
    }
}
