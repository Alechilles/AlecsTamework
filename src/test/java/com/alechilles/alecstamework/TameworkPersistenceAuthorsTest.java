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

    @Test
    void knownWorkflowFailuresExplainWhatWentWrong() {
        assertEquals(
                "No safe companion release location was available here.",
                TameworkPersistenceAuthors.spawnerFailureMessage(result(
                        SpawnerPersistenceAuthorResult.Kind.CAPTURE_RELEASE,
                        SpawnerPersistenceAuthorResult.Status.PLACEMENT_FAILED,
                        "capture_release_placement_failed",
                        null
                ))
        );
        assertEquals(
                "The stored companion data could not be read.",
                TameworkPersistenceAuthors.spawnerFailureMessage(result(
                        SpawnerPersistenceAuthorResult.Kind.CAPTURE_RELEASE,
                        SpawnerPersistenceAuthorResult.Status.SNAPSHOT_DECODE_FAILED,
                        "capture_snapshot_decode_failed",
                        null
                ))
        );
        assertEquals(
                "The companion record could not be loaded.",
                TameworkPersistenceAuthors.spawnerFailureMessage(result(
                        SpawnerPersistenceAuthorResult.Kind.CAPTURE_RELEASE,
                        SpawnerPersistenceAuthorResult.Status.PROFILE_READ_FAILED,
                        "capture_release_profile_read_failed",
                        null
                ))
        );
    }

    private static SpawnerPersistenceAuthorResult failed(
            SpawnerPersistenceAuthorResult.Kind kind,
            Throwable failure
    ) {
        return result(
                kind,
                SpawnerPersistenceAuthorResult.Status.WORKFLOW_FAILED,
                "workflow_not_published",
                failure
        );
    }

    private static SpawnerPersistenceAuthorResult result(
            SpawnerPersistenceAuthorResult.Kind kind,
            SpawnerPersistenceAuthorResult.Status status,
            String detail,
            Throwable failure
    ) {
        return new SpawnerPersistenceAuthorResult(
                kind,
                status,
                null,
                null,
                detail,
                failure
        );
    }
}
