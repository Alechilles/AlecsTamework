package com.alechilles.alecstamework.npc.ambient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

/** Verifies the observable target sequence produced by one admitted herd activity. */
final class AmbientHerdCoordinatorTest {
    @Test
    void holdsNormalBehaviorUntilACompletedRouteThenPublishesJourneyTargetsInOrder() {
        UUID leader = UUID.randomUUID();
        UUID follower = UUID.randomUUID();
        AmbientHerdCoordinator.Activity activity = activity(leader, follower, 1_000L);
        AmbientHerdPoint origin = new AmbientHerdPoint(10.0, 64.0, 10.0);
        AmbientHerdPoint firstTurn = new AmbientHerdPoint(14.0, 64.0, 10.0);
        AmbientHerdPoint bank = new AmbientHerdPoint(18.0, 64.0, 12.0);
        AmbientHerdPoint water = new AmbientHerdPoint(18.0, 63.0, 12.0);
        AmbientHerdWaterBankSearch.BankPatch patch =
                new AmbientHerdWaterBankSearch.BankPatch(
                        water,
                        List.of(bank, new AmbientHerdPoint(18.0, 64.0, 15.0)),
                        List.of(
                                new AmbientHerdPoint(15.0, 64.0, 12.0),
                                new AmbientHerdPoint(15.0, 64.0, 15.0)));

        assertFalse(activity.accepts(AmbientHerdCoordinator.SensorPhase.READY));
        assertNull(activity.targetFor(leader));

        activity.startOutboundPlan(patch, 1_100L);
        activity.startGather(List.of(origin, firstTurn, bank), 1_200L);
        assertTrue(activity.accepts(AmbientHerdCoordinator.SensorPhase.READY));
        assertEquals(origin, activity.targetFor(follower));

        activity.startTravel(1_300L);
        assertTrue(activity.accepts(AmbientHerdCoordinator.SensorPhase.MOVE));
        assertNull(activity.targetFor(follower));
        activity.advanceWaypoint(1_350L);
        assertEquals(firstTurn, activity.targetFor(leader));
        activity.advanceWaypoint(1_400L);
        activity.advanceWaypoint(1_450L);
        assertNull(activity.targetFor(leader));

        activity.startApproach(1_500L);
        assertNull(activity.targetFor(leader));
        activity.approveApproach(leader);
        activity.approveApproach(follower);
        assertTrue(activity.accepts(AmbientHerdCoordinator.SensorPhase.MOVE));
        assertEquals(bank, activity.targetFor(leader));
        assertEquals(new AmbientHerdPoint(18.0, 64.0, 15.0), activity.targetFor(follower));
        activity.startDrink(1_550L);
        assertTrue(activity.accepts(AmbientHerdCoordinator.SensorPhase.DRINK));
        assertEquals(water, activity.targetFor(leader));
        activity.enterRest(1_600L);
        assertTrue(activity.accepts(AmbientHerdCoordinator.SensorPhase.REST));
        assertEquals(new AmbientHerdPoint(15.0, 64.0, 15.0), activity.targetFor(follower));
    }

    @Test
    void deadlinesRemainBoundedByTheSingleActivityDeadline() {
        UUID leader = UUID.randomUUID();
        AmbientHerdCoordinator.Activity activity = activity(leader, UUID.randomUUID(), 5_000L);
        AmbientHerdWaterBankSearch.BankPatch patch =
                new AmbientHerdWaterBankSearch.BankPatch(
                        new AmbientHerdPoint(4.0, 63.0, 4.0),
                        List.of(
                                new AmbientHerdPoint(4.0, 64.0, 5.0),
                                new AmbientHerdPoint(5.0, 64.0, 4.0)),
                        List.of(
                                new AmbientHerdPoint(2.0, 64.0, 4.0),
                                new AmbientHerdPoint(4.0, 64.0, 2.0)));

        activity.startOutboundPlan(patch, 6_000L);
        assertEquals(activity.discoveryDeadline, activity.phaseDeadline);
        activity.startGather(List.of(activity.origin), activity.totalDeadline - 1L);
        activity.startTravel(activity.totalDeadline - 1L);
        assertEquals(activity.totalDeadline, activity.phaseDeadline);
    }

    @Test
    void activeStaysTrueWhileTheReturnRouteIsBeingPlannedWithoutPublishingAnOldTarget() {
        UUID leader = UUID.randomUUID();
        AmbientHerdCoordinator.Activity activity = activity(leader, UUID.randomUUID(), 1_000L);
        AmbientHerdWaterBankSearch.BankPatch patch =
                new AmbientHerdWaterBankSearch.BankPatch(
                        new AmbientHerdPoint(4.0, 63.0, 4.0),
                        List.of(
                                new AmbientHerdPoint(4.0, 64.0, 5.0),
                                new AmbientHerdPoint(5.0, 64.0, 4.0)),
                        List.of(
                                new AmbientHerdPoint(2.0, 64.0, 4.0),
                                new AmbientHerdPoint(4.0, 64.0, 2.0)));

        activity.startOutboundPlan(patch, 1_010L);
        activity.startReturnPlan(new AmbientHerdPoint(4.0, 64.0, 5.0), 1_020L);

        assertTrue(activity.accepts(AmbientHerdCoordinator.SensorPhase.ACTIVE));
        assertNull(activity.targetFor(leader));
        assertFalse(activity.accepts(AmbientHerdCoordinator.SensorPhase.MOVE));
    }

    @Test
    void cohortUsesDistinctSlotsAndDoesNotExposeWaterToWaitingMembers() {
        UUID leader = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID waiting = UUID.randomUUID();
        AmbientHerdCoordinator.Activity activity =
                new AmbientHerdCoordinator.Activity(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        leader,
                        List.of(leader, second, waiting),
                        new AmbientHerdPoint(0.0, 64.0, 0.0),
                        1.0,
                        2.0,
                        0L);
        AmbientHerdWaterBankSearch.BankPatch patch =
                new AmbientHerdWaterBankSearch.BankPatch(
                        new AmbientHerdPoint(10.0, 63.0, 10.0),
                        List.of(
                                new AmbientHerdPoint(9.0, 64.0, 10.0),
                                new AmbientHerdPoint(11.0, 64.0, 10.0)),
                        List.of(
                                new AmbientHerdPoint(7.0, 64.0, 10.0),
                                new AmbientHerdPoint(13.0, 64.0, 10.0)));

        activity.startOutboundPlan(patch, 1L);
        activity.startApproach(2L);
        assertNull(activity.targetFor(leader));
        activity.approveApproach(leader);
        activity.approveApproach(second);
        activity.approveApproach(waiting);
        assertEquals(patch.slots().getFirst(), activity.targetFor(leader));
        assertEquals(patch.slots().get(1), activity.targetFor(second));
        assertEquals(patch.staging().getFirst(), activity.targetFor(waiting));
        activity.startDrink(3L);
        assertTrue(activity.isDrinking(leader));
        assertFalse(activity.isDrinking(waiting));
    }

    @Test
    void coordinatorCadenceAcceptsSignedMonotonicTimeWithoutCatchUp() {
        assertTrue(AmbientHerdCoordinator.isTickDue(Long.MIN_VALUE, -500L));
        assertFalse(AmbientHerdCoordinator.isTickDue(-500L, -251L));
        assertTrue(AmbientHerdCoordinator.isTickDue(-500L, -250L));
    }

    private static AmbientHerdCoordinator.Activity activity(UUID leader, UUID follower, long now) {
        return new AmbientHerdCoordinator.Activity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                leader,
                List.of(leader, follower),
                new AmbientHerdPoint(10.0, 64.0, 10.0),
                1.0,
                2.0,
                now);
    }
}
