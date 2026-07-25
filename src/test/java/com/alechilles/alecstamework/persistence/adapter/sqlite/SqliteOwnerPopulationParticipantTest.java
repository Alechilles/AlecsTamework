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
import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlan;
import com.alechilles.alecstamework.companion.population.OwnerPopulationScope;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationTransition;
import com.alechilles.alecstamework.persistence.operation.PreparedOperation;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integration tests for population as a participant in the shared operation protocol. */
class SqliteOwnerPopulationParticipantTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000001");
    private static final OperationId OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000001");

    @TempDir
    Path tempDir;

    @Test
    void preparationAndRetirementShareTheOwningOperationTransactions()
            throws Exception {
        SqliteConnectionFactory connections = new SqliteConnectionFactory(
                tempDir.resolve("tamework-state.sqlite")
        );
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            createCanonicalProfile(transaction);
            OperationEnvelope operation = prepareOperation(transaction);
            SqliteOwnerPopulationParticipant participant =
                    new SqliteOwnerPopulationParticipant(plan());

            participant.prepare(transaction, operation);
            assertTrue(participant.matches(transaction, operation));
            assertEquals(
                    2,
                    transaction.population()
                            .findByOperation(OPERATION)
                            .size()
            );

            participant.decorate((current, envelope) -> {
                CompanionLifecycle source = current.lifecycles()
                        .findByProfile(PROFILE)
                        .orElseThrow();
                assertTrue(current.lifecycles().transition(
                        new LifecycleTransition(
                                LifecycleRevision.INITIAL,
                                null,
                                new CompanionLifecycle(
                                        PROFILE,
                                        OWNER,
                                        LifecycleState.UNLOADED,
                                        LifecycleLocation.none(),
                                        source.revision().next(),
                                        null,
                                        -9_000,
                                        source.lastReconciledGeneration(),
                                        null,
                                        "world-a"
                                )
                        )
                ).applied());
                return List.of();
            }).execute(transaction, operation);
            OperationEnvelope durable = transaction.operations().transition(
                    new OperationTransition(
                            OPERATION,
                            OperationPhase.PREPARED,
                            OperationPhase.DURABLE,
                            null,
                            null,
                            null,
                            -9_000
                    )
            ).value();

            assertTrue(participant.matches(transaction, durable));
            assertEquals(
                    1,
                    transaction.population().committedCount(
                            OwnerPopulationScope.global(OWNER)
                    )
            );
            assertEquals(
                    1,
                    transaction.population().committedCount(
                            OwnerPopulationScope.perWorld(OWNER, "world-a")
                    )
            );
            assertEquals(
                    0,
                    transaction.population().pendingCount(
                            OwnerPopulationScope.global(OWNER)
                    )
            );
            connection.commit();
        }
    }

    private void createCanonicalProfile(
            SqlitePersistenceTransactionContext transaction
    ) {
        assertTrue(transaction.identities().createProfile(
                new CompanionIdentity(
                        PROFILE,
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
        assertTrue(transaction.lifecycles().create(
                new CompanionLifecycle(
                        PROFILE,
                        null,
                        LifecycleState.UNRESOLVED,
                        LifecycleLocation.unresolved(),
                        LifecycleRevision.INITIAL,
                        null,
                        -10_000,
                        ReconciliationGeneration.INITIAL,
                        null,
                        null
                )
        ).applied());
    }

    private OperationEnvelope prepareOperation(
            SqlitePersistenceTransactionContext transaction
    ) {
        return transaction.operations().prepare(new PreparedOperation(
                OPERATION,
                new IdempotencyKey("population:participant"),
                new OperationKind("owner_population_test"),
                1,
                "{}",
                "owner_population",
                LifecycleRevision.INITIAL,
                List.of(
                        OperationScope.profile(PROFILE),
                        OperationScope.owner(OWNER)
                ),
                -9_000
        )).value();
    }

    private OwnerPopulationAdmissionPlan plan() {
        return new OwnerPopulationAdmissionPlan(
                PROFILE,
                LifecycleRevision.INITIAL,
                List.of(
                        new OwnerPopulationAdmissionPlan.LimitIncrease(
                                OwnerPopulationScope.global(OWNER),
                                1,
                                4
                        ),
                        new OwnerPopulationAdmissionPlan.LimitIncrease(
                                OwnerPopulationScope.perWorld(
                                        OWNER,
                                        "world-a"
                                ),
                                1,
                                2
                        )
                )
        );
    }
}

