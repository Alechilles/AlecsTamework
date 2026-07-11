package com.alechilles.alecstamework.npc.breeding;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Regression coverage for the one-roll, one-resolution immutable birth-plan contract. */
class BreedingBirthPlanServiceTest {
    @Test
    void samplesFertilityOnceAndResolvesEachChildOnce() {
        AtomicInteger rollCalls = new AtomicInteger();
        AtomicInteger childResolverCalls = new AtomicInteger();
        BreedingBirthPlanService service = new BreedingBirthPlanService(() -> {
            rollCalls.incrementAndGet();
            return 0.25;
        });

        BreedingBirthPlan plan = service.createPlan(1.5, 1.0, childIndex -> {
            childResolverCalls.incrementAndGet();
            return child(childIndex);
        });

        assertEquals(1, rollCalls.get());
        assertEquals(2, childResolverCalls.get());
        assertEquals(2, plan.rolledChildCount());
        assertEquals(0.25, plan.fertilitySnapshot().sampledRoll());
        assertEquals("baby-0", plan.children().get(0).roleId());
        assertEquals("baby-1", plan.children().get(1).roleId());

        plan.children();
        plan.fertilitySnapshot();
        assertEquals(1, rollCalls.get());
        assertEquals(2, childResolverCalls.get());
    }

    @Test
    void naturalZeroNeverInvokesChildResolver() {
        AtomicInteger childResolverCalls = new AtomicInteger();
        BreedingBirthPlan plan = new BreedingBirthPlanService(() -> 0.9).createPlan(
                0.4,
                1.0,
                childIndex -> {
                    childResolverCalls.incrementAndGet();
                    return child(childIndex);
                }
        );

        assertEquals(0, plan.rolledChildCount());
        assertEquals(0, childResolverCalls.get());
        assertEquals(0.4, plan.fertilitySnapshot().expectedOffspring());
    }

    @Test
    void expectedOffspringAndResolvedChildrenClampAtFour() {
        BreedingBirthPlan plan = new BreedingBirthPlanService(() -> 0.5).createPlan(
                3.0,
                3.0,
                BreedingBirthPlanServiceTest::child
        );

        assertEquals(4.0, plan.fertilitySnapshot().expectedOffspring());
        assertEquals(4, plan.rolledChildCount());
    }

    @Test
    void invalidInjectedRollFailsBeforeResolvingChildren() {
        AtomicInteger childResolverCalls = new AtomicInteger();
        BreedingBirthPlanService service = new BreedingBirthPlanService(() -> 1.0);

        assertThrows(IllegalStateException.class, () -> service.createPlan(
                1.0,
                1.0,
                index -> {
                    childResolverCalls.incrementAndGet();
                    return child(index);
                }
        ));
        assertEquals(0, childResolverCalls.get());
    }

    private static PlannedChild child(int index) {
        return new PlannedChild(
                "baby-" + index,
                "adult",
                index % 2 == 0 ? "Female" : "Male",
                "family",
                "cattle"
        );
    }
}
