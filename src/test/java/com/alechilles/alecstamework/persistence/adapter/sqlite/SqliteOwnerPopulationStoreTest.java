package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmission;
import com.alechilles.alecstamework.companion.population.OwnerPopulationReservation;
import com.alechilles.alecstamework.companion.population.OwnerPopulationScope;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Transaction tests for canonical owner counts and envelope-attached reservations. */
class SqliteOwnerPopulationStoreTest {
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000001");
    private static final ProfileId PROFILE_A =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    private static final ProfileId PROFILE_B =
            ProfileId.parse("20000000-0000-0000-0000-000000000002");
    private static final ProfileId PROFILE_C =
            ProfileId.parse("20000000-0000-0000-0000-000000000003");
    private static final OperationKind KIND =
            new OperationKind("owner_population_test");

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;

    @BeforeEach
    void setUp() {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("tamework-state.sqlite")
        );
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
    }

    @Test
    void canonicalCountsIncludeOwnedDormantProfiles() throws Exception {
        try (Connection connection = transaction()) {
            createProfile(connection, PROFILE_A);
            createProfile(connection, PROFILE_B);
            createProfile(connection, PROFILE_C);
            createLifecycle(
                    connection,
                    PROFILE_A,
                    OWNER,
                    LifecycleState.ACTIVE,
                    LifecycleLocation.liveEntity("entity-a", "world-a"),
                    "world-a"
            );
            createLifecycle(
                    connection,
                    PROFILE_B,
                    OWNER,
                    LifecycleState.CAPTURED,
                    LifecycleLocation.keyed(
                            LifecycleLocationKind.CAPTURE_ITEM,
                            "capture-b"
                    ),
                    "world-a"
            );
            createLifecycle(
                    connection,
                    PROFILE_C,
                    OWNER,
                    LifecycleState.LOST,
                    LifecycleLocation.none(),
                    "world-b"
            );

            SqliteOwnerPopulationStore store =
                    new SqliteOwnerPopulationStore(connection);
            assertEquals(
                    3,
                    store.committedCount(OwnerPopulationScope.global(OWNER))
            );
            assertEquals(
                    2,
                    store.committedCount(
                            OwnerPopulationScope.perWorld(OWNER, "world-a")
                    )
            );
            assertEquals(
                    1,
                    store.committedCount(
                            OwnerPopulationScope.perWorld(OWNER, "world-b")
                    )
            );
            connection.commit();
        }
    }

    @Test
    void positiveReservationsPreventConcurrentOverAdmission() throws Exception {
        try (Connection connection = transaction()) {
            createProfile(connection, PROFILE_A);
            createProfile(connection, PROFILE_B);
            createProfile(connection, PROFILE_C);
            createLifecycle(
                    connection,
                    PROFILE_A,
                    OWNER,
                    LifecycleState.CAPTURED,
                    LifecycleLocation.keyed(
                            LifecycleLocationKind.CAPTURE_ITEM,
                            "capture-a"
                    ),
                    "world-a"
            );
            createLifecycle(
                    connection,
                    PROFILE_B,
                    null,
                    LifecycleState.UNRESOLVED,
                    LifecycleLocation.unresolved(),
                    null
            );
            createLifecycle(
                    connection,
                    PROFILE_C,
                    null,
                    LifecycleState.UNRESOLVED,
                    LifecycleLocation.unresolved(),
                    null
            );
            OperationId first = operation(
                    connection,
                    "40000000-0000-0000-0000-000000000001",
                    PROFILE_B
            );
            OperationId second = operation(
                    connection,
                    "40000000-0000-0000-0000-000000000002",
                    PROFILE_C
            );
            SqliteOwnerPopulationStore store =
                    new SqliteOwnerPopulationStore(connection);
            OwnerPopulationScope scope = OwnerPopulationScope.global(OWNER);

            assertEquals(
                    OwnerPopulationAdmission.Status.ADMITTED,
                    store.reserve(reservation(first, PROFILE_B, scope, 2))
                            .status()
            );
            assertEquals(
                    OwnerPopulationAdmission.Status.CAPACITY_REACHED,
                    store.reserve(reservation(second, PROFILE_C, scope, 2))
                            .status()
            );
            assertEquals(1, store.pendingCount(scope));
            assertEquals(1, store.findByOperation(first).size());
            assertTrue(store.findByOperation(second).isEmpty());
            connection.commit();
        }
    }

    @Test
    void exactReservationEvidenceIsIdempotentAndRetiredWithDurableWork()
            throws Exception {
        try (Connection connection = transaction()) {
            createProfile(connection, PROFILE_A);
            createLifecycle(
                    connection,
                    PROFILE_A,
                    null,
                    LifecycleState.UNRESOLVED,
                    LifecycleLocation.unresolved(),
                    null
            );
            OperationId operation = operation(
                    connection,
                    "40000000-0000-0000-0000-000000000003",
                    PROFILE_A
            );
            SqliteOwnerPopulationStore store =
                    new SqliteOwnerPopulationStore(connection);
            OwnerPopulationReservation global = reservation(
                    operation,
                    PROFILE_A,
                    OwnerPopulationScope.global(OWNER),
                    4
            );
            OwnerPopulationReservation world = reservation(
                    operation,
                    PROFILE_A,
                    OwnerPopulationScope.perWorld(OWNER, " world-a "),
                    2
            );

            assertTrue(store.reserve(global).admitted());
            assertTrue(store.reserve(global).admitted());
            assertTrue(store.reserve(world).admitted());
            assertEquals(
                    OwnerPopulationAdmission.Status.CONFLICT,
                    store.reserve(new OwnerPopulationReservation(
                            operation,
                            PROFILE_A,
                            LifecycleRevision.INITIAL,
                            global.scope(),
                            1,
                            5,
                            -9_000
                    )).status()
            );
            assertFalse(store.retireExact(operation, 1));
            assertTrue(store.retireExact(operation, 2));
            assertTrue(store.findByOperation(operation).isEmpty());
            connection.commit();
        }
    }

    @Test
    void scopeRequiresAWorldOnlyForPerWorldCapacity() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OwnerPopulationScope(
                        OwnerPopulationScope.Kind.GLOBAL,
                        OWNER,
                        "world-a"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> OwnerPopulationScope.perWorld(OWNER, " ")
        );
    }

    private Connection transaction() throws Exception {
        Connection connection = connections.openWriterConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private void createProfile(
            Connection connection,
            ProfileId profileId
    ) {
        assertTrue(new SqliteCompanionIdentityStore(connection).createProfile(
                new CompanionIdentity(
                        profileId,
                        "Companion",
                        "role",
                        null,
                        null,
                        "world-a",
                        -10_000,
                        -10_000,
                        -10_000,
                        0
                )
        ).applied());
    }

    private void createLifecycle(
            Connection connection,
            ProfileId profileId,
            OwnerId ownerId,
            LifecycleState state,
            LifecycleLocation location,
            String ownerWorldKey
    ) {
        assertTrue(new SqliteCompanionLifecycleStore(connection).create(
                new CompanionLifecycle(
                        profileId,
                        ownerId,
                        state,
                        location,
                        LifecycleRevision.INITIAL,
                        null,
                        -10_000,
                        ReconciliationGeneration.INITIAL,
                        null,
                        ownerWorldKey
                )
        ).applied());
    }

    private OperationId operation(
            Connection connection,
            String operationId,
            ProfileId profileId
    ) {
        OperationId id = OperationId.parse(operationId);
        assertTrue(new SqliteOperationStore(connection).prepare(
                new PreparedOperation(
                        id,
                        new IdempotencyKey("population:" + id),
                        KIND,
                        1,
                        "{}",
                        "owner_population",
                        LifecycleRevision.INITIAL,
                        List.of(
                                OperationScope.profile(profileId),
                                OperationScope.owner(OWNER)
                        ),
                        -9_000
                )
        ).applied());
        return id;
    }

    private OwnerPopulationReservation reservation(
            OperationId operationId,
            ProfileId profileId,
            OwnerPopulationScope scope,
            int limit
    ) {
        return new OwnerPopulationReservation(
                operationId,
                profileId,
                LifecycleRevision.INITIAL,
                scope,
                1,
                limit,
                -9_000
        );
    }
}

