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
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainConvergencePlan;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainConvergencePlanner;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainReservation;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainScope;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.operation.TimedDurableOperationWork;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Production coordinator evidence for retained-domain supersession replay. */
class SqlitePopulationDomainConvergenceCoordinatorTest {
    private static final ProfileId PROFILE = ProfileId.parse(
            "20000000-0000-0000-0000-000000000421"
    );
    private static final OwnerId OWNER = OwnerId.parse(
            "30000000-0000-0000-0000-000000000421"
    );
    private static final PopulationDomainBucket BUCKET = new PopulationDomainBucket(
            OWNER,
            "runeteria:husbandry_owned",
            PopulationDomainScope.PER_WORLD,
            "world-one"
    );

    @TempDir
    Path tempDir;

    @Test
    void productionCoordinatorReplaysOlderRetainedOperationAfterSupersession()
            throws Exception {
        SqliteConnectionFactory connections = new SqliteConnectionFactory(
                tempDir.resolve("coordinator-replay.sqlite")
        );
        assertTrue(new SqliteSchemaV2Manager(connections, () -> -10_000)
                .initialize() instanceof com.alechilles.alecstamework.persistence.kernel
                .PersistenceTransactionResult.Committed<?>);
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            transaction.identities().createProfile(new CompanionIdentity(
                    PROFILE, "Companion", "role", null, null, "world-one",
                    -100, -100, -100, 0
            ));
            transaction.lifecycles().create(new CompanionLifecycle(
                    PROFILE, OWNER, LifecycleState.ACTIVE,
                    LifecycleLocation.liveEntity("npc-one", "world-one"),
                    LifecycleRevision.INITIAL, null, -100,
                    ReconciliationGeneration.INITIAL, null, "world-one"
            ));
            connection.commit();
        }

        SqliteSingleWriter writer = new SqliteSingleWriter(connections);
        SqliteReadExecutor reads = new SqliteReadExecutor(connections);
        try {
            SqliteUnitOfWorkRunner units = new SqliteUnitOfWorkRunner(writer, reads);
            OperationDefinition<String> definition = new OperationDefinition<>() {
                private final OperationKind kind = new OperationKind(
                        "c1_convergence_test"
                );

                @Override
                public OperationKind kind() {
                    return kind;
                }

                @Override
                public int payloadVersion() {
                    return 1;
                }

                @Override
                public Class<String> payloadType() {
                    return String.class;
                }

                @Override
                public String encode(String payload) {
                    return "{}";
                }

                @Override
                public String decode(String payloadJson) {
                    return "payload";
                }
            };
            SqliteOperationEngine engine = new SqliteOperationEngine(
                    new OperationDefinitionRegistry(List.of(definition)), units
            );
            SqliteOperationPublisher publisher = new SqliteOperationPublisher(
                    engine,
                    new SqliteOperationEvidenceReader(reads),
                    new ProjectionCoordinator(
                            new SqliteProjectionGateway(reads, units),
                            ProjectionRetryPolicy.DEFAULT,
                            () -> -10_000
                    ),
                    () -> -10_000
            );
            SqliteLiveOperationCoordinator coordinator =
                    new SqliteLiveOperationCoordinator(
                            engine, publisher, () -> -10_000
                    );
            OperationId firstId = OperationId.parse(
                    "40000000-0000-0000-0000-000000005001"
            );
            OperationRequest<String> firstRequest = new OperationRequest<>(
                    firstId, new IdempotencyKey("c1:first"), "payload",
                    "c1_convergence", LifecycleRevision.INITIAL,
                    List.of(OperationScope.profile(PROFILE), OperationScope.owner(OWNER)),
                    -90
            );
            PopulationDomainReservation target = new PopulationDomainReservation(
                    firstId, PROFILE, LifecycleRevision.INITIAL, BUCKET,
                    1, 1, 1, 4, 4, 1, 1, 1, -80
            );
            SqlitePopulationDomainParticipant retained =
                    new SqlitePopulationDomainParticipant(List.of(target), true);
            AtomicInteger liveCalls = new AtomicInteger();
            TimedDurableOperationWork<String> firstDurable =
                    (transaction, operation, payload, committedAtMs) ->
                            retained.decorate((current, envelope) -> List.of(
                                    new ProjectionEventDraft(
                                            operation.operationId(),
                                            new ProjectionEventType("c1_first"),
                                            PROFILE.toString(), 1, 1, "{}",
                                            committedAtMs
                                    )
                            )).execute(transaction, operation);
            OperationWorkflowResult first = coordinator.execute(
                    definition,
                    firstRequest,
                    retained,
                    (payload, operation) -> {
                        liveCalls.incrementAndGet();
                        return LiveOperationResult.confirmed("first_live").completed();
                    },
                    firstDurable,
                    List.of(),
                    "c1_first"
            ).completion().toCompletableFuture().get();
            assertEquals(OperationWorkflowResult.Status.PUBLISHED, first.status());

            PopulationDomainConvergencePlan supersedingPlan;
            try (Connection connection = connections.openWriterConnection()) {
                SqlitePersistenceTransactionContext transaction =
                        new SqlitePersistenceTransactionContext(connection);
                supersedingPlan = PopulationDomainConvergencePlanner.plan(
                        PROFILE, LifecycleRevision.INITIAL, OWNER, "world-one",
                        LifecycleState.ACTIVE, OWNER, "world-one",
                        LifecycleState.CAPTURED,
                        transaction.populationDomains().profileEvidence(
                                PROFILE, null
                        ).committed()
                );
            }
            OperationId secondId = OperationId.parse(
                    "40000000-0000-0000-0000-000000005002"
            );
            OperationRequest<String> secondRequest = new OperationRequest<>(
                    secondId, new IdempotencyKey("c1:second"), "payload",
                    "c1_convergence", LifecycleRevision.INITIAL,
                    List.of(OperationScope.profile(PROFILE), OperationScope.owner(OWNER)),
                    -70
            );
            SqlitePopulationDomainConvergenceParticipant convergence =
                    new SqlitePopulationDomainConvergenceParticipant(
                            supersedingPlan
                    );
            AtomicInteger secondLiveCalls = new AtomicInteger();
            TimedDurableOperationWork<String> secondDurable =
                    (transaction, operation, payload, committedAtMs) ->
                            convergence.decorate((current, envelope) -> {
                                transitionLifecycle(
                                        transaction, operation,
                                        LifecycleState.CAPTURED,
                                        LifecycleLocation.keyed(
                                                com.alechilles.alecstamework.companion.lifecycle
                                                        .LifecycleLocationKind.CAPTURE_ITEM,
                                                "capture-two"
                                        )
                                );
                                return List.of(new ProjectionEventDraft(
                                        operation.operationId(),
                                        new ProjectionEventType("c1_second"),
                                        PROFILE.toString(), 1, 1, "{}",
                                        committedAtMs
                                ));
                            }).execute(transaction, operation);
            OperationWorkflowResult second = coordinator.execute(
                    definition,
                    secondRequest,
                    convergence,
                    (payload, operation) -> {
                        secondLiveCalls.incrementAndGet();
                        return LiveOperationResult.confirmed("second_live").completed();
                    },
                    secondDurable,
                    List.of(),
                    "c1_second"
            ).completion().toCompletableFuture().get();
            assertEquals(OperationWorkflowResult.Status.PUBLISHED, second.status());

            OperationId thirdId = OperationId.parse(
                    "40000000-0000-0000-0000-000000005003"
            );
            OperationRequest<String> thirdRequest = new OperationRequest<>(
                    thirdId, new IdempotencyKey("c1:third"), "payload",
                    "c1_convergence", new LifecycleRevision(1),
                    List.of(OperationScope.profile(PROFILE), OperationScope.owner(OWNER)),
                    -50
            );
            PopulationDomainReservation reactivationTarget =
                    new PopulationDomainReservation(
                            thirdId, PROFILE, new LifecycleRevision(1), BUCKET,
                            0, 1, 1, 4, 4, 1, 1, 1, -40
                    );
            SqlitePopulationDomainParticipant reactivationAdmission =
                    new SqlitePopulationDomainParticipant(
                            List.of(reactivationTarget), true
                    );
            PopulationDomainConvergencePlan reactivationPlan;
            try (Connection connection = connections.openWriterConnection()) {
                SqlitePersistenceTransactionContext transaction =
                        new SqlitePersistenceTransactionContext(connection);
                reactivationPlan = PopulationDomainConvergencePlanner.plan(
                        PROFILE, new LifecycleRevision(1), OWNER, "world-one",
                        LifecycleState.CAPTURED, OWNER, "world-one",
                        LifecycleState.ACTIVE,
                        transaction.populationDomains().profileEvidence(
                                PROFILE, null
                        ).committed(),
                        List.of(reactivationTarget)
                );
            }
            SqlitePopulationDomainConvergenceParticipant reactivation =
                    new SqlitePopulationDomainConvergenceParticipant(
                            reactivationPlan
                    );
            TimedDurableOperationWork<String> thirdDurable =
                    (transaction, operation, payload, committedAtMs) ->
                            reactivation.decorate(
                                    reactivationAdmission.decorate(
                                            (current, envelope) -> {
                                                transitionLifecycle(
                                                        transaction, operation,
                                                        LifecycleState.ACTIVE,
                                                        LifecycleLocation.liveEntity(
                                                                "npc-one", "world-one"
                                                        )
                                                );
                                                return List.of(new ProjectionEventDraft(
                                                        operation.operationId(),
                                                        new ProjectionEventType("c1_third"),
                                                        PROFILE.toString(), 1, 1, "{}",
                                                        committedAtMs
                                                ));
                                            }
                                    )
                            ).execute(transaction, operation);
            OperationWorkflowResult third = coordinator.execute(
                    definition,
                    thirdRequest,
                    PreparedOperationDetail.compose(
                            reactivationAdmission, reactivation
                    ),
                    (payload, operation) ->
                            LiveOperationResult.confirmed("third_live").completed(),
                    thirdDurable,
                    List.of(),
                    "c1_third"
            ).completion().toCompletableFuture().get();
            assertEquals(OperationWorkflowResult.Status.PUBLISHED, third.status());

            try (Connection connection = connections.openWriterConnection()) {
                SqlitePersistenceTransactionContext transaction =
                        new SqlitePersistenceTransactionContext(connection);
                assertEquals(1, transaction.populationDomains()
                        .counts(BUCKET).committedOwned());
                assertEquals(1, transaction.populationDomains()
                        .counts(BUCKET).committedDeployable());
            }
            OperationWorkflowResult replay = coordinator.execute(
                    definition,
                    secondRequest,
                    convergence,
                    (payload, operation) -> {
                        secondLiveCalls.incrementAndGet();
                        return LiveOperationResult.confirmed("must_not_run").completed();
                    },
                    secondDurable,
                    List.of(),
                    "c1_second_replay"
            ).completion().toCompletableFuture().get();
            assertEquals(OperationWorkflowResult.Status.PUBLISHED, replay.status());
            assertEquals(1, liveCalls.get());
            assertEquals(1, secondLiveCalls.get());
        } finally {
            writer.shutdown(Duration.ofSeconds(5));
            reads.shutdown(Duration.ofSeconds(5));
        }
    }

    private void transitionLifecycle(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            LifecycleState state,
            com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation location
    ) {
        CompanionLifecycle current = transaction.lifecycles()
                .findByProfile(PROFILE).orElseThrow();
        CompanionLifecycle next = new CompanionLifecycle(
                PROFILE,
                state == LifecycleState.RELEASED ? null : OWNER,
                state,
                location,
                current.revision().next(),
                null,
                -60,
                current.lastReconciledGeneration(),
                null,
                state == LifecycleState.RELEASED ? null : "world-one"
        );
        assertTrue(transaction.lifecycles().transition(
                new com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition(
                        current.revision(), current.activeOperationId(), next
                )
        ).applied());
    }
}
