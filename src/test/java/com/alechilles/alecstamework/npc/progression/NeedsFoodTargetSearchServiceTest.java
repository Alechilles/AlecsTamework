package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NeedsFoodTargetSearchServiceTest {
    @Test
    void returnsAllAllowedFoodSourcesFromNearestRingInDeterministicOrder() {
        FakeFoodAccess access = new FakeFoodAccess();
        access.addFood(1, 64, 0);
        access.addFood(0, 64, 1);

        NeedsResourceCandidates.Snapshot snapshot = new NeedsFoodTargetSearchService().search(
                new NeedsFoodTargetSearchService.FoodRequest(
                        0.2,
                        64.2,
                        0.2,
                        4.0,
                        1,
                        2.0,
                        16,
                        List.of("Tw_Berry", "tw_meat")
                ),
                access
        );

        assertEquals(
                List.of(
                        new NeedsResourceCandidates.Candidate(0, 64, 1, 2.0),
                        new NeedsResourceCandidates.Candidate(1, 64, 0, 2.0)
                ),
                snapshot.candidates()
        );
        assertTrue(snapshot.foundSource());
        assertTrue(snapshot.sourceInConsumeRange());
        assertEquals(List.of("tw_berry", "tw_meat"), access.allowedIdsSeen);
    }

    @Test
    void doesNotReturnSourcesOutsideTheFirstUsableHorizontalRing() {
        FakeFoodAccess access = new FakeFoodAccess();
        access.addFood(2, 64, 0);
        access.addFood(0, 64, 3);

        NeedsResourceCandidates.Snapshot snapshot = new NeedsFoodTargetSearchService().search(
                new NeedsFoodTargetSearchService.FoodRequest(
                        0.5,
                        64.0,
                        0.5,
                        4.0,
                        0,
                        2.0,
                        16,
                        List.of("tw_berry")
                ),
                access
        );

        assertEquals(
                List.of(new NeedsResourceCandidates.Candidate(2, 64, 0, 2.0)),
                snapshot.candidates()
        );
    }

    private static final class FakeFoodAccess implements NeedsFoodTargetSearchService.FoodSearchAccess {
        private final Set<String> foodCoordinates = new HashSet<>();
        private List<String> allowedIdsSeen = List.of();

        private void addFood(int x, int y, int z) {
            foodCoordinates.add(key(x, y, z));
        }

        @Override
        public boolean hasAllowedFood(int x, int y, int z, List<String> allowedIds) {
            allowedIdsSeen = allowedIds;
            return foodCoordinates.contains(key(x, y, z));
        }

        private static String key(int x, int y, int z) {
            return x + ":" + y + ":" + z;
        }
    }
}
