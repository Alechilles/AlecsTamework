package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.capture.CaptureTameAndLinkTestFixtures;
import com.alechilles.alecstamework.companion.capture.CaptureAttemptResolutionEventCodec;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureDefinition;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipChangeCodec;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseChangeCodec;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChangeCodec;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentChangeCodec;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChangeCodec;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.alechilles.alecstamework.companion.capture.CaptureTameAndLinkTestFixtures.OPERATION;
import static com.alechilles.alecstamework.companion.capture.CaptureTameAndLinkTestFixtures.PROFILE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Atomic tame/link capture composition and compensation tests. */
class SqliteCompanionCaptureTameAndLinkOperationsTest {
    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteSingleWriter writer;
    private SqliteReadExecutor reads;
    private SqliteCompanionCaptureOperations captures;
    private AtomicInteger refunds;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("tamework-state.sqlite")
        );
        new SqliteSchemaV1Manager(
                connections, () -> -10_000
        ).initialize();
        seedExpectedSource();
        writer = new SqliteSingleWriter(connections);
        reads = new SqliteReadExecutor(connections);
        SqliteUnitOfWorkRunner units =
                new SqliteUnitOfWorkRunner(writer, reads);
        SqliteOperationEngine engine = new SqliteOperationEngine(
                new OperationDefinitionRegistry(
                        List.of(CompanionCaptureDefinition.INSTANCE)
                ),
                units
        );
        refunds = new AtomicInteger();
        captures = new SqliteCompanionCaptureOperations(
                engine,
                new SqliteOperationPublisher(
                        engine,
                        new SqliteOperationEvidenceReader(reads),
                        new ProjectionCoordinator(
                                new SqliteProjectionGateway(reads, units),
                                ProjectionRetryPolicy.DEFAULT,
                                () -> -400
                        ),
                        () -> -400
                ),
                () -> -400,
                (claim, operation) -> {
                    refunds.incrementAndGet();
                    return LiveOperationResult.confirmed(
                            "tame_link_refund_delivered"
                    ).completed();
                },
                List.of()
        );
    }

    @AfterEach
    void tearDown() {
        if (writer != null) {
            writer.shutdown(Duration.ofSeconds(5));
        }
        if (reads != null) {
            reads.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    void oneCaptureCommitsEveryAuthorityAndProjectionAtomically()
            throws Exception {
        AtomicInteger liveCalls = new AtomicInteger();
        OperationWorkflowResult result = submit(
                (capture, operation) -> {
                    liveCalls.incrementAndGet();
                    assertEquals(
                            OperationPhase.LIVE_APPLYING,
                            operation.phase()
                    );
                    return LiveOperationResult.confirmed(
                            "source_spent_and_live_tame_confirmed"
                    ).completed();
                }
        );

        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                result.status()
        );
        assertEquals(1, liveCalls.get());
        assertEquals(6, result.events().size());
        assertEquals(
                List.of(
                        CompanionProfileProjectionChangeCodec.EVENT_TYPE,
                        CompanionLifecycleProjectionChangeCodec.EVENT_TYPE,
                        PopulationGroupAssignmentChangeCodec.EVENT_TYPE,
                        CommandRosterMembershipChangeCodec.EVENT_TYPE,
                        TimedSummonLeaseChangeCodec.EVENT_TYPE,
                        CaptureAttemptResolutionEventCodec.EVENT_TYPE
                ),
                result.events().stream()
                        .map(ProjectionEvent::eventType)
                        .toList()
        );
        assertEventPayloads(result);
        try (Connection connection = connections.openReadConnection()) {
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            var evidence =
                    CaptureTameAndLinkTestFixtures.evidence();
            assertEquals(
                    evidence.targetIdentity(),
                    transaction.identities().findProfile(PROFILE)
                            .orElseThrow()
            );
            assertEquals(
                    evidence.finalLifecycle(),
                    transaction.lifecycles().findByProfile(PROFILE)
                            .orElseThrow()
            );
            assertEquals(
                    evidence.populationGroups().targetPlan().target(),
                    transaction.populationGroups()
                            .findAssignment(PROFILE).orElseThrow()
            );
            assertEquals(
                    evidence.rosterMembership().slotId(),
                    transaction.commandRosters()
                            .findByProfile(PROFILE).orElseThrow().slotId()
            );
            assertEquals(
                    evidence.timedActivation().lease(),
                    transaction.timedSummons().find(PROFILE)
                            .orElseThrow()
            );
            assertTrue(transaction.population()
                    .findByOperation(OPERATION).isEmpty());
            assertTrue(transaction.populationGroups()
                    .findReservations(OPERATION).isEmpty());
        }

        OperationWorkflowResult replay = submit(
                (capture, operation) -> {
                    liveCalls.incrementAndGet();
                    return LiveOperationResult.confirmed(
                            "must_not_run"
                    ).completed();
                }
        );
        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                replay.status()
        );
        assertEquals(1, liveCalls.get());
    }

    @Test
    void exactUnchangedTargetCompensatesAllReservationsAndOneSource()
            throws Exception {
        AtomicInteger liveCalls = new AtomicInteger();
        OperationWorkflowResult result = submit(
                (capture, operation) -> {
                    liveCalls.incrementAndGet();
                    return LiveOperationResult.compensate(
                            "source_spent_target_proven_unchanged",
                            null
                    ).completed();
                }
        );

        assertEquals(
                OperationWorkflowResult.Status.COMPENSATED,
                result.status()
        );
        assertEquals(1, refunds.get());
        try (Connection connection = connections.openReadConnection()) {
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            var evidence =
                    CaptureTameAndLinkTestFixtures.evidence();
            assertEquals(
                    evidence.expectedIdentity(),
                    transaction.identities().findProfile(PROFILE)
                            .orElseThrow()
            );
            var lifecycle = transaction.lifecycles()
                    .findByProfile(PROFILE).orElseThrow();
            assertEquals(LifecycleState.ACTIVE, lifecycle.state());
            assertEquals(
                    evidence.expectedLifecycle().revision().next().next(),
                    lifecycle.revision()
            );
            assertEquals(
                    evidence.expectedLifecycle().location(),
                    lifecycle.location()
            );
            assertFalse(transaction.populationGroups()
                    .findAssignment(PROFILE).isPresent());
            assertFalse(transaction.commandRosters()
                    .findByProfile(PROFILE).isPresent());
            assertFalse(transaction.timedSummons()
                    .find(PROFILE).isPresent());
            assertTrue(transaction.population()
                    .findByOperation(OPERATION).isEmpty());
            assertTrue(transaction.populationGroups()
                    .findReservations(OPERATION).isEmpty());
            assertTrue(transaction.refunds()
                    .findByOperation(OPERATION).orElseThrow().delivered());
        }

        OperationWorkflowResult replay = submit(
                (capture, operation) -> {
                    liveCalls.incrementAndGet();
                    return LiveOperationResult.confirmed(
                            "must_not_run"
                    ).completed();
                }
        );
        assertEquals(
                OperationWorkflowResult.Status.COMPENSATED,
                replay.status()
        );
        assertEquals(1, liveCalls.get());
        assertEquals(1, refunds.get());
    }

    @Test
    void durableFailureRollsBackEveryAuthorityAndResumes()
            throws Exception {
        executeSql("""
                CREATE TRIGGER fail_tame_lease
                BEFORE INSERT ON timed_summon_lease
                BEGIN
                    SELECT RAISE(ABORT, 'injected_tame_lease_failure');
                END
                """);
        AtomicInteger liveCalls = new AtomicInteger();

        OperationWorkflowResult failed = submit(
                (capture, operation) -> {
                    liveCalls.incrementAndGet();
                    return LiveOperationResult.confirmed(
                            "source_spent_and_live_tame_confirmed"
                    ).completed();
                }
        );

        assertEquals(
                OperationWorkflowResult.Status.DURABLE_COMMIT_FAILED,
                failed.status()
        );
        try (Connection connection = connections.openReadConnection()) {
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            var evidence =
                    CaptureTameAndLinkTestFixtures.evidence();
            assertEquals(
                    evidence.expectedIdentity(),
                    transaction.identities().findProfile(PROFILE)
                            .orElseThrow()
            );
            var lifecycle = transaction.lifecycles()
                    .findByProfile(PROFILE).orElseThrow();
            assertEquals(
                    evidence.expectedLifecycle().revision().next(),
                    lifecycle.revision()
            );
            assertEquals(OPERATION, lifecycle.activeOperationId());
            assertFalse(transaction.populationGroups()
                    .findAssignment(PROFILE).isPresent());
            assertFalse(transaction.commandRosters()
                    .findByProfile(PROFILE).isPresent());
            assertFalse(transaction.timedSummons()
                    .find(PROFILE).isPresent());
            assertEquals(2, transaction.population()
                    .findByOperation(OPERATION).size());
            assertEquals(1, transaction.populationGroups()
                    .findReservations(OPERATION).size());
            assertTrue(transaction.outbox()
                    .findByOperation(OPERATION).isEmpty());
        }

        executeSql("DROP TRIGGER fail_tame_lease");
        OperationWorkflowResult resumed = submit(
                (capture, operation) -> {
                    liveCalls.incrementAndGet();
                    return LiveOperationResult.confirmed(
                            "live_tame_receipt_reconfirmed"
                    ).completed();
                }
        );

        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                resumed.status()
        );
        assertEquals(2, liveCalls.get());
    }

    private OperationWorkflowResult submit(
            com.alechilles.alecstamework.companion.capture
                    .CompanionCaptureLiveBoundary boundary
    ) throws Exception {
        SqliteCompanionCaptureOperations.Submission submission =
                captures.submit(
                        OPERATION,
                        new IdempotencyKey("capture-tame-link"),
                        CaptureTameAndLinkTestFixtures.request(),
                        boundary
                );
        assertNotNull(submission.acceptance());
        return submission.completion().toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
    }

    private void assertEventPayloads(
            OperationWorkflowResult result
    ) {
        var evidence = CaptureTameAndLinkTestFixtures.evidence();
        ProjectionEvent profile = event(
                result, CompanionProfileProjectionChangeCodec.EVENT_TYPE
        );
        assertEquals(
                evidence.targetIdentity().roleId(),
                CompanionProfileProjectionChangeCodec.decode(
                        profile.payloadVersion(), profile.payloadJson()
                ).after().roleId()
        );
        ProjectionEvent lifecycle = event(
                result,
                CompanionLifecycleProjectionChangeCodec.EVENT_TYPE
        );
        assertEquals(
                evidence.finalLifecycle(),
                CompanionLifecycleProjectionChangeCodec.decode(
                        lifecycle.payloadVersion(),
                        lifecycle.payloadJson()
                ).after()
        );
        ProjectionEvent groups = event(
                result, PopulationGroupAssignmentChangeCodec.EVENT_TYPE
        );
        assertEquals(
                evidence.populationGroups().targetPlan().target(),
                PopulationGroupAssignmentChangeCodec.decode(
                        groups.payloadVersion(), groups.payloadJson()
                ).after()
        );
        ProjectionEvent roster = event(
                result, CommandRosterMembershipChangeCodec.EVENT_TYPE
        );
        assertEquals(
                evidence.rosterMembership().slotId(),
                CommandRosterMembershipChangeCodec.decode(
                        roster.payloadVersion(), roster.payloadJson()
                ).after().slotId()
        );
        ProjectionEvent lease = event(
                result, TimedSummonLeaseChangeCodec.EVENT_TYPE
        );
        assertEquals(
                evidence.timedActivation().lease(),
                TimedSummonLeaseChangeCodec.decode(
                        lease.payloadVersion(),
                        lease.payloadJson()
                ).after()
        );
        ProjectionEvent attempt = event(
                result, CaptureAttemptResolutionEventCodec.EVENT_TYPE
        );
        assertEquals(
                CaptureTameAndLinkTestFixtures.resolution(),
                CaptureAttemptResolutionEventCodec.decode(
                        attempt.payloadVersion(), attempt.payloadJson()
                )
        );
    }

    private ProjectionEvent event(
            OperationWorkflowResult result,
            ProjectionEventType type
    ) {
        return result.events().stream()
                .filter(candidate ->
                        candidate.eventType().equals(type)
                )
                .findFirst()
                .orElseThrow();
    }

    private void seedExpectedSource() throws Exception {
        var evidence = CaptureTameAndLinkTestFixtures.evidence();
        try (Connection connection =
                     connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            transaction.identities().createProfile(
                    evidence.expectedIdentity()
            );
            transaction.lifecycles().create(
                    evidence.expectedLifecycle()
            );
            try (PreparedStatement statement =
                         connection.prepareStatement("""
                         INSERT INTO companion_alias(
                             npc_uuid, profile_id, alias_generation,
                             alias_state, lease_operation_id,
                             mapped_at_ms, retired_at_ms
                         ) VALUES (?, ?, 0, 'CURRENT', NULL, ?, NULL)
                         """)) {
                statement.setString(
                        1,
                        CaptureTameAndLinkTestFixtures.ALIAS.toString()
                );
                statement.setString(2, PROFILE.toString());
                statement.setLong(3, -1_000);
                statement.executeUpdate();
            }
            connection.commit();
        }
    }

    private void executeSql(String sql) throws Exception {
        try (Connection connection =
                     connections.openWriterConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
