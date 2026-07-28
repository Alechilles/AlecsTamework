package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentDefinition;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentRequest;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupBucket;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupCounts;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
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

/** Shared-envelope group classification, admission, and recovery tests. */
class SqlitePopulationGroupAssignmentOperationsTest {
    private static final ProfileId PROFILE_A =
            ProfileId.parse("20000000-0000-0000-0000-000000000071");
    private static final ProfileId PROFILE_B =
            ProfileId.parse("20000000-0000-0000-0000-000000000072");
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000071");

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
                                .LiveOperationResult.confirmed("refund")
                                .completed(),
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
    void exactAssignmentRetiresReservationAndPublishesCanonicalCount()
            throws Exception {
        OperationId operationId = operationId(10);
        OperationWorkflowResult result = submit(
                operationId, PROFILE_A, 2
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        assertEquals(1, result.events().size());
        PopulationGroupAssignment assignment = assignment(PROFILE_A);
        assertEquals(List.of("mod:mini"), assignment.memberships().stream()
                .map(membership -> membership.groupId())
                .toList());
        assertEquals(0, reservationCount(operationId));
        assertEquals(
                new PopulationGroupCounts(1, 1, 0, 0),
                adapter.populationGroupIndex().counts(bucket())
        );
        assertTrue(adapter.populationGroupIndex()
                .laggingProfiles().contains(PROFILE_B));
    }

    @Test
    void concurrentProfilesCannotOverAdmitOneGroupSlot()
            throws Exception {
        CompletableFuture<OperationWorkflowResult> first = submit(
                operationId(20), PROFILE_A, 1
        ).completion().toCompletableFuture();
        CompletableFuture<OperationWorkflowResult> second = submit(
                operationId(21), PROFILE_B, 1
        ).completion().toCompletableFuture();
        CompletableFuture.allOf(first, second)
                .get(10, TimeUnit.SECONDS);

        List<OperationWorkflowResult.Status> statuses =
                List.of(first.join().status(), second.join().status());
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
                first.join().status()
                        == OperationWorkflowResult.Status.PREPARE_FAILED
                        ? first.join()
                        : second.join();
        assertTrue(rootMessage(denied.failure()).contains(
                "population_group_owned_capacity_reached"
        ));
        assertEquals(1, canonicalAssignmentCount());
        assertEquals(
                new PopulationGroupCounts(1, 1, 0, 0),
                adapter.populationGroupIndex().counts(bucket())
        );
    }

    @Test
    void staleCanonicalEvidenceFailsWithoutSurvivingReservation()
            throws Exception {
        PopulationGroupAssignmentRequest stale = new PopulationGroupAssignmentRequest(
                PROFILE_A,
                0,
                "Other",
                LifecycleRevision.INITIAL,
                OWNER,
                "world-a",
                null,
                1,
                List.of(policy(2)),
                -4_000
        );
        OperationId operationId = operationId(30);

        OperationWorkflowResult result =
                adapter.populationGroupOperations().submit(
                        operationId,
                        new IdempotencyKey("group:stale"),
                        stale
                ).completion().toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);

        assertEquals(
                OperationWorkflowResult.Status.PREPARE_FAILED,
                result.status()
        );
        assertTrue(rootMessage(result.failure()).contains(
                "population_group_assignment_source_mismatch"
        ));
        assertEquals(0, reservationCount(operationId));
        assertEquals(0, canonicalAssignmentCount());
    }

    @Test
    void startupRecoveryReentersSameTypedAssignmentAdapter()
            throws Exception {
        OperationId operationId = operationId(40);
        PopulationGroupAssignmentRequest request =
                request(PROFILE_A, 2, -4_500);
        var prepared = adapter.publicOperations().engine().prepare(
                PopulationGroupAssignmentDefinition.INSTANCE,
                new OperationRequest<>(
                        operationId,
                        new IdempotencyKey("group:recover"),
                        request,
                        SqlitePopulationGroupAssignmentOperations
                                .FEATURE_SCOPE,
                        request.expectedLifecycleRevision(),
                        List.of(
                                OperationScope.profile(PROFILE_A),
                                OperationScope.owner(OWNER)
                        ),
                        request.requestedAtMs()
                ),
                new SqlitePopulationGroupAssignmentPreparation(request)
        ).completion().toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        assertInstanceOf(
                com.alechilles.alecstamework.persistence.kernel
                        .PersistenceTransactionResult.Committed.class,
                prepared
        );
        assertEquals(1, reservationCount(operationId));

        SqlitePublicRecoveryResult recovered = adapter.recover(
                boundaries(), "group-recovery"
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(SqlitePublicRecoveryResult.Status.COMPLETE, recovered.status());
        assertEquals(1, recovered.completedCount());
        assertEquals(0, reservationCount(operationId));
        assertEquals(1, canonicalAssignmentCount());
        assertEquals(
                new PopulationGroupCounts(1, 1, 0, 0),
                adapter.populationGroupIndex().counts(bucket())
        );
    }

    private SqliteDatabaseOperationCoordinator.Submission submit(
            OperationId operationId,
            ProfileId profileId,
            int limit
    ) {
        return adapter.populationGroupOperations().submit(
                operationId,
                new IdempotencyKey("group:" + operationId),
                request(profileId, limit, -4_000)
        );
    }

    private PopulationGroupAssignmentRequest request(
            ProfileId profileId,
            int limit,
            long requestedAtMs
    ) {
        return new PopulationGroupAssignmentRequest(
                profileId,
                0,
                "Mini",
                LifecycleRevision.INITIAL,
                OWNER,
                "world-a",
                null,
                1,
                List.of(policy(limit)),
                requestedAtMs
        );
    }

    private PopulationGroupPolicy policy(int limit) {
        return new PopulationGroupPolicy(
                "mod:mini",
                PopulationGroupScope.GLOBAL,
                limit,
                limit,
                1
        );
    }

    private PopulationGroupBucket bucket() {
        return new PopulationGroupBucket(
                OWNER,
                "mod:mini",
                PopulationGroupScope.GLOBAL,
                null
        );
    }

    private void createProfile(
            ProfileId profileId,
            int operationNumber
    ) throws Exception {
        CompanionProfileMutation.Create create =
                new CompanionProfileMutation.Create(
                        new CompanionIdentity(
                                profileId,
                                "Companion " + operationNumber,
                                "Mini",
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
                                OWNER,
                                LifecycleState.UNLOADED,
                                LifecycleLocation.none(),
                                LifecycleRevision.INITIAL,
                                null,
                                -10_000,
                                ReconciliationGeneration.INITIAL,
                                null,
                                "world-a"
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

    private PopulationGroupAssignment assignment(ProfileId profileId)
            throws Exception {
        PersistenceReadResult.Found<List<PopulationGroupAssignment>> found =
                assertInstanceOf(
                        PersistenceReadResult.Found.class,
                        adapter.populationGroupReader()
                                .findAllAssignments()
                                .toCompletableFuture()
                                .get(10, TimeUnit.SECONDS)
                );
        return found.value().stream()
                .filter(value -> profileId.equals(value.profileId()))
                .findFirst()
                .orElseThrow();
    }

    private long canonicalAssignmentCount() throws Exception {
        PersistenceReadResult.Found<List<PopulationGroupAssignment>> found =
                assertInstanceOf(
                        PersistenceReadResult.Found.class,
                        adapter.populationGroupReader()
                                .findAllAssignments()
                                .toCompletableFuture()
                                .get(10, TimeUnit.SECONDS)
                );
        return found.value().size();
    }

    private int reservationCount(OperationId operationId)
            throws Exception {
        try (var connection = connections.openReadConnection()) {
            return new SqlitePopulationGroupStore(connection)
                    .findReservations(operationId)
                    .size();
        }
    }

    private PublicPersistenceLiveBoundaries boundaries() {
        return new PublicPersistenceLiveBoundaries(
                (request, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.confirmed("capture")
                                .completed(),
                (request, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.confirmed("capture-release")
                                .completed(),
                (request, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.confirmed("restoration")
                                .completed(),
                (request, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.confirmed("coop-capture")
                                .completed(),
                (request, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.confirmed("coop-release")
                                .completed()
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

