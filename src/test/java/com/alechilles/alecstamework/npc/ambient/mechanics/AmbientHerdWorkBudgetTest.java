package com.alechilles.alecstamework.npc.ambient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.UUID;

/** Catches a shared-cap regression where one busy world could exceed the process allowance. */
class AmbientHerdWorkBudgetTest {
    @Test
    void worldsSharePointReadAllowanceAndEpochDoesNotBankCredits() {
        AmbientHerdWorkBudget budget = new AmbientHerdWorkBudget();
        long now = 1_000L;
        for (int world = 0; world < 4; world++) {
            assertTrue(
                    budget.claim(
                            UUID.randomUUID(), AmbientHerdWorkBudget.Work.POINT_READ, 32, now));
        }
        assertFalse(budget.claim(UUID.randomUUID(), AmbientHerdWorkBudget.Work.POINT_READ, 1, now));
        assertTrue(
                budget.claim(
                        UUID.randomUUID(),
                        AmbientHerdWorkBudget.Work.POINT_READ,
                        32,
                        now + 50_000L));
        assertFalse(
                budget.claim(
                        UUID.randomUUID(),
                        AmbientHerdWorkBudget.Work.POINT_READ,
                        33,
                        now + 50_000L));
    }

    @Test
    void idempotentReleaseReturnsActivityCapacityToTheNextHerd() {
        AmbientHerdWorkBudget budget = new AmbientHerdWorkBudget();
        UUID world = UUID.randomUUID();
        UUID activity = UUID.randomUUID();
        assertTrue(budget.tryAdmitActivity(world, activity, 16));
        budget.releaseActivity(world, activity);
        budget.releaseActivity(world, activity);
        for (int i = 0; i < 4; i++)
            assertTrue(budget.tryAdmitActivity(world, UUID.randomUUID(), 16));
    }

    @Test
    void staleClockFromAnotherWorldCannotResetTheSharedWindow() {
        AmbientHerdWorkBudget budget = new AmbientHerdWorkBudget();
        UUID first = UUID.randomUUID();
        assertTrue(budget.claim(first, AmbientHerdWorkBudget.Work.POINT_READ, 32, -50L));
        assertTrue(
                budget.claim(UUID.randomUUID(), AmbientHerdWorkBudget.Work.POINT_READ, 32, 100L));
        assertTrue(
                budget.claim(UUID.randomUUID(), AmbientHerdWorkBudget.Work.POINT_READ, 32, 100L));
        assertTrue(
                budget.claim(UUID.randomUUID(), AmbientHerdWorkBudget.Work.POINT_READ, 32, 100L));
        assertTrue(
                budget.claim(UUID.randomUUID(), AmbientHerdWorkBudget.Work.POINT_READ, 32, 100L));
        assertFalse(budget.claim(UUID.randomUUID(), AmbientHerdWorkBudget.Work.POINT_READ, 1, 0L));
    }
}
