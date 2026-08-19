package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NeedsWaterTargetSearchServiceTest {
    @Test
    void returnsAllValidSourcesFromNearestRingInDeterministicOrder() {
        FakeWaterAccess access = new FakeWaterAccess();
        access.addFluid(1, 64, 0);
        access.addFluid(0, 64, 1);

        NeedsResourceCandidates.Snapshot snapshot = new NeedsWaterTargetSearchService().search(
                new NeedsWaterTargetSearchService.WaterRequest(
                        0.2,
                        64.2,
                        0.2,
                        4.0,
                        1,
                        1.0,
                        16
                ),
                access
        );

        assertEquals(
                java.util.List.of(
                        new NeedsResourceCandidates.Candidate(0, 64, 1, 1.0),
                        new NeedsResourceCandidates.Candidate(1, 64, 0, 1.0)
                ),
                snapshot.candidates()
        );
        assertTrue(snapshot.foundSource());
        assertFalse(snapshot.sourceInConsumeRange());
    }

    @Test
    void capsCandidatesAndKeepsCoordinatesUnique() {
        FakeWaterAccess access = new FakeWaterAccess();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                if (Math.max(Math.abs(x), Math.abs(z)) == 3) {
                    access.addFluid(x, 64, z);
                }
            }
        }

        NeedsResourceCandidates.Snapshot snapshot = new NeedsWaterTargetSearchService().search(
                new NeedsWaterTargetSearchService.WaterRequest(
                        0.5,
                        64.0,
                        0.5,
                        4.0,
                        0,
                        0.0,
                        16
                ),
                access
        );

        assertEquals(16, snapshot.candidates().size());
        assertEquals(16, new HashSet<>(snapshot.candidates()).size());
        assertEquals(
                new NeedsResourceCandidates.Candidate(-3, 64, 0, 1.0),
                snapshot.candidates().get(0)
        );
    }

    @Test
    void emptyFluidSectionSkipsEveryPerCellFluidRead() {
        FakeWaterAccess access = new FakeWaterAccess();
        access.emptySection = true;

        NeedsResourceCandidates.Snapshot snapshot = new NeedsWaterTargetSearchService().search(
                new NeedsWaterTargetSearchService.WaterRequest(0.5, 64.0, 0.5, 2.0, 0, 1.0, 16),
                access
        );

        assertTrue(snapshot.candidates().isEmpty());
        assertEquals(0, access.fluidReads);
        assertTrue(access.sectionReads > 0);
    }

    @Test
    void emptyFluidSectionStillChecksTroughSources() {
        FakeWaterAccess access = new FakeWaterAccess();
        access.emptySection = true;
        access.sourceCoordinates.add("0:64:0");

        NeedsResourceCandidates.Snapshot snapshot = new NeedsWaterTargetSearchService().search(
                new NeedsWaterTargetSearchService.WaterRequest(0.5, 64.0, 0.5, 1.0, 0, 1.0, 16),
                access
        );

        assertEquals(
                java.util.List.of(new NeedsResourceCandidates.Candidate(0, 64, 0, 1.0)),
                snapshot.candidates()
        );
        assertEquals(0, access.fluidReads);
    }

    @Test
    void compatibilityFilterContinuesToAnAcceptedOuterRing() {
        FakeWaterAccess access = new FakeWaterAccess();
        access.addFluid(1, 64, 0);
        access.addFluid(2, 64, 0);

        NeedsResourceCandidates.Snapshot snapshot = new NeedsWaterTargetSearchService().search(
                new NeedsWaterTargetSearchService.WaterRequest(0.5, 64.0, 0.5, 4.0, 0, 1.0, 16),
                access,
                (x, y, z) -> x != 1
        );

        assertEquals(
                java.util.List.of(new NeedsResourceCandidates.Candidate(2, 64, 0, 1.0)),
                snapshot.candidates()
        );
        assertTrue(snapshot.foundSource());
    }

    @Test
    void scannerUsesPublishedBlockCentersAtPositiveAndNegativeBoundaries() {
        FakeWaterAccess positiveBoundary = new FakeWaterAccess();
        positiveBoundary.addFluid(1, 64, 0);
        assertTrue(new NeedsWaterTargetSearchService().search(
                new NeedsWaterTargetSearchService.WaterRequest(0.01, 64.0, 0.5, 1.0, 0, 1.0, 16),
                positiveBoundary
        ).candidates().isEmpty());

        FakeWaterAccess positiveInside = new FakeWaterAccess();
        positiveInside.addFluid(1, 64, 0);
        assertEquals(1, new NeedsWaterTargetSearchService().search(
                new NeedsWaterTargetSearchService.WaterRequest(0.51, 64.0, 0.5, 1.0, 0, 1.0, 16),
                positiveInside
        ).candidates().size());

        FakeWaterAccess negativeBoundary = new FakeWaterAccess();
        negativeBoundary.addFluid(0, 64, 0);
        assertTrue(new NeedsWaterTargetSearchService().search(
                new NeedsWaterTargetSearchService.WaterRequest(-0.99, 64.0, 0.5, 1.0, 0, 1.0, 16),
                negativeBoundary
        ).candidates().isEmpty());

        FakeWaterAccess negativeInside = new FakeWaterAccess();
        negativeInside.addFluid(0, 64, 0);
        assertEquals(1, new NeedsWaterTargetSearchService().search(
                new NeedsWaterTargetSearchService.WaterRequest(-0.49, 64.0, 0.5, 1.0, 0, 1.0, 16),
                negativeInside
        ).candidates().size());
    }

    @Test
    void zeroCandidateSearchStopsAtFirstFoundSource() {
        FakeWaterAccess access = new FakeWaterAccess();
        access.addFluid(0, 64, 0);

        NeedsResourceCandidates.Snapshot snapshot = new NeedsWaterTargetSearchService().search(
                new NeedsWaterTargetSearchService.WaterRequest(0.5, 64.0, 0.5, 1.0, 0, 1.0, 0),
                access
        );

        assertTrue(snapshot.foundSource());
        assertTrue(snapshot.candidates().isEmpty());
        assertEquals(1, access.sectionReads);
        assertEquals(1, access.fluidReads);
    }

    @Test
    void malformedWaterBoundsAreClampedOrRejectedBeforeTraversal() {
        FakeWaterAccess bounded = new FakeWaterAccess();
        NeedsResourceCandidates.Snapshot boundedSnapshot = new NeedsWaterTargetSearchService().search(
                new NeedsWaterTargetSearchService.WaterRequest(
                        0.5,
                        64.0,
                        0.5,
                        1_000_000_000.0,
                        Integer.MAX_VALUE,
                        1_000_000_000.0,
                        0
                ),
                bounded
        );
        assertTrue(boundedSnapshot.candidates().isEmpty());
        assertTrue(bounded.sectionReads < 100_000);

        FakeWaterAccess extremeOrigin = new FakeWaterAccess();
        NeedsResourceCandidates.Snapshot invalidSnapshot = new NeedsWaterTargetSearchService().search(
                new NeedsWaterTargetSearchService.WaterRequest(
                        Double.MAX_VALUE,
                        64.0,
                        0.5,
                        1.0,
                        0,
                        1.0,
                        16
                ),
                extremeOrigin
        );
        assertTrue(invalidSnapshot.candidates().isEmpty());
        assertEquals(0, extremeOrigin.sectionReads);
    }

    @Test
    void upperIntegerEdgesAreRejectedBeforeWaterTraversal() {
        assertFalse(new NeedsWaterTargetSearchService.WaterRequest(
                Integer.MAX_VALUE - 1.0 + 0.5, 64.0, 0.5, 1.0, 0, 1.0, 16
        ).hasValidRange());
        assertFalse(new NeedsWaterTargetSearchService.WaterRequest(
                0.5, 64.0, Integer.MAX_VALUE - 1.0 + 0.5, 1.0, 0, 1.0, 16
        ).hasValidRange());
        assertFalse(new NeedsWaterTargetSearchService.WaterRequest(
                0.5, Integer.MAX_VALUE - 8.0 + 0.5, 0.5, 1.0, 8, 1.0, 16
        ).hasValidRange());
    }

    @Test
    void lowerIntegerEdgesRemainValidAndTraverseBoundedly() {
        assertLowerEdgeTraverses(new NeedsWaterTargetSearchService.WaterRequest(
                Integer.MIN_VALUE + 1.0 + 0.5, 64.0, 0.5, 1.0, 0, 1.0, 16));
        assertLowerEdgeTraverses(new NeedsWaterTargetSearchService.WaterRequest(
                0.5, 64.0, Integer.MIN_VALUE + 1.0 + 0.5, 1.0, 0, 1.0, 16));
        assertLowerEdgeTraverses(new NeedsWaterTargetSearchService.WaterRequest(
                0.5, Integer.MIN_VALUE + 8.0 + 0.5, 0.5, 1.0, 8, 1.0, 16));
    }

    private static void assertLowerEdgeTraverses(NeedsWaterTargetSearchService.WaterRequest request) {
        assertTrue(request.hasValidRange());
        FakeWaterAccess access = new FakeWaterAccess();
        access.maxAccessCalls = 256;
        new NeedsWaterTargetSearchService().search(request, access);
        assertTrue(access.accessCalls > 0);
        assertTrue(access.accessCalls < access.maxAccessCalls);
    }

    private static final class FakeWaterAccess implements NeedsWaterTargetSearchService.WaterSearchAccess {
        private final Map<String, Integer> fluidIds = new HashMap<>();
        private final Set<String> sourceCoordinates = new HashSet<>();
        private boolean emptySection;
        private int fluidReads;
        private int sectionReads;
        private int accessCalls;
        private int maxAccessCalls = Integer.MAX_VALUE;

        private void addFluid(int x, int y, int z) {
            fluidIds.put(key(x, y, z), 1);
        }

        @Override
        public NeedsWaterTargetSearchService.FluidSectionView sectionAt(int x, int y, int z) {
            checkAccess();
            sectionReads++;
            return new FakeSection(emptySection);
        }

        @Override
        public boolean isEmpty(NeedsWaterTargetSearchService.FluidSectionView section) {
            return ((FakeSection) section).empty;
        }

        @Override
        public int fluidId(NeedsWaterTargetSearchService.FluidSectionView section, int x, int y, int z) {
            checkAccess();
            fluidReads++;
            return fluidIds.getOrDefault(key(x, y, z), 0);
        }

        @Override
        public boolean hasConsumableTrough(int x, int y, int z) {
            checkAccess();
            return sourceCoordinates.contains(key(x, y, z));
        }

        private void checkAccess() {
            if (++accessCalls > maxAccessCalls) {
                throw new AssertionError("search wrapped an integer coordinate");
            }
        }

        private static String key(int x, int y, int z) {
            return x + ":" + y + ":" + z;
        }

        private record FakeSection(boolean empty) implements NeedsWaterTargetSearchService.FluidSectionView {
        }
    }
}
