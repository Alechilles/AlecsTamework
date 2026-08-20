package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmission;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainBucket;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainReservation;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainScope;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationTransition;
import com.alechilles.alecstamework.persistence.operation.PreparedOperation;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Behavior checks for weighted domain preparation, overbooking, and retirement. */
class SqlitePopulationDomainParticipantTest {
    private static final ProfileId PROFILE = ProfileId.parse(
            "20000000-0000-0000-0000-000000000401"
    );
    private static final ProfileId OTHER_PROFILE = ProfileId.parse(
            "20000000-0000-0000-0000-000000000402"
    );
    private static final OwnerId OWNER = OwnerId.parse(
            "30000000-0000-0000-0000-000000000401"
    );
    private static final OperationId OPERATION = OperationId.parse(
            "40000000-0000-0000-0000-000000000401"
    );
    private static final OperationId OTHER_OPERATION = OperationId.parse(
            "40000000-0000-0000-0000-000000000402"
    );

    @TempDir
    Path tempDir;

    @Test
    void participantRetiresOnlyItsExactReservationSet() throws Exception {
        SqliteConnectionFactory connections = new SqliteConnectionFactory(
                tempDir.resolve("tamework-state.sqlite")
        );
        assertTrue(new SqliteSchemaV2Manager(connections, () -> -10_000)
                .initialize() instanceof com.alechilles.alecstamework.persistence.kernel
                .PersistenceTransactionResult.Committed<?>);
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            OperationEnvelope operation = prepareOperation(
                    transaction, OPERATION, PROFILE
            );
            PopulationDomainReservation reservation = reservation(OPERATION, PROFILE);
            SqlitePopulationDomainParticipant participant =
                    new SqlitePopulationDomainParticipant(List.of(reservation));

            participant.prepare(transaction, operation);
            assertTrue(participant.matches(transaction, operation));
            assertEquals(1, transaction.populationDomains()
                    .findByOperation(OPERATION).size());

            participant.decorate((current, envelope) -> List.of())
                    .execute(transaction, operation);
            assertEquals(0, transaction.populationDomains()
                    .findByOperation(OPERATION).size());
            connection.commit();
        }
    }

    @Test
    void weightedPendingReservationsPreventOverbooking() throws Exception {
        SqliteConnectionFactory connections = new SqliteConnectionFactory(
                tempDir.resolve("weighted.sqlite")
        );
        assertTrue(new SqliteSchemaV2Manager(connections, () -> -10_000)
                .initialize() instanceof com.alechilles.alecstamework.persistence.kernel
                .PersistenceTransactionResult.Committed<?>);
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            prepareOperation(transaction, OPERATION, PROFILE);
            OperationEnvelope second = prepareOperation(
                    transaction, OTHER_OPERATION, OTHER_PROFILE
            );
            PopulationDomainReservation firstReservation = reservation(
                    OPERATION, PROFILE
            );
            PopulationDomainReservation secondReservation = reservation(
                    OTHER_OPERATION, OTHER_PROFILE
            );
            assertEquals(
                    PopulationDomainAdmission.Status.ADMITTED,
                    transaction.populationDomains().reserve(firstReservation)
                            .status()
            );
            assertEquals(
                    PopulationDomainAdmission.Status.OWNED_CAPACITY_REACHED,
                    transaction.populationDomains().reserve(secondReservation)
                            .status()
            );
            assertEquals(2, transaction.populationDomains()
                    .counts(firstReservation.bucket()).pendingOwned());
            assertThrows(IllegalStateException.class, () ->
                    new SqlitePopulationDomainParticipant(List.of(
                            secondReservation
                    )).prepare(transaction, second)
            );
            connection.rollback();
        }
    }

    @Test
    void retainedCommitIsWeightedAndIsolatedByDomain() throws Exception {
        SqliteConnectionFactory connections = new SqliteConnectionFactory(
                tempDir.resolve("committed-weighted.sqlite")
        );
        assertTrue(new SqliteSchemaV2Manager(connections, () -> -10_000)
                .initialize() instanceof com.alechilles.alecstamework.persistence.kernel
                .PersistenceTransactionResult.Committed<?>);
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            OperationEnvelope operation = prepareOperation(
                    transaction, OPERATION, PROFILE
            );
            PopulationDomainReservation retained = reservation(OPERATION, PROFILE);
            SqlitePopulationDomainParticipant participant =
                    new SqlitePopulationDomainParticipant(List.of(retained), true);
            participant.prepare(transaction, operation);
            OperationEnvelope applying = transaction.operations().transition(
                    new OperationTransition(
                            OPERATION, OperationPhase.PREPARED,
                            OperationPhase.LIVE_APPLYING, null, null, null, -8_000
                    )
            ).value();
            OperationEnvelope durable = transaction.operations().transition(
                    new OperationTransition(
                            OPERATION, applying.phase(), OperationPhase.DURABLE,
                            null, null, null, -7_000
                    )
            ).value();

            assertEquals(OperationPhase.DURABLE, durable.phase());
            assertEquals(2, transaction.populationDomains()
                    .counts(retained.bucket()).committedOwned());
            PopulationDomainBucket otherDomain = new PopulationDomainBucket(
                    OWNER, "runeteria:unrelated", PopulationDomainScope.GLOBAL, null
            );
            assertEquals(0, transaction.populationDomains()
                    .counts(otherDomain).committedOwned());
            assertEquals(0, transaction.populationDomains()
                    .counts(retained.bucket()).pendingOwned());
            connection.rollback();
        }
    }

    private OperationEnvelope prepareOperation(
            SqlitePersistenceTransactionContext transaction,
            OperationId operationId,
            ProfileId profileId
    ) {
        return transaction.operations().prepare(new PreparedOperation(
                operationId,
                new IdempotencyKey("domain:" + operationId),
                new OperationKind("population_domain_test"),
                1,
                "{}",
                "population_domains",
                LifecycleRevision.INITIAL,
                List.of(
                        OperationScope.profile(profileId),
                        OperationScope.owner(OWNER)
                ),
                -9_000
        )).value();
    }

    private PopulationDomainReservation reservation(
            OperationId operationId,
            ProfileId profileId
    ) {
        return new PopulationDomainReservation(
                operationId,
                profileId,
                LifecycleRevision.INITIAL,
                new PopulationDomainBucket(
                        OWNER,
                        "runeteria:husbandry_owned",
                        PopulationDomainScope.GLOBAL,
                        null
                ),
                1,
                1,
                2,
                2,
                2,
                11,
                12,
                13,
                -9_000
        );
    }
}
