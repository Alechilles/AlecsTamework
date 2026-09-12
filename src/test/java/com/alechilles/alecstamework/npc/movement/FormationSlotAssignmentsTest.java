package com.alechilles.alecstamework.npc.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.UUID;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class FormationSlotAssignmentsTest {
    private static final long START = 10_000L;

    @Test
    void crossedFollowersTradeSlotsWhenItAvoidsAFullFormationCrossing() {
        FormationSlotAssignments assignments = new FormationSlotAssignments();
        UUID leftFollower = UUID.randomUUID();
        UUID rightFollower = UUID.randomUUID();
        assignments.claim(leftFollower, 0, START);
        assignments.claim(rightFollower, 1, START);
        assignments.report(leftFollower, new Vector3d(-10, 0, 0), new Vector3d(10, 0, 0), 4, START);
        assignments.report(rightFollower, new Vector3d(10, 0, 0), new Vector3d(-10, 0, 0), 4, START);

        assertEquals(1, assignments.rebalance(START));
        assertEquals(1, assignments.slot(leftFollower));
        assertEquals(0, assignments.slot(rightFollower));
    }

    @Test
    void claimsKeepEveryFollowerInAUniqueSlot() {
        FormationSlotAssignments assignments = new FormationSlotAssignments();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        assertEquals(2, assignments.claim(first, 2, START));
        assertEquals(0, assignments.claim(second, 2, START));
        assertEquals(1, assignments.claim(third, 0, START));
        assertEquals(2, assignments.claim(first, 0, START + 1));
        assertNotEquals(assignments.slot(first), assignments.slot(second));
        assertNotEquals(assignments.slot(first), assignments.slot(third));
        assertNotEquals(assignments.slot(second), assignments.slot(third));
    }

    @Test
    void smallSavingDoesNotCauseRepeatedSlotShuffling() {
        FormationSlotAssignments assignments = new FormationSlotAssignments();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assignments.claim(first, 0, START);
        assignments.claim(second, 1, START);
        assignments.report(first, new Vector3d(0, 0, 0), new Vector3d(0.3, 0, 0), 4, START);
        assignments.report(second, new Vector3d(0.3, 0, 0), new Vector3d(0, 0, 0), 4, START);

        assignments.rebalance(START);
        assertEquals(0, assignments.slot(first));
        assertEquals(1, assignments.slot(second));

        assignments.report(first, new Vector3d(10, 0, 0), new Vector3d(0, 0, 0), 4, START + 600);
        assignments.report(second, new Vector3d(0, 0, 0), new Vector3d(10, 0, 0), 4, START + 600);
        assignments.rebalance(START + 600);
        assertEquals(1, assignments.slot(first));
        assertEquals(0, assignments.slot(second));

        assignments.report(first, new Vector3d(0, 0, 0), new Vector3d(10, 0, 0), 4, START + 1_200);
        assignments.report(second, new Vector3d(10, 0, 0), new Vector3d(0, 0, 0), 4, START + 1_200);
        assignments.rebalance(START + 1_200);
        assertEquals(1, assignments.slot(first), "The swap cooldown prevents an immediate reversal.");
        assertEquals(0, assignments.slot(second));
    }

    @Test
    void expiredReservationsReleaseTheirSlotForANewFollower() {
        FormationSlotAssignments assignments = new FormationSlotAssignments();
        UUID departed = UUID.randomUUID();
        UUID newcomer = UUID.randomUUID();
        assignments.claim(departed, 0, START);

        assignments.rebalance(START);
        assignments.rebalance(START + 2_001);
        assertEquals(0, assignments.size());
        assertEquals(0, assignments.claim(newcomer, 0, START + 2_001));
    }

    @Test
    void rebalanceUsesCadenceAndBoundsThePairWork() {
        FormationSlotAssignments assignments = new FormationSlotAssignments();
        for (int index = 0; index < 40; index++) {
            UUID id = new UUID(0, index + 1L);
            assignments.claim(id, index, START);
            assignments.report(id, new Vector3d(index, 0, 0), new Vector3d(-index, 0, 0), 4, START);
        }

        assertEquals(128, assignments.rebalance(START));
        assertEquals(0, assignments.rebalance(START + 499));
        assertEquals(128, assignments.rebalance(START + 500));
    }

    @Test
    void departingFrontMembersDoNotLeaveSurvivorsInDistantSlots() {
        var assignments = new FormationSlotAssignments();
        UUID survivor = new UUID(0, 20);
        for (int i = 1; i <= 20; i++) assignments.claim(new UUID(0, i), i - 1, START);
        assignments.claim(survivor, 0, START + 1900);
        assignments.rebalance(START + 2001);
        assertEquals(1, assignments.size());
        assertEquals(0, assignments.slot(survivor));
    }

    @Test
    void incompatibleLayoutsKeepUniqueSlotsWithoutTradingTargets() {
        var assignments = new FormationSlotAssignments();
        UUID first = new UUID(0, 1), second = new UUID(0, 2);
        assignments.claim(first, 0, START);
        assignments.claim(second, 0, START);
        assignments.report(first, new Vector3d(-10, 0, 0), new Vector3d(10, 0, 0), 4, "Chevron", START);
        assignments.report(second, new Vector3d(10, 0, 0), new Vector3d(-10, 0, 0), 4, "Loose", START);
        assignments.rebalance(START);
        assertEquals(0, assignments.slot(first));
        assertEquals(1, assignments.slot(second));
    }
}
