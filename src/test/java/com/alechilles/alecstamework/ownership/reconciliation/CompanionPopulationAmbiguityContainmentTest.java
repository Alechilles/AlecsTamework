package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.persistence.health.PersistenceMutationAvailabilityStatus;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationContext;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationDelta;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDisposition;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFailureClass;
import com.alechilles.alecstamework.persistence.incidents.PersistenceOperationPhase;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeType;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionPopulationAmbiguityContainmentTest {
    @TempDir
    Path tempDir;

    @Test
    void ambiguousJournalDurablyFencesOnlyItsOperationAndProfile() throws Exception {
        try (TameworkPersistenceRuntime persistence =
                     TameworkPersistenceRuntime.initialize(tempDir.resolve("runtime"), null)) {
            var containment = new CompanionPopulationAmbiguityContainment(
                    persistence.getIncidentReporter(), persistence.getPersistenceScopeFactory());
            var ambiguity = new CompanionPopulationOperationRecoveryService.AmbiguousOperation(
                    "operation-a", "profile-a",
                    "operation-recovery-source-finalization-pending:spawner_item");

            assertTrue(containment.containAsync(List.of(ambiguity))
                    .get(5L, TimeUnit.SECONDS));

            var incidents = persistence.getIncidentRepository().listOpen(10);
            assertEquals(1, incidents.size());
            assertEquals(PersistenceFailureClass.SCOPED_APPLY_AMBIGUITY,
                    incidents.getFirst().failureClass());
            assertEquals(PersistenceDisposition.SCOPED_QUARANTINE,
                    incidents.getFirst().disposition());
            assertEquals(PersistenceDomain.RECONCILIATION, incidents.getFirst().domain());
            assertEquals(PersistenceOperationPhase.RECOVERY, incidents.getFirst().phase());
            assertEquals(2, persistence.getQuarantineRepository().listActive().size());
            assertTrue(persistence.getQuarantineRegistry()
                    .find(PersistenceScopeType.OPERATION, "operation-a").isPresent());
            assertTrue(persistence.getQuarantineRegistry()
                    .find(PersistenceScopeType.PROFILE, "profile-a").isPresent());

            assertEquals(PersistenceMutationAvailabilityStatus.QUARANTINED,
                    availability(persistence, "profile-a").status());
            assertEquals(PersistenceMutationAvailabilityStatus.ALLOW,
                    availability(persistence, "profile-b").status());

            assertTrue(containment.containAsync(List.of(ambiguity))
                    .get(5L, TimeUnit.SECONDS));
            assertEquals(1, persistence.getIncidentRepository().listOpen(10).size());
            assertEquals(2, persistence.getQuarantineRepository().listActive().size());
            assertEquals(2L,
                    persistence.getIncidentRepository().listOpen(10).getFirst().occurrenceCount());
        }
    }

    private static com.alechilles.alecstamework.persistence.health.PersistenceMutationAvailabilityDecision
    availability(TameworkPersistenceRuntime persistence, String profileId) {
        var scope = persistence.getPersistenceScopeFactory().profile(profileId);
        return persistence.getMutationAvailabilityService().decide(new PersistenceMutationContext(
                PersistenceDomain.CAPTURE_RELEASE,
                "release",
                List.of(scope),
                Set.of(),
                PersistenceMutationDelta.ZERO,
                null,
                null,
                true,
                false
        ));
    }
}
