package com.alechilles.alecstamework.integration.claims;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClaimAdmissionMetricsTest {
    @Test
    void targetedRefreshTimingIsDistinctFromSnapshotTimingAndClampsNegativeDurations() {
        ClaimAdmissionMetrics metrics = new ClaimAdmissionMetrics();
        ClaimOccupancyIndex occupancyIndex = new ClaimOccupancyIndex();

        metrics.recordTargetedRefreshDuration(12L);
        metrics.recordTargetedRefreshDuration(-7L);
        ClaimAdmissionMetrics.Snapshot snapshot = metrics.snapshot(occupancyIndex, 0, 0L);

        assertEquals(0L, snapshot.snapshotCount());
        assertEquals(2L, snapshot.targetedRefreshCount());
        assertEquals(12L, snapshot.totalTargetedRefreshNanos());
        assertEquals(0L, snapshot.lastTargetedRefreshNanos());
    }
}
