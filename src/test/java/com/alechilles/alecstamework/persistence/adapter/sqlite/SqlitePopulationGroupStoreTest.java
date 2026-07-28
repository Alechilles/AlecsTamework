package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAdmission;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupBucket;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupCounts;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupMembership;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupReservation;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.PreparedOperation;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Normalized membership, canonical joins, lag, and reservation tests. */
class SqlitePopulationGroupStoreTest {
    private static final ProfileId PROFILE_A =
            ProfileId.parse("20000000-0000-0000-0000-000000000031");
    private static final ProfileId PROFILE_B =
            ProfileId.parse("20000000-0000-0000-0000-000000000032");
    private static final ProfileId PROFILE_C =
            ProfileId.parse("20000000-0000-0000-0000-000000000033");
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000031");
    private static final OperationKind KIND =
            new OperationKind("population_group_test");

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;

    @BeforeEach
    void setUp() {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("population-groups.db")
        );
        new SqliteSchemaV1Manager(connections, () -> -20_000)
                .initialize();
    }

    @Test
    void completeAssignmentIsSortedRevisionFencedAndCanBeEmpty()
            throws Exception {
        try (Connection connection = transaction()) {
            createProfile(connection, PROFILE_A, "Mini");
            SqlitePopulationGroupStore store =
                    new SqlitePopulationGroupStore(connection);
            PopulationGroupAssignment first = assignment(
                    PROFILE_A,
                    List.of(
                            membership("mod:soul", PopulationGroupScope.GLOBAL),
                            membership("mod:dragon", PopulationGroupScope.GLOBAL)
                    ),
                    1,
                    1
            );

            assertTrue(store.replaceAssignment(null, first).applied());
            assertEquals(
                    List.of("mod:dragon", "mod:soul"),
                    store.findAssignment(PROFILE_A).orElseThrow()
                            .memberships().stream()
                            .map(PopulationGroupMembership::groupId)
                            .toList()
            );
            assertTrue(store.replaceAssignment(null, first).applied());
            PopulationGroupAssignment empty = assignment(
                    PROFILE_A, List.of(), 2, 2
            );
            assertTrue(store.replaceAssignment(1L, empty).applied());
            assertTrue(
                    store.findAssignment(PROFILE_A)
                            .orElseThrow()
                            .memberships()
                            .isEmpty()
            );
            connection.commit();
        }
    }

    @Test
    void canonicalLifecycleChangesCountsWithoutRewritingMembership()
            throws Exception {
        try (Connection connection = transaction()) {
            createProfile(connection, PROFILE_A, "Mini");
            SqlitePopulationGroupStore store =
                    new SqlitePopulationGroupStore(connection);
            assertTrue(store.replaceAssignment(
                    null,
                    assignment(
                            PROFILE_A,
                            List.of(membership(
                                    "mod:soul",
                                    PopulationGroupScope.GLOBAL
                            )),
                            1,
                            1
                    )
            ).applied());
            PopulationGroupBucket bucket = new PopulationGroupBucket(
                    OWNER,
                    "mod:soul",
                    PopulationGroupScope.GLOBAL,
                    null
            );
            assertEquals(
                    new PopulationGroupCounts(1, 1, 0, 0),
                    store.counts(bucket)
            );
            CompanionLifecycle source =
                    new SqliteCompanionLifecycleStore(connection)
                            .findByProfile(PROFILE_A)
                            .orElseThrow();
            CompanionLifecycle released = new CompanionLifecycle(
                    source.profileId(),
                    source.ownerId(),
                    LifecycleState.RELEASED,
                    LifecycleLocation.none(),
                    source.revision().next(),
                    null,
                    -19_000,
                    source.lastReconciledGeneration(),
                    null,
                    source.ownerWorldKey()
            );
            assertTrue(new SqliteCompanionLifecycleStore(connection)
                    .transition(new LifecycleTransition(
                            source.revision(), null, released
                    )).applied());

            assertEquals(
                    new PopulationGroupCounts(0, 0, 0, 0),
                    store.counts(bucket)
            );
            assertEquals(
                    List.of("mod:soul"),
                    store.findAssignment(PROFILE_A).orElseThrow()
                            .memberships().stream()
                            .map(PopulationGroupMembership::groupId)
                            .toList()
            );
            connection.commit();
        }
    }

    @Test
    void roleMismatchAndMissingPerWorldOwnerBucketAreDetectableLag()
            throws Exception {
        try (Connection connection = transaction()) {
            createProfile(connection, PROFILE_A, "Mini");
            SqlitePopulationGroupStore store =
                    new SqlitePopulationGroupStore(connection);
            assertTrue(store.replaceAssignment(
                    null,
                    assignment(
                            PROFILE_A,
                            List.of(membership(
                                    "mod:soul",
                                    PopulationGroupScope.PER_WORLD
                            )),
                            1,
                            1
                    )
            ).applied());
            assertTrue(store.findStaleProfiles().isEmpty());
            CompanionLifecycle lifecycle =
                    new SqliteCompanionLifecycleStore(connection)
                            .findByProfile(PROFILE_A).orElseThrow();
            assertTrue(new SqliteCompanionLifecycleStore(connection)
                    .transition(new LifecycleTransition(
                            lifecycle.revision(),
                            null,
                            new CompanionLifecycle(
                                    lifecycle.profileId(),
                                    lifecycle.ownerId(),
                                    lifecycle.state(),
                                    lifecycle.location(),
                                    lifecycle.revision().next(),
                                    null,
                                    lifecycle.stateChangedAtMs(),
                                    lifecycle.lastReconciledGeneration(),
                                    null,
                                    null
                            )
                    )).applied());
            assertEquals(List.of(PROFILE_A), store.findStaleProfiles());
            CompanionIdentity source =
                    new SqliteCompanionIdentityStore(connection)
                            .findProfile(PROFILE_A).orElseThrow();
            assertTrue(new SqliteCompanionIdentityStore(connection)
                    .updateProfile(new CompanionIdentity(
                            source.profileId(),
                            source.displayName(),
                            "Other",
                            source.metadataJson(),
                            source.metadataHash(),
                            source.lastKnownWorldKey(),
                            source.createdAtMs(),
                            -19_000,
                            source.lastActiveAtMs(),
                            1
                    ), 0).applied());

            assertEquals(List.of(PROFILE_A), store.findStaleProfiles());
            connection.commit();
        }
    }

    @Test
    void positivePendingReservationsPreventGroupOverAdmission()
            throws Exception {
        try (Connection connection = transaction()) {
            createProfile(connection, PROFILE_A, "Mini");
            createProfile(connection, PROFILE_B, "Mini");
            createProfile(connection, PROFILE_C, "Mini");
            SqlitePopulationGroupStore store =
                    new SqlitePopulationGroupStore(connection);
            assertTrue(store.replaceAssignment(
                    null,
                    assignment(
                            PROFILE_A,
                            List.of(membership(
                                    "mod:soul",
                                    PopulationGroupScope.GLOBAL
                            )),
                            1,
                            1
                    )
            ).applied());
            PopulationGroupBucket bucket = new PopulationGroupBucket(
                    OWNER,
                    "mod:soul",
                    PopulationGroupScope.GLOBAL,
                    null
            );
            OperationId first = operation(connection, PROFILE_B, 41);
            OperationId second = operation(connection, PROFILE_C, 42);

            assertEquals(
                    PopulationGroupAdmission.Status.ADMITTED,
                    store.reserve(reservation(
                            first, PROFILE_B, bucket, 2
                    )).status()
            );
            assertEquals(
                    PopulationGroupAdmission.Status.OWNED_CAPACITY_REACHED,
                    store.reserve(reservation(
                            second, PROFILE_C, bucket, 2
                    )).status()
            );
            assertEquals(
                    new PopulationGroupCounts(1, 1, 1, 1),
                    store.counts(bucket)
            );
            assertTrue(store.retireExact(first, 1));
            assertTrue(store.findReservations(first).isEmpty());
            connection.commit();
        }
    }

    @Test
    void activeOnlyReservationProtectsRosterToLiveTransition()
            throws Exception {
        try (Connection connection = transaction()) {
            createProfile(connection, PROFILE_A, "Mini");
            createProfile(connection, PROFILE_B, "Mini");
            SqlitePopulationGroupStore store =
                    new SqlitePopulationGroupStore(connection);
            assertTrue(store.replaceAssignment(
                    null,
                    assignment(
                            PROFILE_A,
                            List.of(membership(
                                    "mod:soul",
                                    PopulationGroupScope.GLOBAL
                            )),
                            1,
                            1
                    )
            ).applied());
            PopulationGroupBucket bucket = new PopulationGroupBucket(
                    OWNER,
                    "mod:soul",
                    PopulationGroupScope.GLOBAL,
                    null
            );
            OperationId operation = operation(connection, PROFILE_B, 43);
            PopulationGroupReservation activeOnly =
                    new PopulationGroupReservation(
                            operation,
                            PROFILE_B,
                            LifecycleRevision.INITIAL,
                            bucket,
                            0,
                            1,
                            2,
                            2,
                            1,
                            -18_000
                    );

            assertEquals(
                    PopulationGroupAdmission.Status.ADMITTED,
                    store.reserve(activeOnly).status()
            );
            assertEquals(
                    new PopulationGroupCounts(1, 1, 0, 1),
                    store.counts(bucket)
            );
            connection.commit();
        }
    }

    private PopulationGroupAssignment assignment(
            ProfileId profileId,
            List<PopulationGroupMembership> memberships,
            long policyRevision,
            long assignmentRevision
    ) {
        return new PopulationGroupAssignment(
                profileId,
                "Mini",
                memberships,
                policyRevision,
                0,
                LifecycleRevision.INITIAL,
                assignmentRevision,
                -19_000
        );
    }

    private PopulationGroupMembership membership(
            String groupId,
            PopulationGroupScope scope
    ) {
        return new PopulationGroupMembership(groupId, scope);
    }

    private PopulationGroupReservation reservation(
            OperationId operationId,
            ProfileId profileId,
            PopulationGroupBucket bucket,
            int limit
    ) {
        return new PopulationGroupReservation(
                operationId,
                profileId,
                LifecycleRevision.INITIAL,
                bucket,
                1,
                1,
                limit,
                limit,
                1,
                -18_000
        );
    }

    private OperationId operation(
            Connection connection,
            ProfileId profileId,
            int number
    ) {
        OperationId operationId = operationId(number);
        assertTrue(new SqliteOperationStore(connection).prepare(
                new PreparedOperation(
                        operationId,
                        new IdempotencyKey("group:" + number),
                        KIND,
                        1,
                        "{}",
                        "population_groups",
                        LifecycleRevision.INITIAL,
                        List.of(
                                OperationScope.profile(profileId),
                                OperationScope.owner(OWNER)
                        ),
                        -18_000
                )
        ).applied());
        return operationId;
    }

    private void createProfile(
            Connection connection,
            ProfileId profileId,
            String roleId
    ) {
        assertTrue(new SqliteCompanionIdentityStore(connection)
                .createProfile(new CompanionIdentity(
                        profileId,
                        "Companion",
                        roleId,
                        null,
                        null,
                        "world-a",
                        -20_000,
                        -20_000,
                        -20_000,
                        0
                )).applied());
        assertTrue(new SqliteCompanionLifecycleStore(connection)
                .create(new CompanionLifecycle(
                        profileId,
                        OWNER,
                        LifecycleState.UNLOADED,
                        LifecycleLocation.none(),
                        LifecycleRevision.INITIAL,
                        null,
                        -20_000,
                        ReconciliationGeneration.INITIAL,
                        null,
                        "world-a"
                )).applied());
    }

    private Connection transaction() throws Exception {
        Connection connection = connections.openWriterConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private OperationId operationId(int number) {
        return OperationId.parse(String.format(
                "40000000-0000-0000-0000-%012d",
                number
        ));
    }
}

