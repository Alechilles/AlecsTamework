package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainBucket;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainReservation;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainScope;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationTransition;
import com.alechilles.alecstamework.persistence.operation.PreparedOperation;
import java.nio.file.Path;
import java.sql.Connection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protects the canonical same-connection source snapshot used by admission. */
class SqliteLifecycleAdmissionSourceReaderTest {
    private static final ProfileId PROFILE = ProfileId.parse(
            "20000000-0000-0000-0000-000000000881"
    );
    private static final OwnerId OWNER = OwnerId.parse(
            "30000000-0000-0000-0000-000000000881"
    );
    private static final String ENTITY =
            "40000000-0000-0000-0000-000000000881";

    @TempDir
    Path tempDir;

    @Test
    void readsCanonicalLifecycleAndIdentityRoleTogether() throws Exception {
        SqliteConnectionFactory connections = new SqliteConnectionFactory(
                tempDir.resolve("source-reader.sqlite")
        );
        assertTrue(new SqliteSchemaV2Manager(connections, () -> -100)
                .initialize() instanceof com.alechilles.alecstamework.persistence.kernel
                .PersistenceTransactionResult.Committed<?>);
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            assertTrue(new SqliteCompanionIdentityStore(connection).createProfile(
                    new CompanionIdentity(
                            PROFILE, "Canonical", "canonical-role", null,
                            null, "canonical-world", 0, 0, 0, 0
                    )
            ).applied());
            assertTrue(new SqliteCompanionLifecycleStore(connection).create(
                    new CompanionLifecycle(
                            PROFILE,
                            OWNER,
                            LifecycleState.ACTIVE,
                            LifecycleLocation.liveEntity(ENTITY, "canonical-world"),
                            LifecycleRevision.INITIAL,
                            null,
                            0,
                            ReconciliationGeneration.INITIAL,
                            null,
                            "canonical-world"
                    )
            ).applied());
            connection.commit();
        }

        try (SqliteReadExecutor reads = new SqliteReadExecutor(connections)) {
            PersistenceReadResult<SqliteLifecycleAdmissionSourceReader.SourceReadModel> result =
                    new SqliteLifecycleAdmissionSourceReader(reads)
                            .findByProfile(PROFILE)
                            .toCompletableFuture()
                            .join();
            var found = assertInstanceOf(
                    PersistenceReadResult.Found.class, result
            );
            var model = assertInstanceOf(
                    SqliteLifecycleAdmissionSourceReader.SourceReadModel.class,
                    found.value()
            );
            assertEquals("canonical-role", model.canonicalRoleId());
            assertEquals(OWNER, model.lifecycle().ownerId());
            assertEquals("canonical-world", model.lifecycle().location().worldKey());
            assertTrue(model.committedDomainRows().isEmpty());
        }
    }

    @Test
    void missingProfileIsAuthoritativeAbsence() throws Exception {
        SqliteConnectionFactory connections = new SqliteConnectionFactory(
                tempDir.resolve("source-reader-absent.sqlite")
        );
        assertTrue(new SqliteSchemaV2Manager(connections, () -> -100)
                .initialize() instanceof com.alechilles.alecstamework.persistence.kernel
                .PersistenceTransactionResult.Committed<?>);
        try (SqliteReadExecutor reads = new SqliteReadExecutor(connections)) {
            PersistenceReadResult<SqliteLifecycleAdmissionSourceReader.SourceReadModel> result =
                    new SqliteLifecycleAdmissionSourceReader(reads)
                            .findByProfile(PROFILE)
                            .toCompletableFuture()
                            .join();
            assertInstanceOf(PersistenceReadResult.Absent.class, result);
        }
    }

    @Test
    void identityWithoutLifecycleIsCorrupt() throws Exception {
        SqliteConnectionFactory connections = new SqliteConnectionFactory(
                tempDir.resolve("source-reader-corrupt.sqlite")
        );
        assertTrue(new SqliteSchemaV2Manager(connections, () -> -100)
                .initialize() instanceof com.alechilles.alecstamework.persistence.kernel
                .PersistenceTransactionResult.Committed<?>);
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            assertTrue(new SqliteCompanionIdentityStore(connection).createProfile(
                    new CompanionIdentity(
                            PROFILE, "Canonical", "canonical-role", null,
                            null, "canonical-world", 0, 0, 0, 0
                    )
            ).applied());
            connection.commit();
        }

        try (SqliteReadExecutor reads = new SqliteReadExecutor(connections)) {
            PersistenceReadResult<SqliteLifecycleAdmissionSourceReader.SourceReadModel> result =
                    new SqliteLifecycleAdmissionSourceReader(reads)
                            .findByProfile(PROFILE)
                            .toCompletableFuture()
                            .join();
            var failed = assertInstanceOf(
                    PersistenceReadResult.Failed.class, result
            );
            assertEquals(StorageFailureKind.CORRUPT, failed.failure().kind());
        }
    }

    @Test
    void pendingPopulationAdmissionIsUnavailableInsteadOfCorrupt() throws Exception {
        SqliteConnectionFactory connections = new SqliteConnectionFactory(
                tempDir.resolve("source-reader-pending.sqlite")
        );
        assertTrue(new SqliteSchemaV2Manager(connections, () -> -100)
                .initialize() instanceof com.alechilles.alecstamework.persistence.kernel
                .PersistenceTransactionResult.Committed<?>);
        OperationId pending = OperationId.parse(
                "50000000-0000-0000-0000-000000000881"
        );
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            assertTrue(new SqliteCompanionIdentityStore(connection).createProfile(
                    new CompanionIdentity(
                            PROFILE, "Canonical", "canonical-role", null,
                            null, "canonical-world", 0, 0, 0, 0
                    )
            ).applied());
            assertTrue(new SqliteCompanionLifecycleStore(connection).create(
                    new CompanionLifecycle(
                            PROFILE, OWNER, LifecycleState.ACTIVE,
                            LifecycleLocation.liveEntity(ENTITY, "canonical-world"),
                            LifecycleRevision.INITIAL, null, 0,
                            ReconciliationGeneration.INITIAL, null, "canonical-world"
                    )
            ).applied());
            assertTrue(new SqliteOperationStore(connection).prepare(
                    new PreparedOperation(
                            pending,
                            new IdempotencyKey("pending-domain-admission"),
                            new OperationKind("population_domain_admission"),
                            1,
                            "{}",
                            "population_domains",
                            null,
                            java.util.List.of(OperationScope.profile(PROFILE)),
                            -100
                    )
            ).applied());
            assertTrue(new SqliteOperationStore(connection).transition(
                    new OperationTransition(
                            pending,
                            OperationPhase.PREPARED,
                            OperationPhase.LIVE_APPLYING,
                            null,
                            null,
                            null,
                            -99
                    )
            ).applied());
            assertEquals(
                    com.alechilles.alecstamework.companion.population.domain
                            .PopulationDomainAdmission.Status.ADMITTED,
                    new SqlitePopulationDomainStore(connection).reserve(
                            new PopulationDomainReservation(
                                    pending,
                                    PROFILE,
                                    null,
                                    new PopulationDomainBucket(
                                            OWNER,
                                            "test-domain",
                                            PopulationDomainScope.GLOBAL,
                                            null
                                    ),
                                    0, 1, 1, 0, 5, 0, 0, 0, -100
                            )
                    ).status()
            );
            connection.commit();
        }

        try (SqliteReadExecutor reads = new SqliteReadExecutor(connections)) {
            PersistenceReadResult<SqliteLifecycleAdmissionSourceReader.SourceReadModel> result =
                    new SqliteLifecycleAdmissionSourceReader(reads)
                            .findByProfile(PROFILE)
                            .toCompletableFuture()
                            .join();
            var failed = assertInstanceOf(PersistenceReadResult.Failed.class, result);
            assertEquals(StorageFailureKind.UNAVAILABLE, failed.failure().kind());
            assertEquals("profile_domain_pending", failed.failure().code());
            assertTrue(failed.failure().retryable());
        }
    }
}
