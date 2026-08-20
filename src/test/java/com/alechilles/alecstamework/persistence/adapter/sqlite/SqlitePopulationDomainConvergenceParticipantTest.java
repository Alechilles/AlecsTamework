package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
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
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavior checks for exact retained domain-row convergence. */
class SqlitePopulationDomainConvergenceParticipantTest {
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
    void activeOwnedAndDeployableBecomesCapturedOwnedOnly() throws Exception {
        try (Connection connection = open("capture.sqlite")) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            PopulationDomainReservation source = seedCommittedSource(transaction);
            PopulationDomainConvergencePlan plan = plan(
                    transaction, LifecycleState.ACTIVE, OWNER, "world-one",
                    LifecycleState.CAPTURED, OWNER, "world-one", source
            );
            OperationEnvelope operation = prepareOperation(
                    transaction, "capture", LifecycleRevision.INITIAL
            );
            SqlitePopulationDomainConvergenceParticipant participant =
                    new SqlitePopulationDomainConvergenceParticipant(plan);

            participant.prepare(transaction, operation);
            participant.decorate((current, envelope) -> List.of())
                    .execute(transaction, operation);

            PopulationDomainReservation retained = transaction.populationDomains()
                    .findCommittedByProfile(PROFILE).getFirst();
            assertEquals(1, retained.ownedDelta());
            assertEquals(0, retained.deployableDelta());
            connection.commit();
        }
    }

    @Test
    void releaseRemovesBothDimensions() throws Exception {
        try (Connection connection = open("release.sqlite")) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            PopulationDomainReservation source = seedCommittedSource(transaction);
            PopulationDomainConvergencePlan plan = plan(
                    transaction, LifecycleState.ACTIVE, OWNER, "world-one",
                    LifecycleState.RELEASED, null, null, source
            );
            OperationEnvelope operation = prepareOperation(
                    transaction, "release", LifecycleRevision.INITIAL
            );
            SqlitePopulationDomainConvergenceParticipant participant =
                    new SqlitePopulationDomainConvergenceParticipant(plan);

            participant.prepare(transaction, operation);
            participant.decorate((current, envelope) -> List.of())
                    .execute(transaction, operation);

            assertTrue(transaction.populationDomains()
                    .findCommittedByProfile(PROFILE).isEmpty());
            connection.commit();
        }
    }

    @Test
    void changedSourceEvidenceFailsBeforeDelegatedWork() throws Exception {
        try (Connection connection = open("mismatch.sqlite")) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            PopulationDomainReservation source = seedCommittedSource(transaction);
            PopulationDomainReservation changed = new PopulationDomainReservation(
                    source.operationId(), source.profileId(),
                    source.expectedLifecycleRevision(), source.bucket(),
                    2, source.deployableDelta(), source.weight(),
                    source.snapshottedMaxOwned(),
                    source.snapshottedMaxDeployable(),
                    source.providerSnapshotRevision(),
                    source.managedConfigRevision(), source.policyRevision(),
                    source.createdAtMs()
            );
            PopulationDomainConvergencePlan plan =
                    new PopulationDomainConvergencePlan(
                            PROFILE, LifecycleRevision.INITIAL, OWNER, "world-one",
                            LifecycleState.ACTIVE, OWNER, "world-one",
                            LifecycleState.CAPTURED,
                            List.of(new PopulationDomainConvergencePlan.SourceRow(
                                    changed, 2, 0
                            ))
                    );
            OperationEnvelope operation = prepareOperation(
                    transaction, "mismatch", LifecycleRevision.INITIAL
            );
            SqlitePopulationDomainConvergenceParticipant participant =
                    new SqlitePopulationDomainConvergenceParticipant(plan);
            AtomicBoolean delegated = new AtomicBoolean();

            assertThrows(IllegalStateException.class, () ->
                    participant.prepare(transaction, operation));
            assertFalse(delegated.get());
            assertEquals(1, transaction.populationDomains()
                    .findCommittedByProfile(PROFILE).getFirst().ownedDelta());
            connection.rollback();
        }
    }

    @Test
    void delegatedFailureLeavesSourceRowsUnchanged() throws Exception {
        try (Connection connection = open("rollback.sqlite")) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            PopulationDomainReservation source = seedCommittedSource(transaction);
            PopulationDomainConvergencePlan plan = plan(
                    transaction, LifecycleState.ACTIVE, OWNER, "world-one",
                    LifecycleState.CAPTURED, OWNER, "world-one", source
            );
            OperationEnvelope operation = prepareOperation(
                    transaction, "rollback", LifecycleRevision.INITIAL
            );
            SqlitePopulationDomainConvergenceParticipant participant =
                    new SqlitePopulationDomainConvergenceParticipant(plan);

            participant.prepare(transaction, operation);
            assertThrows(IllegalStateException.class, () ->
                    participant.decorate((current, envelope) -> {
                        throw new IllegalStateException("live_failure");
                    }).execute(transaction, operation));
            PopulationDomainReservation retained = transaction.populationDomains()
                    .findCommittedByProfile(PROFILE).getFirst();
            assertEquals(1, retained.ownedDelta());
            assertEquals(1, retained.deployableDelta());
            connection.rollback();
        }
    }

    @Test
    void durableReplayAcceptsLaterLifecycleSupersession() throws Exception {
        try (Connection connection = open("supersession.sqlite")) {
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
            PopulationDomainReservation source = seedCommittedSource(transaction);
            PopulationDomainConvergencePlan capturePlan = plan(
                    transaction, LifecycleState.ACTIVE, OWNER, "world-one",
                    LifecycleState.CAPTURED, OWNER, "world-one", source
            );
            OperationEnvelope capture = prepareOperation(
                    transaction, "supersede-capture", LifecycleRevision.INITIAL
            );
            SqlitePopulationDomainConvergenceParticipant captureParticipant =
                    new SqlitePopulationDomainConvergenceParticipant(capturePlan);
            captureParticipant.prepare(transaction, capture);
            captureParticipant.decorate((current, envelope) -> {
                transitionLifecycle(
                        transaction, capture, LifecycleState.CAPTURED,
                        LifecycleLocation.keyed(
                                com.alechilles.alecstamework.companion.lifecycle
                                        .LifecycleLocationKind.CAPTURE_ITEM,
                                "capture-one"
                        )
                );
                return List.of();
            }).execute(transaction, capture);
            OperationEnvelope durableCapture = transition(
                    transaction, capture, OperationPhase.DURABLE, -70
            );
            assertTrue(captureParticipant.matches(transaction, durableCapture));

            PopulationDomainReservation captured = transaction.populationDomains()
                    .findCommittedByProfile(PROFILE).getFirst();
            PopulationDomainConvergencePlan releasePlan = planAt(
                    transaction, new LifecycleRevision(1),
                    LifecycleState.CAPTURED, OWNER, "world-one",
                    LifecycleState.RELEASED, null, null, captured
            );
            OperationEnvelope release = prepareOperation(
                    transaction, "supersede-release", new LifecycleRevision(1)
            );
            SqlitePopulationDomainConvergenceParticipant releaseParticipant =
                    new SqlitePopulationDomainConvergenceParticipant(releasePlan);
            releaseParticipant.prepare(transaction, release);
            releaseParticipant.decorate((current, envelope) -> {
                transitionLifecycle(
                        transaction, release, LifecycleState.RELEASED,
                        LifecycleLocation.none()
                );
                return List.of();
            }).execute(transaction, release);

            assertTrue(captureParticipant.matches(transaction, durableCapture));
            connection.rollback();
        }
    }

    @Test
    void unownedEmptySourceAllowsNewOwnerClaim() {
        PopulationDomainConvergencePlan plan =
                PopulationDomainConvergencePlanner.plan(
                        PROFILE, LifecycleRevision.INITIAL, null, null,
                        LifecycleState.ACTIVE, OWNER, "world-one",
                        LifecycleState.ACTIVE, List.of()
                );

        assertTrue(plan.sourceRows().isEmpty());
        assertFalse(plan.mutatesSourceRows());
    }

    private Connection open(String name) throws Exception {
        SqliteConnectionFactory connections = new SqliteConnectionFactory(
                tempDir.resolve(name)
        );
        assertTrue(new SqliteSchemaV2Manager(connections, () -> -10_000)
                .initialize() instanceof com.alechilles.alecstamework.persistence.kernel
                .PersistenceTransactionResult.Committed<?>);
        return connections.openWriterConnection();
    }

    private PopulationDomainReservation seedCommittedSource(
            SqlitePersistenceTransactionContext transaction
    ) {
        OperationEnvelope sourceOperation = prepareOperation(
                transaction, "source", LifecycleRevision.INITIAL
        );
        PopulationDomainReservation source = new PopulationDomainReservation(
                sourceOperation.operationId(), PROFILE,
                LifecycleRevision.INITIAL, BUCKET, 1, 1, 1,
                4, 4, 1, 1, 1, -90
        );
        SqlitePopulationDomainParticipant participant =
                new SqlitePopulationDomainParticipant(List.of(source), true);
        participant.prepare(transaction, sourceOperation);
        OperationEnvelope applying = transition(
                transaction, sourceOperation, OperationPhase.LIVE_APPLYING, -80
        );
        transition(transaction, applying, OperationPhase.DURABLE, -70);
        return source;
    }

    private PopulationDomainConvergencePlan plan(
            SqlitePersistenceTransactionContext transaction,
            LifecycleState sourceState,
            OwnerId sourceOwner,
            String sourceWorld,
            LifecycleState targetState,
            OwnerId targetOwner,
            String targetWorld,
            PopulationDomainReservation source
    ) {
        return planAt(
                transaction, LifecycleRevision.INITIAL, sourceState, sourceOwner,
                sourceWorld, targetState, targetOwner, targetWorld, source
        );
    }

    private PopulationDomainConvergencePlan planAt(
            SqlitePersistenceTransactionContext transaction,
            LifecycleRevision sourceRevision,
            LifecycleState sourceState,
            OwnerId sourceOwner,
            String sourceWorld,
            LifecycleState targetState,
            OwnerId targetOwner,
            String targetWorld,
            PopulationDomainReservation source
    ) {
        return PopulationDomainConvergencePlanner.plan(
                PROFILE, sourceRevision, sourceOwner, sourceWorld,
                sourceState, targetOwner, targetWorld, targetState,
                transaction.populationDomains().findCommittedByProfile(PROFILE)
        );
    }

    private OperationEnvelope prepareOperation(
            SqlitePersistenceTransactionContext transaction,
            String key,
            LifecycleRevision revision
    ) {
        return transaction.operations().prepare(new PreparedOperation(
                OperationId.parse("40000000-0000-0000-0000-0000000004"
                        + String.format("%02d", Math.abs(key.hashCode()) % 100)),
                new IdempotencyKey("convergence:" + key),
                new OperationKind("population_domain_convergence_test"),
                1, "{}", "population_domains", revision,
                List.of(OperationScope.profile(PROFILE), OperationScope.owner(OWNER)),
                -90
        )).value();
    }

    private OperationEnvelope transition(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            OperationPhase next,
            long at
    ) {
        return transaction.operations().transition(new OperationTransition(
                operation.operationId(), operation.phase(), next,
                null, null, null, at
        )).value();
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
                operation.operationId(),
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
