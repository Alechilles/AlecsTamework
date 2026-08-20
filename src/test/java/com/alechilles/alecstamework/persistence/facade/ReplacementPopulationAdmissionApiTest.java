package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationAdmissionIdentity;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV2;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV3;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.api.internal.AdmissionProviderRegistry;
import com.alechilles.alecstamework.companion.population.domain.ManagedAdmissionEvidenceAuthor;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceRuntimeConfiguration;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceWorldReconciliation;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Readiness and fail-closed behavior for the managed admission facade. */
class ReplacementPopulationAdmissionApiTest {
    private static final UUID OWNER = UUID.fromString(
            "30000000-0000-0000-0000-000000000411"
    );

    @TempDir
    Path tempDir;

    @Test
    void missingManagedProfileFailsClosedBeforeOperationPreparation() {
        try (PersistenceBootstrap persistence = new PersistenceBootstrap(configuration())) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            PopulationGroupConfigRegistry groups = new PopulationGroupConfigRegistry();
            ManagedActivityConfigRegistry managed =
                    new ManagedActivityConfigRegistry(groups);
            AdmissionProviderRegistry providers = new AdmissionProviderRegistry();
            ManagedAdmissionEvidenceAuthor author =
                    new ManagedAdmissionEvidenceAuthor(
                            managed, groups, providers, () -> -50L
                    );
            ReplacementPopulationAdmissionApi api =
                    new ReplacementPopulationAdmissionApi(
                            persistence,
                            persistence.facades().operations()
                                    .populationDomainAdmission(),
                            author,
                            managed,
                            providers,
                            () -> -50L
                    );

            PopulationAdmissionDecision result = api.tryAdmitV3(request())
                    .toCompletableFuture().join();

            assertEquals(PopulationAdmissionDecision.Status.UNAVAILABLE, result.status());
            assertFalse(api.status("runeteria:husbandry").available());
            assertEquals("profile-not-found",
                    api.status("runeteria:husbandry").detail());
        }
    }

    private PopulationAdmissionRequestV3 request() {
        PopulationAdmissionRequest admission = new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity(
                        null,
                        "40000000-0000-0000-0000-000000000411",
                        "facade-test"
                ),
                null,
                PopulationAdmissionRequest.NEW_PROFILE_REVISION,
                null,
                OWNER,
                null,
                new PopulationAdmissionLocation("world", 0, 0),
                PopulationAdmissionOperation.NEW_OWNERSHIP,
                1,
                PopulationAdmissionForcePolicy.ENFORCE,
                PopulationCompanionLifecycle.ACTIVE
        );
        return new PopulationAdmissionRequestV3(
                new PopulationAdmissionRequestV2(
                        admission, "unmanaged-role", "world"
                ),
                "runeteria:husbandry"
        );
    }

    private PublicPersistenceRuntimeConfiguration configuration() {
        return new PublicPersistenceRuntimeConfiguration(
                tempDir,
                "replacement-population-admission-test",
                () -> -100L,
                (claim, operation) -> confirmed("refund"),
                event -> { },
                boundaries(),
                PublicPersistenceWorldReconciliation.alreadyComplete(),
                Duration.ofSeconds(5)
        );
    }

    private PublicPersistenceLiveBoundaries boundaries() {
        return new PublicPersistenceLiveBoundaries(
                (request, operation) -> confirmed("capture"),
                (request, operation) -> confirmed("capture_release"),
                (request, operation) -> confirmed("restoration"),
                (request, operation) -> confirmed("coop_capture"),
                (request, operation) -> confirmed("coop_release")
        );
    }

    private java.util.concurrent.CompletionStage<LiveOperationResult> confirmed(
            String code
    ) {
        return LiveOperationResult.confirmed(code).completed();
    }
}
