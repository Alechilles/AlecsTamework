package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationAdmissionIdentity;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV2;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV3;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionOperation;
import com.alechilles.alecstamework.persistence.control.PersistenceOperationAdmissionGate;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionRequest;
import com.alechilles.alecstamework.persistence.runtime.PersistenceLifecycleAdmissionGateway;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceFeatureRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavior checks for the shared lifecycle-admission binding. */
class SqliteLifecycleAdmissionBindingTest {
    @TempDir
    Path tempDir;

    private SqlitePersistenceKernel kernel;

    @AfterEach
    void tearDown() {
        if (kernel != null) {
            kernel.shutdown(java.time.Duration.ofSeconds(5));
        }
    }

    @Test
    void managedPositiveAuthoringFailsClosedBeforeBinding() {
        SqliteLifecycleAdmissionBinding binding =
                new SqliteLifecycleAdmissionBinding();

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> binding.authorize(managedRequest())
                        .toCompletableFuture().join()
        );

        assertTrue(failure.getCause() instanceof IllegalStateException);
        assertEquals(
                "managed-lifecycle-admission-authority-unavailable",
                failure.getCause().getMessage()
        );
    }

    @Test
    void unboundGatewayDoesNotAllowAnUnmanagedBypass() {
        SqliteLifecycleAdmissionBinding binding =
                new SqliteLifecycleAdmissionBinding();

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> binding.authorize(
                        LifecycleAdmissionRequest.unmanaged(
                                OperationId.create(),
                                UUID.randomUUID(),
                                "role",
                                null,
                                LifecycleState.ACTIVE,
                                LifecycleState.COOP,
                                null,
                                null
                        )
                ).toCompletableFuture().join()
        );

        assertEquals(
                "managed-lifecycle-admission-authority-unavailable",
                failure.getCause().getMessage()
        );
    }

    @Test
    void oneBindIsVisibleToPublicAndRecoverySets() {
        SqliteConnectionFactory connections = new SqliteConnectionFactory(
                tempDir.resolve("lifecycle-admission.sqlite")
        );
        new SqliteSchemaV1Manager(connections, () -> -100).initialize();
        kernel = new SqlitePersistenceKernel(connections);
        SqlitePublicPersistenceAdapter adapter =
                new SqlitePublicPersistenceAdapter(
                        PublicPersistenceFeatureRegistry.create(),
                        kernel,
                        PersistenceOperationAdmissionGate.allowAll(),
                        () -> -100,
                        (claim, operation) -> LiveOperationResult
                                .confirmed("test-refund")
                                .completed(),
                        event -> {
                        }
                );

        SqliteLifecycleAdmissionBinding publicBinding =
                adapter.publicOperations().lifecycleAdmission();
        SqliteLifecycleAdmissionBinding recoveryBinding =
                adapter.recoveryOperations().lifecycleAdmission();
        assertSame(publicBinding, recoveryBinding);

        AtomicInteger calls = new AtomicInteger();
        PersistenceLifecycleAdmissionGateway gateway = request -> {
            calls.incrementAndGet();
            return java.util.concurrent.CompletableFuture.completedFuture(
                    LifecycleAdmissionEvidence.neutral()
            );
        };
        adapter.bindLifecycleAdmission(gateway);

        assertSame(gateway, publicBinding.gateway());
        assertSame(gateway, recoveryBinding.gateway());
        assertEquals(
                LifecycleAdmissionEvidence.Status.NEUTRAL,
                publicBinding.authorize(managedRequest())
                        .toCompletableFuture().join().status()
        );
        assertEquals(
                LifecycleAdmissionEvidence.Status.NEUTRAL,
                recoveryBinding.authorize(managedRequest())
                        .toCompletableFuture().join().status()
        );
        assertEquals(2, calls.get());
        assertThrows(
                IllegalStateException.class,
                () -> adapter.bindLifecycleAdmission(gateway)
        );
    }

    @Test
    void durableReconstructionWorksBeforeProviderBinding() {
        SqliteLifecycleAdmissionBinding binding =
                new SqliteLifecycleAdmissionBinding();
        PopulationDomainAdmissionOperation.Payload payload = new
                PopulationDomainAdmissionOperation.Payload(
                UUID.randomUUID(),
                ProfileId.parse("20000000-0000-0000-0000-000000000001"),
                OwnerId.parse("10000000-0000-0000-0000-000000000001"),
                null,
                "world",
                null,
                null,
                null,
                LifecycleState.ACTIVE,
                "group",
                "provider",
                1,
                "generation",
                1,
                1,
                Long.MAX_VALUE,
                1,
                List.of(new PopulationDomainAdmissionOperation.DomainInput(
                        "owned",
                        com.alechilles.alecstamework.companion.population.domain
                                .PopulationDomainScope.GLOBAL,
                        null,
                        1,
                        1,
                        1,
                        10,
                        10,
                        1
                )),
                List.of(),
                0
        );

        LifecycleAdmissionEvidence result = binding.reconstructDurable(
                payload,
                null
        );

        assertEquals(LifecycleAdmissionEvidence.Status.MANAGED, result.status());
        assertSame(payload, result.payload());
    }

    private LifecycleAdmissionRequest managedRequest() {
        PopulationAdmissionRequest admission = new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity(null, "provisional", null),
                null,
                PopulationAdmissionRequest.NEW_PROFILE_REVISION,
                null,
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                null,
                new PopulationAdmissionLocation("world", 0, 0),
                PopulationAdmissionOperation.NEW_OWNERSHIP,
                1,
                PopulationAdmissionForcePolicy.ENFORCE,
                com.alechilles.alecstamework.api.PopulationCompanionLifecycle.ACTIVE
        );
        PopulationAdmissionRequestV3 request = new PopulationAdmissionRequestV3(
                new PopulationAdmissionRequestV2(admission, "role", "world"),
                "managed"
        );
        return LifecycleAdmissionRequest.managed(
                OperationId.create(),
                UUID.randomUUID(),
                "role",
                request,
                null,
                null,
                LifecycleState.ACTIVE,
                null,
                null
        );
    }
}
