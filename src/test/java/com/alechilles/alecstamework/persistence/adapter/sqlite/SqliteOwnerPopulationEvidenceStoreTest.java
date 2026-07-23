package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.population.PopulationEvidenceAssessment;
import com.alechilles.alecstamework.companion.population.PopulationEvidenceBatch;
import com.alechilles.alecstamework.companion.population.PopulationEvidenceObservation;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import java.nio.file.Path;
import java.sql.Connection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Durable sealed-evidence invariants for owner-population reconciliation. */
class SqliteOwnerPopulationEvidenceStoreTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000011");
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000011");
    private static final ReconciliationGeneration GENERATION =
            new ReconciliationGeneration(7);

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;

    @BeforeEach
    void setUp() {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("population-evidence.db")
        );
        assertTrue(new SqliteSchemaV1Manager(
                connections, () -> -20_000
        ).initialize() instanceof
                com.alechilles.alecstamework.persistence.kernel
                        .PersistenceTransactionResult.Committed<?>);
    }

    @Test
    void exactPositiveEvidenceIsActionableBeforeItsBatchSeals()
            throws Exception {
        try (Connection connection = transaction()) {
            createProfile(connection);
            SqliteOwnerPopulationEvidenceStore store =
                    new SqliteOwnerPopulationEvidenceStore(connection);
            PopulationEvidenceBatch.Key key = key(
                    PopulationEvidenceBatch.Source.LIVE,
                    GENERATION
            );
            PopulationEvidenceBatch batch =
                    PopulationEvidenceBatch.open(key, -19_000);
            PopulationEvidenceObservation observation = observation(
                    key, true, OWNER, " world-a "
            );

            assertTrue(store.open(batch).applied());
            assertTrue(store.open(batch).applied());
            assertTrue(store.observe(observation).applied());
            assertTrue(store.observe(observation).applied());
            assertEquals(
                    PopulationEvidenceAssessment.Status.PRESENT_MATCH,
                    store.assessPositive(
                            key, PROFILE, OWNER, "world-a"
                    ).status()
            );
            assertEquals(
                    PopulationEvidenceAssessment.Status.PRESENT_CONTRADICTION,
                    store.assessPositive(
                            key,
                            PROFILE,
                            OwnerId.parse(
                                    "30000000-0000-0000-0000-000000000012"
                            ),
                            "world-a"
                    ).status()
            );
            assertEquals(
                    PersistenceMutationStatus.CONFLICT,
                    store.observe(observation(
                            key, true, OWNER, "world-b"
                    )).status()
            );
            connection.commit();
        }
    }

    @Test
    void absenceRequiresBothSourcesSealedForTheSameBootWorldAndGeneration()
            throws Exception {
        try (Connection connection = transaction()) {
            createProfile(connection);
            SqliteOwnerPopulationEvidenceStore store =
                    new SqliteOwnerPopulationEvidenceStore(connection);
            PopulationEvidenceBatch.Key disk = key(
                    PopulationEvidenceBatch.Source.DISK,
                    GENERATION
            );
            PopulationEvidenceBatch.Key live = key(
                    PopulationEvidenceBatch.Source.LIVE,
                    GENERATION
            );
            openAndClose(
                    store,
                    disk,
                    PopulationEvidenceBatch.Status.SEALED,
                    null
            );
            openAndClose(
                    store,
                    key(
                            PopulationEvidenceBatch.Source.LIVE,
                            GENERATION.next()
                    ),
                    PopulationEvidenceBatch.Status.SEALED,
                    null
            );

            assertIncomplete(store);

            openAndClose(
                    store,
                    live,
                    PopulationEvidenceBatch.Status.FAILED,
                    "live_scan_failed"
            );
            assertIncomplete(store);
            connection.commit();
        }

        connections = new SqliteConnectionFactory(
                tempDir.resolve("population-evidence-sealed.db")
        );
        assertTrue(new SqliteSchemaV1Manager(
                connections, () -> -20_000
        ).initialize() instanceof
                com.alechilles.alecstamework.persistence.kernel
                        .PersistenceTransactionResult.Committed<?>);
        try (Connection connection = transaction()) {
            createProfile(connection);
            SqliteOwnerPopulationEvidenceStore store =
                    new SqliteOwnerPopulationEvidenceStore(connection);
            openAndClose(
                    store,
                    key(PopulationEvidenceBatch.Source.DISK, GENERATION),
                    PopulationEvidenceBatch.Status.SEALED,
                    null
            );
            openAndClose(
                    store,
                    key(PopulationEvidenceBatch.Source.LIVE, GENERATION),
                    PopulationEvidenceBatch.Status.SEALED,
                    null
            );

            assertEquals(
                    PopulationEvidenceAssessment.Status.ABSENT_PROVEN,
                    absence(store).status()
            );
            connection.commit();
        }
    }

    @Test
    void anyPositiveObservationDefeatsAnAbsenceClaim()
            throws Exception {
        try (Connection connection = transaction()) {
            createProfile(connection);
            SqliteOwnerPopulationEvidenceStore store =
                    new SqliteOwnerPopulationEvidenceStore(connection);
            PopulationEvidenceBatch.Key disk =
                    key(PopulationEvidenceBatch.Source.DISK, GENERATION);
            PopulationEvidenceBatch.Key live =
                    key(PopulationEvidenceBatch.Source.LIVE, GENERATION);
            assertTrue(store.open(
                    PopulationEvidenceBatch.open(disk, -19_000)
            ).applied());
            assertTrue(store.observe(
                    observation(disk, false, null, null)
            ).applied());
            assertTrue(store.close(
                    disk,
                    PopulationEvidenceBatch.Status.SEALED,
                    -18_000,
                    null
            ).applied());
            openAndClose(
                    store,
                    live,
                    PopulationEvidenceBatch.Status.SEALED,
                    null
            );

            PopulationEvidenceAssessment assessment = absence(store);
            assertEquals(
                    PopulationEvidenceAssessment.Status.PRESENT_INCOMPLETE,
                    assessment.status()
            );
            assertEquals(PROFILE, assessment.observation().profileId());
            assertFalse(assessment.actionable());
            connection.commit();
        }
    }

    @Test
    void closingASealedBatchPreventsLateObservations()
            throws Exception {
        try (Connection connection = transaction()) {
            createProfile(connection);
            SqliteOwnerPopulationEvidenceStore store =
                    new SqliteOwnerPopulationEvidenceStore(connection);
            PopulationEvidenceBatch.Key key =
                    key(PopulationEvidenceBatch.Source.DISK, GENERATION);
            openAndClose(
                    store,
                    key,
                    PopulationEvidenceBatch.Status.SEALED,
                    null
            );

            assertEquals(
                    PersistenceMutationStatus.PHASE_MISMATCH,
                    store.observe(
                            observation(key, true, OWNER, "world-a")
                    ).status()
            );
            connection.commit();
        }
    }

    private void assertIncomplete(
            SqliteOwnerPopulationEvidenceStore store
    ) {
        PopulationEvidenceAssessment assessment = absence(store);
        assertEquals(
                PopulationEvidenceAssessment.Status.INCOMPLETE,
                assessment.status()
        );
        assertFalse(assessment.actionable());
    }

    private PopulationEvidenceAssessment absence(
            SqliteOwnerPopulationEvidenceStore store
    ) {
        return store.assessAbsence(
                "boot-a", "world-a", GENERATION, PROFILE
        );
    }

    private void openAndClose(
            SqliteOwnerPopulationEvidenceStore store,
            PopulationEvidenceBatch.Key key,
            PopulationEvidenceBatch.Status status,
            String failure
    ) {
        assertTrue(store.open(
                PopulationEvidenceBatch.open(key, -19_000)
        ).applied());
        assertTrue(store.close(key, status, -18_000, failure).applied());
    }

    private PopulationEvidenceBatch.Key key(
            PopulationEvidenceBatch.Source source,
            ReconciliationGeneration generation
    ) {
        return new PopulationEvidenceBatch.Key(
                "boot-a", "world-a", generation, source
        );
    }

    private PopulationEvidenceObservation observation(
            PopulationEvidenceBatch.Key key,
            boolean ownerObserved,
            OwnerId ownerId,
            String ownerWorldKey
    ) {
        return new PopulationEvidenceObservation(
                key,
                PROFILE,
                ownerObserved,
                ownerId,
                ownerWorldKey,
                -18_500
        );
    }

    private Connection transaction() throws Exception {
        Connection connection = connections.openWriterConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private void createProfile(Connection connection) {
        assertTrue(new SqliteCompanionIdentityStore(connection).createProfile(
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
                )
        ).applied());
    }
}
