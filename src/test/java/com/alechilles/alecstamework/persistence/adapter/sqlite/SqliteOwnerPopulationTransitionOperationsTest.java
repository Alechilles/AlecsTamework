package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.population.OwnerPopulationScope;
import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlan;
import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlanner;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionDefinition;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionEventCodec;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionOutcome;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionRequest;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.persistence.control.PersistenceOperationAdmissionGate;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shared-operation, exact-source, and serialized capacity admission tests. */
class SqliteOwnerPopulationTransitionOperationsTest {
    private static final ProfileId PROFILE_A =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    private static final ProfileId PROFILE_B =
            ProfileId.parse("20000000-0000-0000-0000-000000000002");
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000001");

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
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
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
        createProfile(PROFILE_A, 1);
        createProfile(PROFILE_B, 2);
    }

    @AfterEach
    void tearDown() {
        if (kernel != null) {
            kernel.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    void exactTransitionRetiresReservationsAndPublishesCanonicalCounts()
            throws Exception {
        OperationWorkflowResult result = submit(
                10,
                PROFILE_A,
                2
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        assertEquals(3, result.events().size());
        assertEquals(
                SqliteOwnerPopulationTransitionOperations.EVENT_TYPE,
                result.events().getFirst().eventType()
        );
        OwnerPopulationTransitionOutcome outcome =
                OwnerPopulationTransitionEventCodec.decode(
                        result.events().getFirst().payloadVersion(),
                        result.events().getFirst().payloadJson()
                );
        assertEquals(OWNER, outcome.ownerId());
        assertEquals("world-a", outcome.ownerWorldKey());
        assertEquals(new LifecycleRevision(1), outcome.committedRevision());
        assertCanonicalCount(1);
        assertEquals(
                1,
                adapter.ownerPopulationIndex().count(
                        OwnerPopulationScope.global(OWNER)
                )
        );
        assertEquals(0, pending());
    }

    @Test
    void concurrentRequestsCannotOverAdmitOneRemainingSlot()
            throws Exception {
        var first = submit(20, PROFILE_A, 1);
        var second = submit(21, PROFILE_B, 1);
        CompletableFuture<OperationWorkflowResult> firstResult =
                first.completion().toCompletableFuture();
        CompletableFuture<OperationWorkflowResult> secondResult =
                second.completion().toCompletableFuture();
        CompletableFuture.allOf(firstResult, secondResult)
                .get(10, TimeUnit.SECONDS);

        List<OperationWorkflowResult.Status> statuses =
                List.of(firstResult.join().status(), secondResult.join().status());
        assertEquals(
                1,
                statuses.stream().filter(status ->
                        status == OperationWorkflowResult.Status.PUBLISHED
                ).count()
        );
        assertEquals(
                1,
                statuses.stream().filter(status ->
                        status
                                == OperationWorkflowResult.Status.PREPARE_FAILED
                ).count()
        );
        OperationWorkflowResult denied =
                firstResult.join().status()
                        == OperationWorkflowResult.Status.PREPARE_FAILED
                        ? firstResult.join()
                        : secondResult.join();
        assertTrue(
                rootMessage(denied.failure()).contains(
                        "owner_population_capacity_reached"
                )
        );
        assertCanonicalCount(1);
        assertEquals(0, pending());
        assertEquals(
                1,
                adapter.ownerPopulationIndex().count(
                        OwnerPopulationScope.global(OWNER)
                )
        );
    }

    @Test
    void staleOwnerEvidenceFailsBeforeAnyReservationSurvives()
            throws Exception {
        OwnerPopulationTransitionRequest stale =
                new OwnerPopulationTransitionRequest(
                        PROFILE_A,
                        LifecycleRevision.INITIAL,
                        OWNER,
                        "world-a",
                        OWNER,
                        "world-b",
                        2,
                        2,
                        -4_000
                );

        OperationWorkflowResult result =
                adapter.ownerPopulationOperations().submit(
                        operationId(30),
                        new IdempotencyKey("population:stale"),
                        stale
                ).completion().toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);

        assertEquals(
                OperationWorkflowResult.Status.PREPARE_FAILED,
                result.status()
        );
        assertTrue(
                rootMessage(result.failure()).contains(
                        "owner_population_source_mismatch"
                )
        );
        assertCanonicalCount(0);
        assertEquals(0, pending());
    }

    @Test
    void startupRecoveryResumesPreparedReservationsThroughSameAdapter()
            throws Exception {
        OwnerPopulationTransitionRequest transition = transition(
                PROFILE_A,
                2,
                -4_500
        );
        OwnerPopulationAdmissionPlan plan =
                OwnerPopulationAdmissionPlanner.plan(transition)
                        .orElseThrow();
        OperationId operationId = operationId(40);
        var prepared = adapter.publicOperations().engine().prepare(
                OwnerPopulationTransitionDefinition.INSTANCE,
                new OperationRequest<>(
                        operationId,
                        new IdempotencyKey("population:recover"),
                        transition,
                        SqliteOwnerPopulationTransitionOperations.FEATURE_SCOPE,
                        transition.expectedLifecycleRevision(),
                        List.of(
                                OperationScope.profile(PROFILE_A),
                                OperationScope.owner(OWNER)
                        ),
                        transition.requestedAtMs()
                ),
                new SqliteOwnerPopulationParticipant(plan)
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertInstanceOf(
                com.alechilles.alecstamework.persistence.kernel
                        .PersistenceTransactionResult.Committed.class,
                prepared
        );
        assertEquals(2, reservationCount(operationId));

        SqlitePublicRecoveryResult recovered = adapter.recover(
                boundaries(),
                "population-recovery"
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(SqlitePublicRecoveryResult.Status.COMPLETE, recovered.status());
        assertEquals(1, recovered.completedCount());
        assertCanonicalCount(1);
        assertEquals(0, reservationCount(operationId));
        assertEquals(0, pending());
    }

    private SqliteDatabaseOperationCoordinator.Submission submit(
            int operationNumber,
            ProfileId profileId,
            int limit
    ) {
        return adapter.ownerPopulationOperations().submit(
                operationId(operationNumber),
                new IdempotencyKey("population:" + operationNumber),
                transition(profileId, limit, -5_000 + operationNumber)
        );
    }

    private OwnerPopulationTransitionRequest transition(
            ProfileId profileId,
            int limit,
            long requestedAtMs
    ) {
        return new OwnerPopulationTransitionRequest(
                profileId,
                LifecycleRevision.INITIAL,
                null,
                null,
                OWNER,
                "world-a",
                limit,
                limit,
                requestedAtMs
        );
    }

    private void createProfile(ProfileId profileId, int operationNumber)
            throws Exception {
        CompanionProfileMutation.Create create =
                new CompanionProfileMutation.Create(
                        new CompanionIdentity(
                                profileId,
                                "Companion " + operationNumber,
                                "role",
                                null,
                                null,
                                "world-a",
                                -10_000,
                                -10_000,
                                -10_000,
                                0
                        ),
                        new CompanionLifecycle(
                                profileId,
                                null,
                                LifecycleState.UNRESOLVED,
                                LifecycleLocation.unresolved(),
                                LifecycleRevision.INITIAL,
                                null,
                                -10_000,
                                ReconciliationGeneration.INITIAL,
                                null,
                                null
                        ),
                        List.of(),
                        -10_000
                );
        OperationWorkflowResult result = adapter.profileOperations().submit(
                operationId(operationNumber),
                new IdempotencyKey("profile:" + operationNumber),
                create
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
    }

    private void assertCanonicalCount(long expected) throws Exception {
        PersistenceReadResult.Found<List<CompanionLifecycle>> found =
                assertInstanceOf(
                        PersistenceReadResult.Found.class,
                        adapter.lifecycleReader().findAll()
                                .toCompletableFuture()
                                .get(10, TimeUnit.SECONDS)
                );
        assertEquals(
                expected,
                found.value().stream()
                        .filter(lifecycle ->
                                OWNER.equals(lifecycle.ownerId()))
                        .count()
        );
    }

    private long pending() throws Exception {
        try (var connection = connections.openReadConnection()) {
            return new SqliteOwnerPopulationStore(connection).pendingCount(
                    OwnerPopulationScope.global(OWNER)
            );
        }
    }

    private int reservationCount(OperationId operationId) throws Exception {
        try (var connection = connections.openReadConnection()) {
            return new SqliteOwnerPopulationStore(connection)
                    .findByOperation(operationId)
                    .size();
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
