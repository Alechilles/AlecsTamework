package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAdmission;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupBucket;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupReservation;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningOrigin;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningRecord;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.PreparedOperation;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SQLite contract tests for immutable normalized provisioning provenance. */
class SqliteProvisioningStoreTest {
    private static final ProvisioningOrigin ORIGIN =
            new ProvisioningOrigin("test:integration", "profile-a");
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000094");
    private static final OperationId OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000094");

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;

    @BeforeEach
    void setUp() {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("provisioning.db")
        );
        new SqliteSchemaV1Manager(connections, () -> -10_000)
                .initialize();
    }

    @Test
    void createsAndReplaysOneImmutableSignedTimeRecord() throws Exception {
        try (Connection connection = transaction()) {
            prepareOperation(connection);
            createProfile(connection);
            SqliteProvisioningStore store =
                    new SqliteProvisioningStore(connection);
            ProvisioningRecord record = record(-9_000);

            assertTrue(store.create(record).applied());
            assertTrue(store.create(record).applied());
            assertEquals(record, store.findByProfile(
                    ORIGIN.profileId()
            ).orElseThrow());
            assertEquals(record, store.findByOrigin(ORIGIN).orElseThrow());
            assertEquals(List.of(record), store.findAll());
            connection.commit();
        }
    }

    @Test
    void sameOriginCannotBeReinterpretedAfterCreation() throws Exception {
        try (Connection connection = transaction()) {
            prepareOperation(connection);
            createProfile(connection);
            SqliteProvisioningStore store =
                    new SqliteProvisioningStore(connection);
            assertTrue(store.create(record(-9_000)).applied());

            assertEquals(
                    PersistenceMutationStatus.CONFLICT,
                    store.create(record(-8_000)).status()
            );
            connection.commit();
        }
    }

    @Test
    void groupCapacityCanBeReservedBeforeTheProfileExists()
            throws Exception {
        try (Connection connection = transaction()) {
            prepareOperation(connection);
            PopulationGroupReservation reservation =
                    new PopulationGroupReservation(
                            OPERATION,
                            ORIGIN.profileId(),
                            null,
                            new PopulationGroupBucket(
                                    OWNER,
                                    "mod:mini",
                                    PopulationGroupScope.GLOBAL,
                                    null
                            ),
                            1,
                            0,
                            1,
                            1,
                            7,
                            -9_000
                    );

            PopulationGroupAdmission admission =
                    new SqlitePopulationGroupAdmissionStore(connection)
                            .reserve(reservation);

            assertEquals(
                    PopulationGroupAdmission.Status.ADMITTED,
                    admission.status()
            );
            assertNull(admission.reservation()
                    .expectedLifecycleRevision());
            connection.commit();
        }
    }

    private void prepareOperation(Connection connection) {
        assertTrue(new SqliteOperationStore(connection).prepare(
                new PreparedOperation(
                        OPERATION,
                        new IdempotencyKey("provisioning-test"),
                        new OperationKind("companion_provisioning"),
                        1,
                        "{}",
                        "provisioning",
                        null,
                        List.of(
                                OperationScope.profile(ORIGIN.profileId()),
                                OperationScope.owner(OWNER)
                        ),
                        -10_000
                )
        ).applied());
    }

    private void createProfile(Connection connection) {
        assertTrue(new SqliteCompanionIdentityStore(connection)
                .createProfile(new CompanionIdentity(
                        ORIGIN.profileId(),
                        "Provisioned",
                        "Mini",
                        null,
                        null,
                        "world-a",
                        -9_000,
                        -9_000,
                        -9_000,
                        0
                )).applied());
    }

    private ProvisioningRecord record(long createdAtMs) {
        return new ProvisioningRecord(
                ORIGIN.profileId(),
                ORIGIN,
                new UUID(0, 94),
                7,
                OPERATION,
                createdAtMs
        );
    }

    private Connection transaction() throws Exception {
        Connection connection = connections.openWriterConnection();
        connection.setAutoCommit(false);
        return connection;
    }
}
