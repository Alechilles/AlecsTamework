package com.alechilles.alecstamework.npc.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class CompanionFollowFormationTest {
    private static final double GAP = 0.75;

    @Test
    void flyingGroupIgnoresSmallOwnerStepsAndJumpsThenMovesTogether() {
        var group = new CompanionFollowFormation();
        group.configure(new Vector3d(10, 0, 0), 2, new double[] {.5, 2},
                new double[] {2.75, 2.75}, 32);
        Vector3d player = new Vector3d(0, 20, 0);
        Vector3d first = group.flyingTarget(player, 0, 5, new Vector3d());
        Vector3d second = group.flyingTarget(player, 1, 5, new Vector3d());
        assertEquals(first, group.flyingTarget(new Vector3d(-1, 21, 0), 0, 5, new Vector3d()));
        Vector3d movedFirst = group.flyingTarget(new Vector3d(-8, 23, 0), 0, 5, new Vector3d());
        Vector3d movedSecond = group.flyingTarget(new Vector3d(-8, 23, 0), 1, 5, new Vector3d());
        assertTrue(movedFirst.x < first.x - 2);
        assertEquals(28, movedFirst.y, 1e-9);
        assertEquals(new Vector3d(second).sub(first), new Vector3d(movedSecond).sub(movedFirst));
        assertTrue(movedFirst.distance(movedSecond) >= .5 + 2 + 2.75 - 1e-9);
    }

    @Test
    void equalCowsUseTheirPairClearanceInsteadOfTheOldSixBlockSpacing() {
        var group = new CompanionFollowFormation();
        group.configure(new Vector3d(), 2, new double[] {1, 1}, new double[] {GAP, GAP}, 40);

        Vector3d first = group.target(new Vector3d(), 0, new Vector3d());
        Vector3d second = group.target(new Vector3d(), 1, new Vector3d());

        assertEquals(2.75, first.distance(second), 0.000001);
    }

    @Test
    void bisonOnlyWidensTheSlotsBesideIt() {
        var group = new CompanionFollowFormation();
        group.configure(new Vector3d(), 4, new double[] {3, .25, .25, .25},
                new double[] {GAP, GAP, GAP, GAP}, 40);

        Vector3d firstChicken = group.target(new Vector3d(), 2, new Vector3d());
        Vector3d secondChicken = group.target(new Vector3d(), 3, new Vector3d());

        assertEquals(1.25, firstChicken.distance(secondChicken), 0.000001);
    }

    @Test
    void compactPackingKeepsEveryPairOutsideTheirCombinedBodiesAndGap() {
        double[] radii = {3, 1, .25, 1.5, .25, 1};
        double[] gaps = {.75, .6, .5, .75, .5, .6};
        var group = new CompanionFollowFormation();
        group.configure(new Vector3d(), radii.length, radii, gaps, 40);

        assertClearance(targets(group, radii.length, new Vector3d()), radii, gaps);
    }

    @Test
    void slotExchangeRebuildsClearanceForTheNewAnimalSizes() {
        double[] beforeRadii = {3, .25, .25, 1};
        double[] afterRadii = {.25, 3, .25, 1};
        double[] gaps = {GAP, GAP, GAP, GAP};
        var group = new CompanionFollowFormation();
        group.configure(new Vector3d(), beforeRadii.length, beforeRadii, gaps, 40);
        group.configure(new Vector3d(8, 0, 0), afterRadii.length, afterRadii, gaps, 40);

        var fresh = new CompanionFollowFormation();
        fresh.configure(new Vector3d(), afterRadii.length, afterRadii, gaps, 40);
        for (int repeat = 0; repeat < 4; repeat++) {
            group.configure(new Vector3d(), beforeRadii.length, beforeRadii, gaps, 40);
            group.configure(new Vector3d(), afterRadii.length, afterRadii, gaps, 40);
            Vector3d[] actual = targets(group, afterRadii.length, new Vector3d());
            assertClearance(actual, afterRadii, gaps);
            Vector3d[] expected = targets(fresh, afterRadii.length, new Vector3d());
            for (int slot = 0; slot < actual.length; slot++) assertEquals(expected[slot], actual[slot]);
        }
    }

    @Test
    void unchangedMembershipKeepsTheGroupCenterAndTargetsStable() {
        double[] radii = {1, 1, .5, .5};
        double[] gaps = {GAP, GAP, GAP, GAP};
        var group = new CompanionFollowFormation();
        group.configure(new Vector3d(10, 0, 0), radii.length, radii, gaps, 40);
        Vector3d before = group.target(new Vector3d(2, 0, 0), 0, new Vector3d());

        group.configure(new Vector3d(8, 0, 0), radii.length, radii, gaps, 40);

        assertEquals(before, group.target(new Vector3d(2, 0, 0), 0, new Vector3d()));
    }

    @Test
    void travellingAwayTranslatesWholeFormationWithoutChangingOffsets() {
        double[] radii = {1, 1, .5, .5};
        double[] gaps = {GAP, GAP, GAP, GAP};
        var group = new CompanionFollowFormation();
        group.configure(new Vector3d(10, 0, 0), radii.length, radii, gaps, 40);
        Vector3d a = group.target(new Vector3d(), 0, new Vector3d());
        Vector3d b = group.target(new Vector3d(), 3, new Vector3d());
        Vector3d movedA = group.target(new Vector3d(-10, 0, 0), 0, new Vector3d());
        Vector3d movedB = group.target(new Vector3d(-10, 0, 0), 3, new Vector3d());

        assertTrue(movedA.x < a.x - 5);
        assertEquals(new Vector3d(b).sub(a), new Vector3d(movedB).sub(movedA));
    }

    private static Vector3d[] targets(CompanionFollowFormation group, int count, Vector3d player) {
        Vector3d[] targets = new Vector3d[count];
        for (int slot = 0; slot < count; slot++) {
            targets[slot] = group.target(player, slot, new Vector3d());
        }
        return targets;
    }

    private static void assertClearance(Vector3d[] targets, double[] radii, double[] gaps) {
        for (int slot = 0; slot < targets.length; slot++) {
            for (int other = 0; other < slot; other++) {
                double required = radii[slot] + radii[other] + Math.max(gaps[slot], gaps[other]);
                assertTrue(targets[slot].distance(targets[other]) >= required - 0.000001,
                        "slots " + slot + " and " + other + " overlap");
            }
        }
    }
}
