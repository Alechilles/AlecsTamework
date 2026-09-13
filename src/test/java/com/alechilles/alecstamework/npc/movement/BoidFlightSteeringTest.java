package com.alechilles.alecstamework.npc.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class BoidFlightSteeringTest {
    private static final double EPSILON = 1.0E-9;
    private static final double SPACING = 3.0;
    private static final double MAXIMUM_SPEED = 8.0;

    @Test
    void nearbyNeighborProducesSeparationAwayFromIt() {
        BoidFlightSteering steering = new BoidFlightSteering();
        Vector3d self = new Vector3d();
        steering.reset();
        steering.addNeighbor(self, new Vector3d(1.0, 0.0, 0.0), new Vector3d(), SPACING);

        Vector3d result = steering.resolve(self, new Vector3d(), new Vector3d(), SPACING,
                1.0, MAXIMUM_SPEED, 1.0, new Vector3d());

        assertTrue(result.x < 0.0, "A neighbor inside the separation spacing must push the bird away.");
    }

    @Test
    void shallowSeparationCorrectionIsWeakerThanADeepOverlap() {
        Vector3d self = new Vector3d();
        BoidFlightSteering deep = new BoidFlightSteering();
        deep.reset();
        deep.addNeighbor(self, new Vector3d(0.5, 0.0, 0.0), new Vector3d(), SPACING);
        Vector3d deepResult = deep.resolve(self, new Vector3d(), new Vector3d(), SPACING,
                1.0, MAXIMUM_SPEED, 1.0, new Vector3d());

        BoidFlightSteering shallow = new BoidFlightSteering();
        shallow.reset();
        shallow.addNeighbor(self, new Vector3d(2.9, 0.0, 0.0), new Vector3d(), SPACING);
        Vector3d shallowResult = shallow.resolve(self, new Vector3d(), new Vector3d(), SPACING,
                1.0, MAXIMUM_SPEED, 1.0, new Vector3d());

        assertTrue(Math.abs(deepResult.x) > Math.abs(shallowResult.x) * 4.0,
                "Separation must fade as a neighbor approaches the spacing threshold.");
    }

    @Test
    void alignedNeighborsContributeTheirSharedTravelDirection() {
        BoidFlightSteering steering = new BoidFlightSteering();
        Vector3d self = new Vector3d();
        steering.reset();
        steering.addNeighbor(self, new Vector3d(6.0, 0.0, 0.0), new Vector3d(0.0, 0.0, 6.0), SPACING);
        steering.addNeighbor(self, new Vector3d(-6.0, 0.0, 0.0), new Vector3d(0.0, 0.0, 6.0), SPACING);

        Vector3d result = steering.resolve(self, new Vector3d(), new Vector3d(), SPACING,
                1.0, MAXIMUM_SPEED, 1.0, new Vector3d());

        assertTrue(result.z > 0.0, "Local velocity alignment must carry the group forward.");
        assertTrue(Math.abs(result.z) > Math.abs(result.x) + EPSILON);
    }

    @Test
    void distantLocalNeighborsPullTheBirdTowardTheirCenter() {
        BoidFlightSteering steering = new BoidFlightSteering();
        Vector3d self = new Vector3d();
        steering.reset();
        steering.addNeighbor(self, new Vector3d(6.0, 0.0, 0.0), new Vector3d(), SPACING);
        steering.addNeighbor(self, new Vector3d(6.0, 0.0, 3.0), new Vector3d(), SPACING);

        Vector3d result = steering.resolve(self, new Vector3d(), new Vector3d(), SPACING,
                1.0, MAXIMUM_SPEED, 1.0, new Vector3d());

        assertTrue(result.x > 0.0, "The local center of mass should draw an isolated bird back toward its neighbors.");
    }

    @Test
    void isolatedBirdKeepsLeaderCruiseVelocity() {
        BoidFlightSteering steering = new BoidFlightSteering();
        Vector3d result = steering.resolve(new Vector3d(), new Vector3d(), new Vector3d(0.0, 0.0, 4.0), SPACING,
                1.0, MAXIMUM_SPEED, 1.0, new Vector3d());

        assertEquals(0.0, result.x, EPSILON);
        assertEquals(0.0, result.y, EPSILON);
        assertEquals(0.5, result.z, EPSILON);
    }

    @Test
    void overlapAndInvalidInputsStayFiniteAndWithinTheSpeedBound() {
        BoidFlightSteering steering = new BoidFlightSteering();
        Vector3d self = new Vector3d();
        steering.reset();
        steering.addNeighbor(self, new Vector3d(self), new Vector3d(Double.NaN, 0.0, 0.0), SPACING);
        steering.addNeighbor(self, new Vector3d(Double.NaN, 0.0, 0.0), new Vector3d(), SPACING);

        Vector3d result = steering.resolve(self, new Vector3d(Double.POSITIVE_INFINITY, 0.0, 0.0),
                new Vector3d(), SPACING, 1.0, MAXIMUM_SPEED, 1.0, new Vector3d());

        assertTrue(Double.isFinite(result.x) && Double.isFinite(result.y) && Double.isFinite(result.z));
        assertTrue(result.length() <= 1.0 + EPSILON);
        assertTrue(result.x > 0.0, "Exact overlap uses a deterministic fallback separation direction.");
    }

    @Test
    void exactOverlapUsesOppositeCallerSuppliedDirectionsForEachPairMember() {
        Vector3d self = new Vector3d();
        BoidFlightSteering first = new BoidFlightSteering();
        first.reset();
        first.addNeighbor(self, new Vector3d(self), new Vector3d(), SPACING, 1.0);
        Vector3d firstResult = first.resolve(self, new Vector3d(), new Vector3d(), SPACING,
                1.0, MAXIMUM_SPEED, 1.0, new Vector3d());

        BoidFlightSteering second = new BoidFlightSteering();
        second.reset();
        second.addNeighbor(self, new Vector3d(self), new Vector3d(), SPACING, -1.0);
        Vector3d secondResult = second.resolve(self, new Vector3d(), new Vector3d(), SPACING,
                1.0, MAXIMUM_SPEED, 1.0, new Vector3d());

        assertTrue(firstResult.x > 0.0);
        assertTrue(secondResult.x < 0.0,
                "Overlapped pair members need opposite directions to separate rather than translate together.");
    }
}
