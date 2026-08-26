package com.alechilles.alecstamework;

import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmission;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainCapacityException;
import com.alechilles.alecstamework.items.persistence.SpawnerPersistenceAuthorResult;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Player-feedback regressions for completed spawner persistence workflows. */
class TameworkPersistenceAuthorsTest {
    @Test
    void capacityFailuresHaveSpecificFeedback() {
        // Protects the generic release message reported for the 2026-08-25
        // population_domain_deployable_capacity_reached failure.
        assertEquals(
                "Not enough deployed companion capacity: 8 / 8 slots used; "
                        + "release needs 1 slot.",
                TameworkPersistenceAuthors.spawnerFailureMessage(failed(
                        SpawnerPersistenceAuthorResult.Kind.CAPTURE_RELEASE,
                        new CompletionException(new PopulationDomainCapacityException(
                                PopulationDomainAdmission.Status
                                        .DEPLOYABLE_CAPACITY_REACHED,
                                8, 1, 8
                        ))
                ))
        );
        assertEquals(
                "Not enough owned companion capacity: 11 / 12 slots used; "
                        + "capture needs 2 slots.",
                TameworkPersistenceAuthors.spawnerFailureMessage(failed(
                        SpawnerPersistenceAuthorResult.Kind.CAPTURE,
                        new PopulationDomainCapacityException(
                                PopulationDomainAdmission.Status
                                        .OWNED_CAPACITY_REACHED,
                                11, 2, 12
                        )
                ))
        );
        assertEquals(
                "Companion release could not be completed.",
                TameworkPersistenceAuthors.spawnerFailureMessage(failed(
                        SpawnerPersistenceAuthorResult.Kind.CAPTURE_RELEASE,
                        new IllegalStateException("unrelated_failure")
                ))
        );
    }

    private static SpawnerPersistenceAuthorResult failed(
            SpawnerPersistenceAuthorResult.Kind kind,
            Throwable failure
    ) {
        return new SpawnerPersistenceAuthorResult(
                kind,
                SpawnerPersistenceAuthorResult.Status.WORKFLOW_FAILED,
                null,
                null,
                "workflow_not_published",
                failure
        );
    }
}
