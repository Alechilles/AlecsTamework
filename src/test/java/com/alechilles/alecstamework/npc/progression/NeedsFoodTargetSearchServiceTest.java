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
        assertEquals(Set.of("tw_berry", "tw_meat"), access.allowedIdsSeen);
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

    @Test
    void compatibilityFilterContinuesToAnAcceptedOuterRing() {
        FakeFoodAccess access = new FakeFoodAccess();
        access.addFood(1, 64, 0);
        access.addFood(2, 64, 0);

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
                access,
                (x, y, z) -> x != 1
        );

        assertEquals(
                List.of(new NeedsResourceCandidates.Candidate(2, 64, 0, 2.0)),
                snapshot.candidates()
        );
        assertTrue(snapshot.foundSource());
    }

    @Test
    void scannerUsesPublishedBlockCentersAtPositiveAndNegativeBoundaries() {
        FakeFoodAccess positiveBoundary = new FakeFoodAccess();
        positiveBoundary.addFood(1, 64, 0);
        assertTrue(new NeedsFoodTargetSearchService().search(
                new NeedsFoodTargetSearchService.FoodRequest(
                        0.01, 64.0, 0.5, 1.0, 0, 2.0, 16, List.of("tw_berry")),
                positiveBoundary
        ).candidates().isEmpty());

        FakeFoodAccess negativeInside = new FakeFoodAccess();
        negativeInside.addFood(0, 64, 0);
        assertEquals(1, new NeedsFoodTargetSearchService().search(
                new NeedsFoodTargetSearchService.FoodRequest(
                        -0.49, 64.0, 0.5, 1.0, 0, 2.0, 16, List.of("tw_berry")),
                negativeInside
        ).candidates().size());
    }

    @Test
    void zeroCandidateSearchStopsAtFirstFoundSource() {
        FakeFoodAccess access = new FakeFoodAccess();
        access.addFood(0, 64, 0);

        NeedsResourceCandidates.Snapshot snapshot = new NeedsFoodTargetSearchService().search(
                new NeedsFoodTargetSearchService.FoodRequest(
                        0.5, 64.0, 0.5, 1.0, 0, 2.0, 0, List.of("tw_berry")),
                access
        );

        assertTrue(snapshot.foundSource());
        assertTrue(snapshot.candidates().isEmpty());
        assertEquals(1, access.sourceChecks);
    }

    @Test
    void malformedFoodBoundsAreClampedOrRejectedBeforeTraversal() {
        FakeFoodAccess bounded = new FakeFoodAccess();
        NeedsResourceCandidates.Snapshot boundedSnapshot = new NeedsFoodTargetSearchService().search(
                new NeedsFoodTargetSearchService.FoodRequest(
                        0.5,
                        64.0,
                        0.5,
                        1_000_000_000.0,
                        Integer.MAX_VALUE,
                        1_000_000_000.0,
                        0,
                        List.of("tw_berry")
                ),
                bounded
        );
        assertTrue(boundedSnapshot.candidates().isEmpty());
        assertTrue(bounded.sourceChecks < 100_000);

        FakeFoodAccess extremeOrigin = new FakeFoodAccess();
        NeedsResourceCandidates.Snapshot invalidSnapshot = new NeedsFoodTargetSearchService().search(
                new NeedsFoodTargetSearchService.FoodRequest(
                        Double.MAX_VALUE, 64.0, 0.5, 1.0, 0, 2.0, 16, List.of("tw_berry")),
                extremeOrigin
        );
        assertTrue(invalidSnapshot.candidates().isEmpty());
        assertEquals(0, extremeOrigin.sourceChecks);
    }

    @Test
    void boundedFoodSearchDoesNotWrapAtEitherIntegerExtreme() {
        assertBounded(new NeedsFoodTargetSearchService.FoodRequest(
                Integer.MAX_VALUE - 1.0 + 0.5, 64.0, 0.5, 1.0, 0, 2.0, 16, List.of("tw_berry")));
        assertBounded(new NeedsFoodTargetSearchService.FoodRequest(
                Integer.MIN_VALUE + 1.0 + 0.5, 64.0, 0.5, 1.0, 0, 2.0, 16, List.of("tw_berry")));
        assertBounded(new NeedsFoodTargetSearchService.FoodRequest(
                0.5, 64.0, Integer.MAX_VALUE - 1.0 + 0.5, 1.0, 0, 2.0, 16, List.of("tw_berry")));
        assertBounded(new NeedsFoodTargetSearchService.FoodRequest(
                0.5, 64.0, Integer.MIN_VALUE + 1.0 + 0.5, 1.0, 0, 2.0, 16, List.of("tw_berry")));
        assertBounded(new NeedsFoodTargetSearchService.FoodRequest(
                0.5, Integer.MAX_VALUE - 8.0 + 0.5, 0.5, 1.0, 8, 2.0, 16, List.of("tw_berry")));
        assertBounded(new NeedsFoodTargetSearchService.FoodRequest(
                0.5, Integer.MIN_VALUE + 8.0 + 0.5, 0.5, 1.0, 8, 2.0, 16, List.of("tw_berry")));
    }

    private static void assertBounded(NeedsFoodTargetSearchService.FoodRequest request) {
        FakeFoodAccess access = new FakeFoodAccess();
        access.maxSourceChecks = 256;
        NeedsResourceCandidates.Snapshot snapshot = new NeedsFoodTargetSearchService().search(request, access);

        assertTrue(snapshot.candidates().isEmpty());
        assertTrue(access.sourceChecks < access.maxSourceChecks);
    }

    private static final class FakeFoodAccess implements NeedsFoodTargetSearchService.FoodSearchAccess {
        private final Set<String> foodCoordinates = new HashSet<>();
        private Set<String> allowedIdsSeen = Set.of();
        private int sourceChecks;
        private int maxSourceChecks = Integer.MAX_VALUE;

        private void addFood(int x, int y, int z) {
            foodCoordinates.add(key(x, y, z));
        }

        @Override
        public boolean hasAllowedFood(int x, int y, int z, Set<String> allowedIds) {
            if (++sourceChecks > maxSourceChecks) {
                throw new AssertionError("search wrapped an integer coordinate");
            }
            allowedIdsSeen = allowedIds;
            return foodCoordinates.contains(key(x, y, z)) && allowedIds.contains("tw_berry");
        }

        private static String key(int x, int y, int z) {
            return x + ":" + y + ":" + z;
        }
    }
}
