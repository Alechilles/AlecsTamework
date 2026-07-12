package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionPopulationCoveragePublisherTest {
    @Test
    void initializeStagesBothOwnerDimensionsBeforeCatalogCoverage() {
        List<CompanionPopulationCoverageRecord> writes = new ArrayList<>();
        CompanionPopulationCoveragePublisher publisher =
                new CompanionPopulationCoveragePublisher(catalog(), coverage -> {
                    writes.add(coverage);
                    return CompletableFuture.completedFuture(true);
                });

        assertTrue(publisher.initializeAsync().join());
        assertEquals(
                List.of(
                        CompanionPopulationCoverageRecord.Dimension.GLOBAL_OWNER,
                        CompanionPopulationCoverageRecord.Dimension.PER_WORLD_OWNER
                ),
                writes.subList(0, 2).stream()
                        .map(CompanionPopulationCoverageRecord::dimension)
                        .toList()
        );
        assertTrue(writes.subList(0, 2).stream().allMatch(coverage ->
                coverage.state() == CompanionPopulationCoverageRecord.State.RECONCILING));
    }

    @Test
    void ownerStagingFailurePreventsCatalogScanPublication() {
        List<CompanionPopulationCoverageRecord.Dimension> attempted = new ArrayList<>();
        CompanionPopulationCoveragePublisher publisher =
                new CompanionPopulationCoveragePublisher(catalog(), coverage -> {
                    attempted.add(coverage.dimension());
                    return CompletableFuture.completedFuture(false);
                });

        assertFalse(publisher.initializeAsync().join());
        assertEquals(
                List.of(
                        CompanionPopulationCoverageRecord.Dimension.GLOBAL_OWNER,
                        CompanionPopulationCoverageRecord.Dimension.PER_WORLD_OWNER
                ),
                attempted
        );
    }

    @Test
    void publishBothRequiresGlobalAndPerWorldWrites() {
        assertOwnerWriteFailure(false, true, false);
        assertOwnerWriteFailure(true, false, false);
    }

    @Test
    void publishMergedRequiresGlobalAndPerWorldWrites() {
        assertOwnerWriteFailure(false, true, true);
        assertOwnerWriteFailure(true, false, true);
    }

    private static void assertOwnerWriteFailure(
            boolean globalResult,
            boolean perWorldResult,
            boolean merged
    ) {
        List<CompanionPopulationCoverageRecord.Dimension> attempted = new ArrayList<>();
        CompanionPopulationCoveragePublisher publisher =
                new CompanionPopulationCoveragePublisher(catalog(), coverage -> {
                    attempted.add(coverage.dimension());
                    boolean result = coverage.dimension()
                            == CompanionPopulationCoverageRecord.Dimension.GLOBAL_OWNER
                            ? globalResult : perWorldResult;
                    return CompletableFuture.completedFuture(result);
                });

        boolean published = merged
                ? publisher.publishMergedAsync(
                        CompanionPopulationCoverageRecord.State.READY, 1, null
                ).join()
                : publisher.publishBothAsync(
                        CompanionPopulationCoverageRecord.State.DEGRADED,
                        "test",
                        1,
                        0
                ).join();

        assertFalse(published);
        assertEquals(
                List.of(
                        CompanionPopulationCoverageRecord.Dimension.GLOBAL_OWNER,
                        CompanionPopulationCoverageRecord.Dimension.PER_WORLD_OWNER
                ),
                attempted
        );
    }

    private static CompanionPopulationReconciliationCatalog catalog() {
        return new CompanionPopulationReconciliationCatalog(
                List.of(),
                true,
                true,
                true,
                true,
                new CustomContainerReconciliationRegistry.Snapshot(
                        List.of(), true, "sealed", "generation"
                )
        );
    }
}
