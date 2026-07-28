package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.capture.CaptureTameAndLinkTestFixtures;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureDefinition;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationTransition;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PreparedOperation;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pre-live command-family, slot, roster-revision, and timed-lease fencing tests. */
class SqliteCompanionCaptureTameAndLinkPreparationTest {
    private static final OperationId FAMILY_OPERATION =
            OperationId.parse(
                    "50000000-0000-0000-0000-000000000999"
            );

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteSingleWriter writer;
    private SqliteReadExecutor reads;
    private SqliteCompanionCaptureOperations captures;

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
                (claim, operation) -> LiveOperationResult.confirmed(
                        "unused_refund"
                ).completed(),
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
    void activeFamilyWriterBlocksUntilItsDurableCommit()
            throws Exception {
        seedActiveFamilyOperation();
        AtomicInteger liveCalls = new AtomicInteger();

        OperationWorkflowResult blocked = submit(liveCalls);

        assertEquals(
                OperationWorkflowResult.Status.PREPARE_FAILED,
                blocked.status()
        );
        assertEquals(0, liveCalls.get());

        markFamilyOperationDurable();
        OperationWorkflowResult resumed = submit(liveCalls);

        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                resumed.status()
        );
        assertEquals(1, liveCalls.get());
    }

    @Test
    void existingTimedLeaseBlocksBeforeLiveMutation()
            throws Exception {
        try (Connection connection =
                     connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            var transaction =
                    new SqlitePersistenceTransactionContext(connection);
            assertTrue(transaction.timedSummons().replace(
                    null,
                    CaptureTameAndLinkTestFixtures.evidence()
                            .timedActivation().lease()
            ).applied());
            connection.commit();
        }

        assertPrepareBlocked();
    }

    @Test
    void staleCommandFamilyRevisionBlocksBeforeLiveMutation()
            throws Exception {
        var family = CaptureTameAndLinkTestFixtures.evidence()
                .rosterMembership().familyKey();
        try (Connection connection =
                     connections.openWriterConnection();
             PreparedStatement statement =
                     connection.prepareStatement("""
                     INSERT INTO command_family(
                         owner_uuid, family_id, roster_revision,
                         created_at_ms, updated_at_ms
                     ) VALUES (?, ?, 1, ?, ?)
                     """)) {
            statement.setString(1, family.ownerId().toString());
            statement.setString(2, family.familyId());
            statement.setLong(3, -700);
            statement.setLong(4, -700);
            statement.executeUpdate();
        }

        assertPrepareBlocked();
    }

    @Test
    void occupiedGlobalCommandSlotBlocksBeforeLiveMutation()
            throws Exception {
        seedOccupiedCommandSlot();

        assertPrepareBlocked();
    }

    private void assertPrepareBlocked() throws Exception {
        AtomicInteger liveCalls = new AtomicInteger();
        OperationWorkflowResult result = submit(liveCalls);
        assertEquals(
                OperationWorkflowResult.Status.PREPARE_FAILED,
                result.status()
        );
        assertEquals(0, liveCalls.get());
    }

    private OperationWorkflowResult submit(
            AtomicInteger liveCalls
    ) throws Exception {
        return captures.submit(
                OPERATION,
                new IdempotencyKey("capture-tame-link"),
                CaptureTameAndLinkTestFixtures.request(),
                (capture, operation) -> {
                    liveCalls.incrementAndGet();
                    return LiveOperationResult.confirmed(
                            "source_spent_and_live_tame_confirmed"
                    ).completed();
                }
        ).completion().toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
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

    private void seedActiveFamilyOperation() throws Exception {
        var family = CaptureTameAndLinkTestFixtures.evidence()
                .rosterMembership().familyKey();
        try (Connection connection =
                     connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            assertTrue(transaction.operations().prepare(
                    new PreparedOperation(
                            FAMILY_OPERATION,
                            new IdempotencyKey("active-family-writer"),
                            new OperationKind("command_roster_membership"),
                            1,
                            "{}",
                            "command_roster",
                            null,
                            List.of(OperationScope.commandFamily(family)),
                            -600
                    )
            ).applied());
            connection.commit();
        }
    }

    private void markFamilyOperationDurable() throws Exception {
        try (Connection connection =
                     connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            assertTrue(transaction.operations().transition(
                    new OperationTransition(
                            FAMILY_OPERATION,
                            OperationPhase.PREPARED,
                            OperationPhase.DURABLE,
                            null,
                            null,
                            null,
                            -500
                    )
            ).applied());
            connection.commit();
        }
    }

    private void seedOccupiedCommandSlot() throws Exception {
        ProfileId otherProfile = ProfileId.parse(
                "10000000-0000-0000-0000-000000000999"
        );
        OwnerId otherOwner = OwnerId.parse(
                "30000000-0000-0000-0000-000000000999"
        );
        String slotId = CaptureTameAndLinkTestFixtures.evidence()
                .rosterMembership().slotId().toString();
        try (Connection connection =
                     connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            assertTrue(transaction.identities().createProfile(
                    new CompanionIdentity(
                            otherProfile,
                            "Other",
                            "Other_Role",
                            null,
                            null,
                            "world",
                            -900,
                            -900,
                            -900,
                            0
                    )
            ).applied());
            insertOccupiedSlot(
                    connection, otherProfile, otherOwner, slotId
            );
            connection.commit();
        }
    }

    private void insertOccupiedSlot(
            Connection connection,
            ProfileId profileId,
            OwnerId ownerId,
            String slotId
    ) throws Exception {
        try (PreparedStatement family =
                     connection.prepareStatement("""
                     INSERT INTO command_family(
                         owner_uuid, family_id, roster_revision,
                         created_at_ms, updated_at_ms
                     ) VALUES (?, 'other-family', 1, ?, ?)
                     """);
             PreparedStatement membership =
                     connection.prepareStatement("""
                     INSERT INTO command_roster_membership(
                         slot_id, profile_id, owner_uuid, family_id,
                         membership_revision, group_id,
                         active_for_bulk_commands,
                         home_world_key, home_x, home_y, home_z,
                         created_at_ms, updated_at_ms
                     ) VALUES (?, ?, ?, 'other-family', 1, NULL, 1,
                               NULL, NULL, NULL, NULL, ?, ?)
                     """)) {
            family.setString(1, ownerId.toString());
            family.setLong(2, -800);
            family.setLong(3, -800);
            family.executeUpdate();
            membership.setString(1, slotId);
            membership.setString(2, profileId.toString());
            membership.setString(3, ownerId.toString());
            membership.setLong(4, -800);
            membership.setLong(5, -800);
            membership.executeUpdate();
        }
    }
}
