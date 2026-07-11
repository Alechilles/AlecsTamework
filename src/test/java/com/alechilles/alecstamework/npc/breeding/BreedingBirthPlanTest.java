package com.alechilles.alecstamework.npc.breeding;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for immutable, pre-rolled birth plans. */
class BreedingBirthPlanTest {
    @Test
    void defensivelyCopiesResolvedChildrenAndPreservesOrder() {
        PlannedChild first = child("Baby_A", "Adult_A", "Female", "Family_A", "  CATTLE ");
        PlannedChild second = child("Baby_B", "Adult_B", "Male", "Family_B", "cattle");
        List<PlannedChild> source = new ArrayList<>(List.of(first, second));

        BreedingBirthPlan plan = BreedingBirthPlan.of(source);
        source.clear();

        assertEquals(2, plan.rolledChildCount());
        assertEquals(List.of(first, second), plan.children());
        assertEquals("cattle", plan.children().getFirst().populationType());
        assertThrows(UnsupportedOperationException.class, () -> plan.children().add(first));
    }

    @Test
    void rejectsAResolvedListThatDoesNotMatchFertilityResult() {
        PlannedChild child = child("Baby", "Adult", null, "Family", "cattle");

        assertThrows(IllegalArgumentException.class, () -> new BreedingBirthPlan(2, List.of(child)));
    }

    @Test
    void rejectsMoreThanCurrentFourChildFertilityCeiling() {
        PlannedChild child = child("Baby", "Adult", null, "Family", "cattle");

        assertThrows(IllegalArgumentException.class, () -> BreedingBirthPlan.of(List.of(
                child,
                child,
                child,
                child,
                child
        )));
    }

    @Test
    void emptyPlanRepresentsNaturalZeroChildRoll() {
        BreedingBirthPlan plan = BreedingBirthPlan.of(List.of());

        assertEquals(0, plan.rolledChildCount());
        assertEquals(List.of(), plan.children());
        assertTrue(plan.isNaturallyEmpty());
    }

    private static PlannedChild child(String role,
                                      String adultRole,
                                      String gender,
                                      String family,
                                      String populationType) {
        return new PlannedChild(role, adultRole, gender, family, populationType);
    }
}
