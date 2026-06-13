package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class NeedsResourceStandTargetSelectorTest {

    @Test
    void diagonalAdjacentFoodCandidatesAreConsidered() {
        NeedsResourceStandTargetSelector selector = new NeedsResourceStandTargetSelector();
        Vector3d target = selector.findNearestProjectedTarget(
                0,
                0,
                0,
                new Vector3d(1.5, 0.0, 1.5),
                NeedsResourceStandTargetSelector.MIN_ADJACENT_DISTANCE,
                false,
                (candidate, minY, maxY) -> approximatelyEqual(candidate.x, 1.5)
                        && approximatelyEqual(candidate.z, 1.5)
                        && approximatelyEqual(candidate.y, 0.05)
        );

        assertNotNull(target);
        assertEquals(1.5, target.x, 0.000001);
        assertEquals(1.5, target.z, 0.000001);
        assertTrue(NeedsResourceStandTargetSelector.hasDiagonalAdjacentCandidate());
    }

    @Test
    void projectionSuccessReturnsSeekTarget() {
        NeedsResourceStandTargetSelector selector = new NeedsResourceStandTargetSelector();

        Vector3d target = selector.findNearestProjectedTarget(
                4,
                10,
                8,
                new Vector3d(5.5, 10.0, 8.5),
                1.0,
                false,
                (candidate, minY, maxY) -> {
                    candidate.y += 0.25;
                    return true;
                }
        );

        assertNotNull(target);
        assertEquals(10.30, target.y, 0.000001);
    }

    @Test
    void projectionFailureRejectsCandidate() {
        NeedsResourceStandTargetSelector selector = new NeedsResourceStandTargetSelector();

        Vector3d target = selector.findNearestProjectedTarget(
                0,
                0,
                0,
                new Vector3d(1.5, 0.0, 0.5),
                1.0,
                false,
                (candidate, minY, maxY) -> false
        );

        assertNull(target);
    }

    @Test
    void rejectedNearestProjectedCandidateFallsBackToNextBest() {
        NeedsResourceStandTargetSelector selector = new NeedsResourceStandTargetSelector();

        Vector3d target = selector.findNearestProjectedTarget(
                0,
                0,
                0,
                new Vector3d(1.5, 0.0, 0.5),
                1.0,
                false,
                (candidate, minY, maxY) -> true,
                candidate -> Math.floor(candidate.x) == 1.0 && Math.floor(candidate.z) == 0.0
        );

        assertNotNull(target);
        assertTrue(Math.floor(target.x) != 1.0 || Math.floor(target.z) != 0.0);
    }

    @Test
    void allRejectedProjectedCandidatesReturnNull() {
        NeedsResourceStandTargetSelector selector = new NeedsResourceStandTargetSelector();

        Vector3d target = selector.findNearestProjectedTarget(
                0,
                0,
                0,
                new Vector3d(1.5, 0.0, 0.5),
                1.0,
                false,
                (candidate, minY, maxY) -> true,
                candidate -> true
        );

        assertNull(target);
    }

    @Test
    void candidateCountRemainsBoundedBySelectorConstant() {
        NeedsResourceStandTargetSelector selector = new NeedsResourceStandTargetSelector();
        AtomicInteger checkedCandidates = new AtomicInteger();

        selector.findNearestProjectedTarget(
                0,
                0,
                0,
                new Vector3d(10.0, 0.0, 10.0),
                100.0,
                true,
                (candidate, minY, maxY) -> {
                    checkedCandidates.incrementAndGet();
                    return false;
                }
        );

        assertTrue(checkedCandidates.get() <= NeedsResourceStandTargetSelector.maxCandidateCount());
        assertEquals(39, NeedsResourceStandTargetSelector.maxCandidateCount());
    }

    @Test
    void cacheHitReturnsProjectedResultCopy() {
        Vector3d projectedTarget = new Vector3d(2.5, 4.05, 6.5);

        Vector3d cachedTarget = CompanionNeedsEnvironmentService.cacheSearchTargetRoundTripForTests(
                projectedTarget,
                1_000L
        );

        assertEquals(projectedTarget.x, cachedTarget.x, 0.000001);
        assertEquals(projectedTarget.y, cachedTarget.y, 0.000001);
        assertEquals(projectedTarget.z, cachedTarget.z, 0.000001);
        assertTrue(projectedTarget != cachedTarget);
    }

    @Test
    void waterStandTargetSelectionExcludesSourceBlockCenters() {
        assertTrue(!CompanionNeedsEnvironmentService.waterStandTargetsIncludeSourceBlockForTests());
    }

    private static boolean approximatelyEqual(double left, double right) {
        return Math.abs(left - right) < 0.000001;
    }
}
