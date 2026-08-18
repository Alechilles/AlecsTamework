package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(snapshot.sourceInConsumeRange());
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

    private static final class FakeWaterAccess implements NeedsWaterTargetSearchService.WaterSearchAccess {
        private final Map<String, Integer> fluidIds = new HashMap<>();
        private final Set<String> sourceCoordinates = new HashSet<>();
        private boolean emptySection;
        private int fluidReads;
        private int sectionReads;

        private void addFluid(int x, int y, int z) {
            fluidIds.put(key(x, y, z), 1);
        }

        @Override
        public NeedsWaterTargetSearchService.FluidSectionView sectionAt(int x, int y, int z) {
            sectionReads++;
            return new FakeSection(emptySection);
        }

        @Override
        public boolean isEmpty(NeedsWaterTargetSearchService.FluidSectionView section) {
            return ((FakeSection) section).empty;
        }

        @Override
        public int fluidId(NeedsWaterTargetSearchService.FluidSectionView section, int x, int y, int z) {
            fluidReads++;
            return fluidIds.getOrDefault(key(x, y, z), 0);
        }

        @Override
        public boolean hasConsumableTrough(int x, int y, int z) {
            return sourceCoordinates.contains(key(x, y, z));
        }

        private static String key(int x, int y, int z) {
            return x + ":" + y + ":" + z;
        }

        private record FakeSection(boolean empty) implements NeedsWaterTargetSearchService.FluidSectionView {
        }
    }
}
