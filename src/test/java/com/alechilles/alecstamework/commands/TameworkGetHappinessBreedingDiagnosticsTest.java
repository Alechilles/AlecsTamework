package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.npc.breeding.BreedingBirthJob;
import com.alechilles.alecstamework.npc.breeding.BreedingBirthJobRegistry;
import com.alechilles.alecstamework.npc.breeding.BreedingBirthJobState;
import com.alechilles.alecstamework.npc.breeding.BreedingJobDiagnosticSnapshot;
import com.alechilles.alecstamework.npc.breeding.BreedingParentIdentity;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the admin-visible exact outcome and population context for the latest birth job. */
class TameworkGetHappinessBreedingDiagnosticsTest {
    @Test
    void terminalJobShowsExactSpawnCountHeadroomReasonAndRollback() {
        Object scope = new Object();
        UUID parentUuid = uuid(1L);
        BreedingBirthJob job = BreedingBirthJob.reserved(
                uuid(100L),
                "world-a",
                new BreedingParentIdentity(parentUuid, "profile-a"),
                new BreedingParentIdentity(uuid(2L), "profile-b")
        );
        BreedingBirthJobRegistry registry = new BreedingBirthJobRegistry();
        registry.register(scope, job);
        registry.advance(
                scope,
                job.jobId(),
                BreedingBirthJobState.RESERVED,
                BreedingBirthJobState.APPROACHING
        );
        registry.advance(
                scope,
                job.jobId(),
                BreedingBirthJobState.APPROACHING,
                BreedingBirthJobState.HEARTS_SHOWN
        );
        registry.claimSpawn(scope, job.jobId());
        BreedingBirthJob completed = registry.complete(scope, job.jobId()).job().orElseThrow();
        BreedingJobDiagnosticSnapshot.CapacitySnapshot capacity =
                new BreedingJobDiagnosticSnapshot.CapacitySnapshot(
                        8,
                        Map.of("cattle", 5),
                        2,
                        Integer.MAX_VALUE,
                        3,
                        3,
                        Map.of()
                );
        BreedingJobDiagnosticSnapshot diagnostics = new BreedingJobDiagnosticSnapshot(
                job.jobId(),
                capacity,
                capacity,
                1,
                true,
                BreedingJobDiagnosticSnapshot.Outcome.COMPLETED,
                "child-spawn-failures=1",
                BreedingJobDiagnosticSnapshot.RollbackStatus.NOT_ATTEMPTED,
                null
        );
        StringBuilder message = new StringBuilder();

        TameworkGetHappinessCommand.appendBreedingJob(
                message,
                parentUuid,
                completed,
                diagnostics
        );

        String output = message.toString();
        assertTrue(output.contains("Latest breeding job: id=" + job.jobId()));
        assertTrue(output.contains("spawned=1"));
        assertTrue(output.contains("initialPopulation={admitted=2, maxNearby=8"));
        assertTrue(output.contains("liveNearby={cattle=5}"));
        assertTrue(output.contains("claimHeadroom=unlimited"));
        assertTrue(output.contains("reason=child-spawn-failures=1"));
        assertTrue(output.contains("rollback=NOT_ATTEMPTED"));
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
