package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.population.OwnerPopulationEvidenceClaim;
import com.alechilles.alecstamework.companion.population.OwnerPopulationReconciliationDefinition;
import com.alechilles.alecstamework.companion.population.OwnerPopulationReconciliationEventCodec;
import com.alechilles.alecstamework.companion.population.OwnerPopulationReconciliationOutcome;
import com.alechilles.alecstamework.companion.population.OwnerPopulationReconciliationRequest;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionRequest;
import com.alechilles.alecstamework.companion.population.PopulationEvidenceBatch;
import com.alechilles.alecstamework.companion.population.PopulationEvidenceObservation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.persistence.control.PersistenceOperationAdmissionGate;
import com.alechilles.alecstamework.persistence.incidents.QuarantineState;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shared-operation reconciliation and owner-scoped containment tests. */
class SqliteOwnerPopulationReconciliationOperationsTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000021");
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000021");
    private static final OwnerId OTHER_OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000022");
    private static final ReconciliationGeneration GENERATION =
            new ReconciliationGeneration(7);

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqlitePersistenceKernel kernel;
    private SqlitePublicPersistenceAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("tamework-state.sqlite")
        );
        new SqliteSchemaV1Manager(connections, () -> -20_000)
                .initialize();
        kernel = new SqlitePersistenceKernel(connections);
        adapter = new SqlitePublicPersistenceAdapter(
                PublicPersistenceFeatureRegistry.create(),
                kernel,
                PersistenceOperationAdmissionGate.allowAll(),
                () -> -5_000,
                (claim, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.confirmed(
                                        "test_refund"
                                ).completed(),
                event -> {
                }
        );
        createProfile();
    }

    @AfterEach
    void tearDown() {
        if (kernel != null) {
            kernel.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    void exactPositiveEvidenceAdvancesOnlyCanonicalReconciliationGeneration()
            throws Exception {
        PopulationEvidenceObservation observation = positive(
                PopulationEvidenceBatch.Source.LIVE,
                OWNER,
                "world-a"
        );
        OwnerPopulationReconciliationRequest request =
                request(OwnerPopulationEvidenceClaim.positive(observation));

        OperationWorkflowResult result = submit(10, request);

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        assertEquals(3, result.events().size());
        OwnerPopulationReconciliationOutcome outcome =
                OwnerPopulationReconciliationEventCodec.decode(
                        result.events().getFirst().payloadVersion(),
                        result.events().getFirst().payloadJson()
                );
        assertEquals(
                OwnerPopulationReconciliationOutcome.Status.RECONCILED,
                outcome.status()
        );
        CompanionLifecycle lifecycle = lifecycle();
        assertEquals(new LifecycleRevision(1), lifecycle.revision());
        assertEquals(GENERATION, lifecycle.lastReconciledGeneration());
        assertEquals(-20_000, lifecycle.stateChangedAtMs());
        assertNull(lifecycle.quarantineIncidentId());
        assertEquals(1, canonicalOwnerCount());
    }

    @Test
    void incompleteEvidenceCannotBecomeAnAbsenceMutation()
            throws Exception {
        PopulationEvidenceBatch.Key disk = key(
                PopulationEvidenceBatch.Source.DISK,
                GENERATION
        );
        writeBatch(disk, true, null);
        OwnerPopulationReconciliationRequest request = request(
                OwnerPopulationEvidenceClaim.absence(
                        "boot-a", "world-a", GENERATION
                )
        );

        OperationWorkflowResult result = submit(20, request);

        assertEquals(
                OperationWorkflowResult.Status.PREPARE_FAILED,
                result.status()
        );
        assertTrue(rootMessage(result.failure()).contains(
                "population_absence_sources_incomplete"
        ));
        assertEquals(LifecycleRevision.INITIAL, lifecycle().revision());
        assertNull(lifecycle().quarantineIncidentId());
    }

    @Test
    void sealedAbsenceQuarantinesOwnerWithoutFreeingCapacity()
            throws Exception {
        writeBatch(
                key(PopulationEvidenceBatch.Source.DISK, GENERATION),
                true,
                null
        );
        writeBatch(
                key(PopulationEvidenceBatch.Source.LIVE, GENERATION),
                true,
                null
        );
        OwnerPopulationReconciliationRequest request = request(
                OwnerPopulationEvidenceClaim.absence(
                        "boot-a", "world-a", GENERATION
                )
        );

        OperationWorkflowResult result = submit(30, request);

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        CompanionLifecycle lifecycle = lifecycle();
        assertNotNull(lifecycle.quarantineIncidentId());
        assertEquals(
                ReconciliationGeneration.INITIAL,
                lifecycle.lastReconciledGeneration()
        );
        assertEquals(1, canonicalOwnerCount());
        try (var connection = connections.openReadConnection()) {
            SqliteIncidentStore incidents =
                    new SqliteIncidentStore(connection);
            assertEquals(
                    QuarantineState.ACTIVE,
                    incidents.findQuarantine(
                            OperationScope.owner(OWNER)
                    ).orElseThrow().state()
            );
            assertEquals(
                    QuarantineState.ACTIVE,
                    incidents.findQuarantine(
                            OperationScope.profile(PROFILE)
                    ).orElseThrow().state()
            );
        }

        OperationWorkflowResult denied =
                adapter.ownerPopulationOperations().submit(
                        operationId(31),
                        new IdempotencyKey("population:after-quarantine"),
                        new OwnerPopulationTransitionRequest(
                                PROFILE,
                                new LifecycleRevision(1),
                                OWNER,
                                "world-a",
                                null,
                                null,
                                0,
                                0,
                                -4_900
                        )
                ).completion().toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
        assertEquals(
                OperationWorkflowResult.Status.PREPARE_FAILED,
                denied.status()
        );
        assertTrue(rootMessage(denied.failure()).contains(
                "operation_scope_quarantined"
        ));
    }

    @Test
    void preparedPositiveReconciliationRecoversThroughTheSameAdapter()
            throws Exception {
        PopulationEvidenceObservation observation = positive(
                PopulationEvidenceBatch.Source.DISK,
                OWNER,
                "world-a"
        );
        OwnerPopulationReconciliationRequest request =
                request(OwnerPopulationEvidenceClaim.positive(observation));
        OperationId operationId = operationId(40);
        adapter.publicOperations().engine().prepare(
                OwnerPopulationReconciliationDefinition.INSTANCE,
                new OperationRequest<>(
                        operationId,
                        new IdempotencyKey("population-reconcile:recover"),
                        request,
                        SqliteOwnerPopulationReconciliationOperations
                                .FEATURE_SCOPE,
                        request.expectedLifecycleRevision(),
                        List.of(
                                OperationScope.profile(PROFILE),
                                OperationScope.owner(OWNER)
                        ),
                        request.requestedAtMs()
                )
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        SqlitePublicRecoveryResult recovered = adapter.recover(
                boundaries(),
                "population-reconciliation-recovery"
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(
                SqlitePublicRecoveryResult.Status.COMPLETE,
                recovered.status()
        );
        assertEquals(1, recovered.completedCount());
        assertEquals(GENERATION, lifecycle().lastReconciledGeneration());
        assertEquals(new LifecycleRevision(1), lifecycle().revision());
    }

    @Test
    void lateSameGenerationContradictionStillQuarantinesTheOwner()
            throws Exception {
        PopulationEvidenceObservation matching = positive(
                PopulationEvidenceBatch.Source.LIVE,
                OWNER,
                "world-a"
        );
        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                submit(
                        50,
                        request(
                                OwnerPopulationEvidenceClaim.positive(
                                        matching
                                )
                        )
                ).status()
        );
        PopulationEvidenceObservation contradiction = positive(
                PopulationEvidenceBatch.Source.DISK,
                OTHER_OWNER,
                "world-b"
        );
        OwnerPopulationReconciliationRequest late =
                new OwnerPopulationReconciliationRequest(
                        PROFILE,
                        new LifecycleRevision(1),
                        OWNER,
                        "world-a",
                        OwnerPopulationEvidenceClaim.positive(
                                contradiction
                        ),
                        -4_900
                );

        OperationWorkflowResult result = submit(51, late);

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        CompanionLifecycle lifecycle = lifecycle();
        assertEquals(new LifecycleRevision(2), lifecycle.revision());
        assertEquals(GENERATION, lifecycle.lastReconciledGeneration());
        assertNotNull(lifecycle.quarantineIncidentId());
        assertEquals(1, canonicalOwnerCount());
        try (var connection = connections.openReadConnection()) {
            SqliteIncidentStore incidents =
                    new SqliteIncidentStore(connection);
            assertEquals(
                    QuarantineState.ACTIVE,
                    incidents.findQuarantine(
                            OperationScope.owner(OWNER)
                    ).orElseThrow().state()
            );
            assertEquals(
                    QuarantineState.ACTIVE,
                    incidents.findQuarantine(
                            OperationScope.owner(OTHER_OWNER)
                    ).orElseThrow().state()
            );
        }
    }

    @Test
    void definitionRoundTripsExactPositiveAndAbsenceClaims() {
        PopulationEvidenceObservation observation = new PopulationEvidenceObservation(
                key(PopulationEvidenceBatch.Source.DISK, GENERATION),
                PROFILE,
                true,
                OTHER_OWNER,
                "world-b",
                -6_000
        );
        for (OwnerPopulationEvidenceClaim evidence : List.of(
                OwnerPopulationEvidenceClaim.positive(observation),
                OwnerPopulationEvidenceClaim.absence(
                        "boot-a", "world-a", GENERATION
                )
        )) {
            OwnerPopulationReconciliationRequest request =
                    new OwnerPopulationReconciliationRequest(
                            PROFILE,
                            new LifecycleRevision(3),
                            OWNER,
                            "world-a",
                            evidence,
                            -5_000
                    );
            assertEquals(
                    request,
                    OwnerPopulationReconciliationDefinition.INSTANCE.decode(
                            OwnerPopulationReconciliationDefinition.INSTANCE
                                    .encode(request)
                    )
            );
        }
    }

    private OperationWorkflowResult submit(
            int number,
            OwnerPopulationReconciliationRequest request
    ) throws Exception {
        return adapter.ownerPopulationReconciliationOperations().submit(
                operationId(number),
                new IdempotencyKey("population-reconcile:" + number),
                request
        ).completion().toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
    }

    private OwnerPopulationReconciliationRequest request(
            OwnerPopulationEvidenceClaim evidence
    ) {
        return new OwnerPopulationReconciliationRequest(
                PROFILE,
                LifecycleRevision.INITIAL,
                OWNER,
                "world-a",
                evidence,
                -5_000
        );
    }

    private PopulationEvidenceObservation positive(
            PopulationEvidenceBatch.Source source,
            OwnerId observedOwner,
            String observedWorld
    ) throws Exception {
        PopulationEvidenceBatch.Key key = key(source, GENERATION);
        PopulationEvidenceObservation observation =
                new PopulationEvidenceObservation(
                        key,
                        PROFILE,
                        true,
                        observedOwner,
                        observedWorld,
                        -6_000
                );
        try (var connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqliteOwnerPopulationEvidenceStore store =
                    new SqliteOwnerPopulationEvidenceStore(connection);
            assertTrue(store.open(
                    PopulationEvidenceBatch.open(key, -7_000)
            ).applied());
            assertTrue(store.observe(observation).applied());
            connection.commit();
        }
        return observation;
    }

    private void writeBatch(
            PopulationEvidenceBatch.Key key,
            boolean sealed,
            String failure
    ) throws Exception {
        try (var connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqliteOwnerPopulationEvidenceStore store =
                    new SqliteOwnerPopulationEvidenceStore(connection);
            assertTrue(store.open(
                    PopulationEvidenceBatch.open(key, -7_000)
            ).applied());
            assertTrue(store.close(
                    key,
                    sealed
                            ? PopulationEvidenceBatch.Status.SEALED
                            : PopulationEvidenceBatch.Status.FAILED,
                    -6_000,
                    failure
            ).applied());
            connection.commit();
        }
    }

    private PopulationEvidenceBatch.Key key(
            PopulationEvidenceBatch.Source source,
            ReconciliationGeneration generation
    ) {
        return new PopulationEvidenceBatch.Key(
                "boot-a", "world-a", generation, source
        );
    }

    private void createProfile() throws Exception {
        CompanionProfileMutation.Create create =
                new CompanionProfileMutation.Create(
                        new CompanionIdentity(
                                PROFILE,
                                "Companion",
                                "role",
                                null,
                                null,
                                "world-a",
                                -20_000,
                                -20_000,
                                -20_000,
                                0
                        ),
                        new CompanionLifecycle(
                                PROFILE,
                                OWNER,
                                LifecycleState.UNLOADED,
                                LifecycleLocation.none(),
                                LifecycleRevision.INITIAL,
                                null,
                                -20_000,
                                ReconciliationGeneration.INITIAL,
                                null,
                                "world-a"
                        ),
                        List.of(),
                        -20_000
                );
        OperationWorkflowResult result = adapter.profileOperations().submit(
                operationId(1),
                new IdempotencyKey("profile:population-reconciliation"),
                create
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
    }

    private CompanionLifecycle lifecycle() throws Exception {
        try (var connection = connections.openReadConnection()) {
            return new SqliteCompanionLifecycleStore(connection)
                    .findByProfile(PROFILE)
                    .orElseThrow();
        }
    }

    private long canonicalOwnerCount() throws Exception {
        try (var connection = connections.openReadConnection()) {
            return new SqliteOwnerPopulationStore(connection).committedCount(
                    com.alechilles.alecstamework.companion.population
                            .OwnerPopulationScope.global(OWNER)
            );
        }
    }

    private PublicPersistenceLiveBoundaries boundaries() {
        return new PublicPersistenceLiveBoundaries(
                (rotation, operation) ->
                        com.alechilles.alecstamework.companion.identity
                                .CompanionAliasLiveBoundary.Result.confirmed(),
                (request, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.confirmed(
                                        "capture"
                                ).completed(),
                (request, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.confirmed(
                                        "restoration"
                                ).completed(),
                (request, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.confirmed(
                                        "coop_capture"
                                ).completed(),
                (request, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.confirmed(
                                        "coop_release"
                                ).completed(),
                (request, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.confirmed(
                                        "timed"
                                ).completed(),
                (request, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.confirmed(
                                        "provisioning_activation"
                                ).completed()
        );
    }

    private OperationId operationId(int number) {
        return OperationId.parse(String.format(
                "40000000-0000-0000-0000-%012d",
                number
        ));
    }

    private String rootMessage(Throwable failure) {
        ArrayList<String> messages = new ArrayList<>();
        while (failure != null) {
            if (failure.getMessage() != null) {
                messages.add(failure.getMessage());
            }
            failure = failure.getCause();
        }
        return String.join(":", messages);
    }
}
