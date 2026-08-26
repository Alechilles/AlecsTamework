package com.alechilles.alecstamework;

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
                "Your active companion limit has been reached.",
                TameworkPersistenceAuthors.spawnerFailureMessage(failed(
                        SpawnerPersistenceAuthorResult.Kind.CAPTURE_RELEASE,
                        new CompletionException(new IllegalStateException(
                                "population_domain_deployable_capacity_reached"
                        ))
                ))
        );
        assertEquals(
                "Your owned companion limit has been reached.",
                TameworkPersistenceAuthors.spawnerFailureMessage(failed(
                        SpawnerPersistenceAuthorResult.Kind.CAPTURE,
                        new IllegalStateException(
                                "population_domain_owned_capacity_reached"
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
