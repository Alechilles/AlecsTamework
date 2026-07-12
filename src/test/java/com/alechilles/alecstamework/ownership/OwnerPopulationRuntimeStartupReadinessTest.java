package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimOccupancyReadiness;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationScanSessionRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnerPopulationRuntimeStartupReadinessTest {
    @TempDir
    Path tempDir;

    @Test
    void priorProcessReadyRowsCannotOpenAdmissionsBeforeTheFreshScan() throws Exception {
        Path dataDirectory = tempDir.resolve("prior-ready");
        try (TameworkPersistenceRuntime prior =
                     TameworkPersistenceRuntime.initialize(dataDirectory, null)) {
            seedReadyCoverage(prior);
            CompanionPopulationScanSessionRepository sessions =
                    prior.getCompanionPopulationScanSessionRepository();
            PersistenceWriteQueue.WriteOutcome<CompanionPopulationScanSessionRepository.Session>
                    acquired = sessions.acquireOrResumeAsync().completion()
                    .get(5L, TimeUnit.SECONDS);
            assertTrue(acquired.isCommitted());
            assertTrue(sessions.markReadyAsync(acquired.value().epoch()).completion()
                    .get(5L, TimeUnit.SECONDS).value());
        }

        try (TameworkPersistenceRuntime current =
                     TameworkPersistenceRuntime.initialize(dataDirectory, null);
             OwnerPopulationRuntime runtime = OwnerPopulationRuntime.initialize(current)) {
            assertEquals(
                    OwnerPopulationReadiness.READY,
                    runtime.bootstrapResult().globalReadiness()
            );
            assertEquals(
                    OwnerPopulationReadiness.READY,
                    runtime.bootstrapResult().perWorldReadiness()
            );
            assertEquals(
                    OwnerPopulationReadiness.RECONCILING,
                    runtime.index().readiness(OwnerPopulationLimitScope.GLOBAL)
            );
            assertEquals(
                    OwnerPopulationReadiness.RECONCILING,
                    runtime.index().readiness(OwnerPopulationLimitScope.PER_WORLD)
            );
            assertEquals(
                    ClaimOccupancyReadiness.RECONCILING,
                    runtime.claimOccupancyIndex().readiness()
            );
        }
    }

    private static void seedReadyCoverage(TameworkPersistenceRuntime persistence) throws Exception {
        long now = System.currentTimeMillis();
        for (CompanionPopulationCoverageRecord.Dimension dimension
                : CompanionPopulationCoverageRecord.Dimension.values()) {
            CompanionPopulationCoverageRecord coverage = new CompanionPopulationCoverageRecord(
                    "prior-ready:" + dimension.name().toLowerCase(Locale.ROOT),
                    dimension,
                    "prior-process",
                    "prior-ready-generation",
                    CompanionPopulationCoverageRecord.State.READY,
                    null,
                    1L,
                    1L,
                    now,
                    now,
                    now,
                    null
            );
            assertTrue(persistence.getCompanionPopulationCoverageRepository()
                    .upsertAsync(coverage).completion().get(5L, TimeUnit.SECONDS).isCommitted());
        }
    }
}
