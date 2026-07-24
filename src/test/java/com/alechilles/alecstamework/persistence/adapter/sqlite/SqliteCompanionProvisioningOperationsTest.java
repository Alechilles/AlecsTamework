package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDraft;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupBucket;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupCounts;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupMembership;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlan;
import com.alechilles.alecstamework.companion.population.OwnerPopulationScope;
import com.alechilles.alecstamework.companion.provisioning.CompanionProvisioningDefinition;
import com.alechilles.alecstamework.companion.provisioning.CompanionProvisioningRequest;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningOrigin;
import com.alechilles.alecstamework.persistence.control.PersistenceOperationAdmissionGate;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceFeatureRegistry;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Atomic grant, capacity, namespace, and idempotency tests for provisioning. */
class SqliteCompanionProvisioningOperationsTest {
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000096");
    private static final long NOW = -5_000;

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqlitePersistenceKernel kernel;
    private SqlitePublicPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("provisioning-operations.db")
        );
        new SqliteSchemaV1Manager(connections, () -> -10_000)
                .initialize();
        kernel = new SqlitePersistenceKernel(connections);
        adapter = new SqlitePublicPersistenceAdapter(
                PublicPersistenceFeatureRegistry.create(),
                kernel,
                PersistenceOperationAdmissionGate.allowAll(),
                () -> NOW,
                (claim, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.confirmed("refund")
                                .completed(),
                event -> {
                }
        );
    }

    @AfterEach
    void tearDown() {
        if (kernel != null) {
            kernel.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    void oneGrantCommitsEveryAuthorityAndProjectionAtomically()
            throws Exception {
        ProvisioningOrigin origin =
                new ProvisioningOrigin("test:one", "companion");
        CompanionProvisioningRequest request =
                request(origin, 1, true, 0);

        OperationWorkflowResult result = submit(
                1, request
        );

        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                result.status()
        );
        assertEquals(5, result.events().size());
        assertEquals(1, count("companion_profile"));
        assertEquals(1, count("companion_lifecycle"));
        assertEquals(1, count("provisioning_record"));
        assertEquals(1, count("population_group_classification"));
        assertEquals(1, count("command_roster_membership"));
        assertEquals(0, count("owner_population_reservation"));
        assertEquals(0, count("population_group_reservation"));
        assertEquals(request.lifecycle(), lifecycle(origin));
        assertEquals(
                origin,
                adapter.provisioningIndex()
                        .findByProfile(origin.profileId())
                        .orElseThrow()
                        .origin()
        );
        assertEquals(
                new PopulationGroupCounts(1, 0, 0, 0),
                adapter.populationGroupIndex().counts(bucket())
        );
        assertTrue(adapter.commandRosterIndex()
                .actionSnapshot().containsKey(origin.profileId()));
    }

    @Test
    void differentKeysCannotBypassOwnerOrGroupCapacity()
            throws Exception {
        CompanionProvisioningRequest first = request(
                new ProvisioningOrigin("test:capacity", "first"),
                1,
                false,
                null
        );
        CompanionProvisioningRequest second = request(
                new ProvisioningOrigin("test:capacity", "second"),
                1,
                false,
                null
        );

        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                submit(10, first).status()
        );
        OperationWorkflowResult denied = submit(11, second);

        assertEquals(
                OperationWorkflowResult.Status.PREPARE_FAILED,
                denied.status()
        );
        assertTrue(rootMessage(denied.failure()).contains(
                "capacity_reached"
        ));
        assertEquals(1, count("companion_profile"));
        assertEquals(1, count("provisioning_record"));
        assertEquals(0, count("owner_population_reservation"));
        assertEquals(0, count("population_group_reservation"));
    }

    @Test
    void sameCallerKeyInDifferentNamespacesCreatesDistinctProfiles()
            throws Exception {
        CompanionProvisioningRequest first = request(
                new ProvisioningOrigin("test:namespace-a", "same"),
                2,
                false,
                null
        );
        CompanionProvisioningRequest second = request(
                new ProvisioningOrigin("test:namespace-b", "same"),
                2,
                false,
                null
        );

        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                submit(20, first).status()
        );
        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                submit(21, second).status()
        );
        assertEquals(2, count("provisioning_record"));
        assertTrue(!first.origin().profileId().equals(
                second.origin().profileId()
        ));
    }

    @Test
    void replayUsesOneOperationAndOneCanonicalProfile()
            throws Exception {
        CompanionProvisioningRequest request = request(
                new ProvisioningOrigin("test:replay", "same"),
                1,
                false,
                null
        );
        OperationWorkflowResult first = submit(30, request);
        OperationWorkflowResult replay = submit(31, request);

        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                first.status()
        );
        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                replay.status()
        );
        assertEquals(first.operation(), replay.operation());
        assertEquals(1, count("operation_envelope"));
        assertEquals(1, count("companion_profile"));
        assertEquals(1, count("provisioning_record"));
    }

    @Test
    void recoveryResumesThePreparedGrantThroughTheSameAdapter()
            throws Exception {
        CompanionProvisioningRequest request = request(
                new ProvisioningOrigin("test:recovery", "profile"),
                1,
                false,
                null
        );
        OperationId operationId = operationId(40);
        SqliteOwnerPopulationParticipant population =
                new SqliteOwnerPopulationParticipant(
                        populationPlan(request)
                );
        SqlitePopulationGroupProvisioningParticipant groups =
                new SqlitePopulationGroupProvisioningParticipant(
                        request
                );

        Object prepared = adapter.publicOperations().engine().prepare(
                CompanionProvisioningDefinition.INSTANCE,
                new OperationRequest<>(
                        operationId,
                        request.origin().operationKey(),
                        request,
                        SqliteCompanionProvisioningOperations
                                .FEATURE_SCOPE,
                        null,
                        List.of(
                                OperationScope.profile(
                                        request.origin().profileId()
                                ),
                                OperationScope.owner(OWNER)
                        ),
                        request.requestedAtMs()
                ),
                PreparedOperationDetail.compose(
                        new SqliteCompanionProvisioningPreparation(
                                request
                        ),
                        population,
                        groups
                )
        ).completion().toCompletableFuture().get(
                10, TimeUnit.SECONDS
        );
        assertInstanceOf(
                PersistenceTransactionResult.Committed.class,
                prepared
        );
        assertEquals(2, count("owner_population_reservation"));
        assertEquals(1, count("population_group_reservation"));
        assertEquals(0, count("companion_profile"));

        SqlitePublicRecoveryResult recovered = adapter.recover(
                boundaries(), "provisioning-recovery"
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(
                SqlitePublicRecoveryResult.Status.COMPLETE,
                recovered.status()
        );
        assertEquals(1, recovered.completedCount());
        assertEquals(0, count("owner_population_reservation"));
        assertEquals(0, count("population_group_reservation"));
        assertEquals(1, count("provisioning_record"));
    }

    private OperationWorkflowResult submit(
            int number,
            CompanionProvisioningRequest request
    ) throws Exception {
        return adapter.provisioningOperations().submit(
                operationId(number), request
        ).completion().toCompletableFuture().get(
                10, TimeUnit.SECONDS
        );
    }

    private CompanionProvisioningRequest request(
            ProvisioningOrigin origin,
            int limit,
            boolean command,
            Integer rosterRevision
    ) {
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                origin.profileId(),
                OWNER,
                LifecycleState.PROVISIONED_DORMANT,
                LifecycleLocation.keyed(
                        LifecycleLocationKind.PROVISIONING,
                        origin.stableKey()
                ),
                LifecycleRevision.INITIAL,
                null,
                NOW,
                ReconciliationGeneration.INITIAL,
                null,
                "world-a"
        );
        CommandRosterMembershipDraft membership = command
                ? new CommandRosterMembershipDraft(
                        origin.commandSlotId(),
                        new CommandFamilyKey(OWNER, "summon"),
                        origin.profileId(),
                        "companions",
                        true,
                        null,
                        NOW
                )
                : null;
        return new CompanionProvisioningRequest(
                origin,
                new UUID(0, Math.abs(origin.stableKey().hashCode())),
                new CompanionIdentity(
                        origin.profileId(),
                        "Provisioned",
                        "Mini",
                        null,
                        null,
                        "world-a",
                        NOW,
                        NOW,
                        NOW,
                        0
                ),
                lifecycle,
                new PopulationGroupAssignment(
                        origin.profileId(),
                        "Mini",
                        List.of(new PopulationGroupMembership(
                                "mod:mini",
                                PopulationGroupScope.GLOBAL
                        )),
                        7,
                        0,
                        LifecycleRevision.INITIAL,
                        1,
                        NOW
                ),
                List.of(new PopulationGroupPolicy(
                        "mod:mini",
                        PopulationGroupScope.GLOBAL,
                        limit,
                        limit,
                        7
                )),
                limit,
                limit,
                membership,
                rosterRevision == null
                        ? null
                        : rosterRevision.longValue(),
                NOW
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

    private OwnerPopulationAdmissionPlan populationPlan(
            CompanionProvisioningRequest request
    ) {
        return new OwnerPopulationAdmissionPlan(
                request.origin().profileId(),
                null,
                List.of(
                        new OwnerPopulationAdmissionPlan.LimitIncrease(
                                OwnerPopulationScope.global(OWNER),
                                1,
                                request.globalOwnerLimit()
                        ),
                        new OwnerPopulationAdmissionPlan.LimitIncrease(
                                OwnerPopulationScope.perWorld(
                                        OWNER, "world-a"
                                ),
                                1,
                                request.perWorldOwnerLimit()
                        )
                )
        );
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
                                .LiveOperationResult.confirmed("restore")
                                .completed(),
                (request, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.confirmed("coop-in")
                                .completed(),
                (request, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.confirmed("coop-out")
                                .completed(),
                (request, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.confirmed("timed")
                                .completed(),
                (request, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.confirmed(
                                        "provisioning-activation"
                                )
                                .completed(),
                com.alechilles.alecstamework.companion.revival
                        .PaidRevivalBoundaries.unavailable()
        );
    }

    private CompanionLifecycle lifecycle(ProvisioningOrigin origin)
            throws Exception {
        try (Connection connection = readConnection()) {
            return new SqliteCompanionLifecycleStore(connection)
                    .findByProfile(origin.profileId())
                    .orElseThrow();
        }
    }

    private long count(String table) throws Exception {
        try (Connection connection = readConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(
                     "SELECT COUNT(*) FROM " + table
             )) {
            row.next();
            return row.getLong(1);
        }
    }

    private Connection readConnection() throws Exception {
        return connections.openReadConnection();
    }

    private OperationId operationId(int number) {
        return OperationId.parse(String.format(
                "40000000-0000-0000-0000-%012d",
                number
        ));
    }

    private String rootMessage(Throwable failure) {
        StringBuilder result = new StringBuilder();
        while (failure != null) {
            if (failure.getMessage() != null) {
                result.append(':').append(failure.getMessage());
            }
            failure = failure.getCause();
        }
        return result.toString();
    }
}
