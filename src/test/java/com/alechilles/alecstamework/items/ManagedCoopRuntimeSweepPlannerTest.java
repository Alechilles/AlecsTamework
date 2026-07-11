package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for the branch/cadence behavior extracted from the legacy coop runtime. */
class ManagedCoopRuntimeSweepPlannerTest {

    @Test
    void enclosedCoopSelectsNearestAdmissibleCandidateAndConsumesItOnce() throws Exception {
        FakeOccupancy occupancy = new FakeOccupancy();
        ManagedCoopRuntimeSweepPlanner planner = new ManagedCoopRuntimeSweepPlanner(occupancy);
        ManagedCoopContext first = context(0, 2, 6, 18, true, false, 10.0, new String[] {"hen"});
        ManagedCoopContext second = context(20, 2, 6, 18, true, false, 30.0, new String[] {"hen"});
        ManagedCoopCaptureCandidate rejectedNearest = candidate(1, "hen", 1.0, false);
        ManagedCoopCaptureCandidate selected = candidate(2, "HEN", 2.0, true);
        occupancy.captureAdmission.put(rejectedNearest.npcUuid(), false);

        ManagedCoopRuntimeSweepPlanner.SweepPlan plan = planner.plan(
                List.of(first, second), List.of(rejectedNearest, selected), 22, 1_000L, true);

        assertEquals(ManagedCoopRuntimeSweepPlanner.Branch.CAPTURE, plan.coops().get(0).branch());
        assertEquals(selected.npcUuid(), plan.coops().get(0).candidate().npcUuid());
        assertEquals(ManagedCoopRuntimeSweepPlanner.Branch.NONE, plan.coops().get(1).branch());
        assertTrue(plan.coops().stream().allMatch(ManagedCoopRuntimeSweepPlanner.CoopPlan::syncInteractionState));
    }

    @Test
    void roamTransitionTriggersProduceOnceAndReleaseUsesCommittedResident() throws Exception {
        FakeOccupancy occupancy = new FakeOccupancy();
        ManagedCoopContext context = context(0, 2, 6, 18, true, false, 10.0, new String[0]);
        ResidentRecord housed = resident(context);
        occupancy.housed.put(context.coopKey(), housed);
        ManagedCoopRuntimeSweepPlanner planner = new ManagedCoopRuntimeSweepPlanner(occupancy);

        var first = planner.plan(List.of(context), List.of(), 6, 1_000L, true).coops().getFirst();
        var throttled = planner.plan(List.of(context), List.of(), 7, 1_100L, true).coops().getFirst();
        var next = planner.plan(List.of(context), List.of(), 7, 1_400L, true).coops().getFirst();
        var enclosed = planner.plan(List.of(context), List.of(), 18, 2_000L, true).coops().getFirst();

        assertEquals(ManagedCoopRuntimeSweepPlanner.Branch.RELEASE, first.branch());
        assertEquals(housed, first.resident());
        assertTrue(first.produce());
        assertEquals(ManagedCoopRuntimeSweepPlanner.Branch.NONE, throttled.branch());
        assertFalse(throttled.produce());
        assertEquals(ManagedCoopRuntimeSweepPlanner.Branch.RELEASE, next.branch());
        assertFalse(next.produce());
        assertEquals(ManagedCoopRuntimeSweepPlanner.Branch.NONE, enclosed.branch());
        assertNull(enclosed.resident());
    }

    @Test
    void wrappedRoamWindowAndRemovedCheckFailClosedOnUnreliableScan() throws Exception {
        FakeOccupancy occupancy = new FakeOccupancy();
        ManagedCoopContext context = context(0, 1, 18, 6, true, false, 10.0, new String[0]);
        occupancy.housed.put(context.coopKey(), resident(context));
        ManagedCoopRuntimeSweepPlanner planner = new ManagedCoopRuntimeSweepPlanner(occupancy);

        var evening = planner.plan(List.of(context), List.of(), 22, 0L, true);
        var daytime = planner.plan(List.of(context), List.of(), 12, 400L, true);
        var unreliable = planner.plan(List.of(), List.of(), 12, 5_000L, false);
        var reliable = planner.plan(List.of(), List.of(), 12, 5_000L, true);

        assertEquals(ManagedCoopRuntimeSweepPlanner.Branch.RELEASE, evening.coops().getFirst().branch());
        assertTrue(evening.coops().getFirst().produce());
        assertEquals(ManagedCoopRuntimeSweepPlanner.Branch.NONE, daytime.coops().getFirst().branch());
        assertFalse(unreliable.checkRemovedCoops());
        assertTrue(reliable.checkRemovedCoops());
        assertTrue(reliable.activeCoopKeys().isEmpty());
    }

    @Test
    void activeLifecycleBlocksReleaseCaptureAndTransitionProduce() throws Exception {
        FakeOccupancy occupancy = new FakeOccupancy();
        ManagedCoopContext context = context(0, 2, 6, 18, true, false, 10.0,
                new String[] {"hen"});
        occupancy.housed.put(context.coopKey(), resident(context));
        ManagedCoopRuntimeSweepPlanner planner =
                new ManagedCoopRuntimeSweepPlanner(occupancy, ignored -> false);

        assertFalse(planner.needsCaptureCandidates(List.of(context), 22, 1_000L));
        var roaming = planner.plan(
                List.of(context), List.of(), 6, 1_000L, true).coops().getFirst();
        var enclosed = planner.plan(
                List.of(context), List.of(candidate(7, "hen", 1.0, true)),
                22, 2_000L, true).coops().getFirst();

        assertEquals(ManagedCoopRuntimeSweepPlanner.Branch.NONE, roaming.branch());
        assertFalse(roaming.produce());
        assertEquals(ManagedCoopRuntimeSweepPlanner.Branch.NONE, enclosed.branch());
    }

    private static ManagedCoopCaptureCandidate candidate(long id,
                                                          String role,
                                                          double x,
                                                          boolean tamed) {
        return new ManagedCoopCaptureCandidate(
                uuid(id), role, x, 2.5, 0.5,
                null, null, new String[0], null, tamed);
    }

    private static ManagedCoopContext context(int x,
                                              int maxResidents,
                                              int roamStart,
                                              int roamEnd,
                                              boolean capture,
                                              boolean requireTamed,
                                              double radius,
                                              String[] roles) throws Exception {
        var constructor = TwCoopConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwCoopConfig config = constructor.newInstance();
        set(config, "id", "test-" + x);
        set(config, "enabled", true);
        set(config, "coopId", "coop_chicken");
        set(config.getIdentityRules(), "preserveUUID", false);
        set(config.getCapturePolicy(), "requireTamed", requireTamed);
        set(config.getLifecycleRules(), "maxResidents", maxResidents);
        set(config.getLifecycleRules(), "residentRoamStartHour", roamStart);
        set(config.getLifecycleRules(), "residentRoamEndHour", roamEnd);
        set(config.getLifecycleRules(), "captureWildNPCsInRange", capture);
        set(config.getLifecycleRules(), "wildCaptureRadius", radius);
        set(config.getLifecycleRules(), "acceptedRoleIds", roles);
        return new ManagedCoopContext(
                new ManagedCoopAuthorityKey("world", x, 2, 0),
                "coop_chicken", 0, config, null);
    }

    private static ResidentRecord resident(ManagedCoopContext context) {
        UUID source = uuid(99);
        return new ResidentRecord(
                "resident", context.authorityKey(), context.coopId(), 0,
                "profile", "hen", source, source, null,
                "{}", "a".repeat(64), 1, ResidentState.HOUSED, 1L, true,
                1L, 0L, 1L, 1L);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class FakeOccupancy
            implements ManagedCoopRuntimeSweepPlanner.OccupancyGateway {
        private final Map<UUID, Boolean> captureAdmission = new HashMap<>();
        private final Map<String, ResidentRecord> housed = new HashMap<>();

        @Override
        public boolean permitsCapture(ManagedCoopContext context,
                                      ManagedCoopCaptureCandidate candidate) {
            return captureAdmission.getOrDefault(candidate.npcUuid(), true);
        }

        @Override
        public ResidentRecord firstHoused(ManagedCoopContext context) {
            return housed.get(context.coopKey());
        }
    }
}
