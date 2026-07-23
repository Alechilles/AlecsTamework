package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.extension.ProfileExtensionData;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionKey;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutation;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationAction;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationDefinition;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationEventCodec;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationOutcome;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.projection.ProjectionApplyOutcome;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end extension mutation, denial, deletion, and replay tests. */
class SqliteProfileExtensionOperationsTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    private static final ProfileId MISSING_PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000002");
    private static final ProfileExtensionKey KEY =
            new ProfileExtensionKey(PROFILE, "example:integration", "state");

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteSingleWriter writer;
    private SqliteReadExecutor reads;
    private SqliteProfileExtensionOperations operations;
    private RevisionConsumer consumer;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionFactory(tempDir.resolve("tamework-state.sqlite"));
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
        try (Connection connection = transaction()) {
            new SqliteCompanionIdentityStore(connection).createProfile(new CompanionIdentity(
                    PROFILE,
                    "Companion",
                    "role",
                    null,
                    null,
                    null,
                    -10_000,
                    -10_000,
                    -10_000,
                    0
            ));
            connection.commit();
        }
        writer = new SqliteSingleWriter(connections);
        reads = new SqliteReadExecutor(connections);
        SqliteUnitOfWorkRunner units = new SqliteUnitOfWorkRunner(writer, reads);
        consumer = new RevisionConsumer();
        operations = new SqliteProfileExtensionOperations(
                new SqliteDatabaseOperationCoordinator(
                        new SqliteOperationEngine(
                                new OperationDefinitionRegistry(
                                        List.of(ProfileExtensionMutationDefinition.INSTANCE)
                                ),
                                units
                        ),
                        new SqliteOperationEvidenceReader(reads),
                        new ProjectionCoordinator(
                                new SqliteProjectionGateway(reads, units),
                                ProjectionRetryPolicy.DEFAULT,
                                () -> -5_000
                        ),
                        () -> -5_000
                ),
                List.of(consumer)
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
    void putsDeniesDeletesAndRecreatesWithOneMonotonicRevisionLineage()
            throws Exception {
        ProfileExtensionMutationOutcome initial = submit(
                "40000000-0000-0000-0000-000000000001",
                "extension-put-1",
                mutation(KEY, ProfileExtensionMutationAction.PUT, null, "{\"value\":1}", -9_000)
        );
        assertEquals(ProfileExtensionMutationOutcome.Status.APPLIED, initial.status());
        assertEquals(1, initial.revision());

        ProfileExtensionMutationOutcome denied = submit(
                "40000000-0000-0000-0000-000000000002",
                "extension-put-stale",
                mutation(KEY, ProfileExtensionMutationAction.PUT, 0L, "{\"value\":2}", -8_000)
        );
        assertEquals(ProfileExtensionMutationOutcome.Status.REVISION_MISMATCH, denied.status());
        assertEquals(1, denied.revision());
        assertEquals("{\"value\":1}", activeValue().jsonPayload());

        ProfileExtensionMutationOutcome deleted = submit(
                "40000000-0000-0000-0000-000000000003",
                "extension-delete-1",
                mutation(KEY, ProfileExtensionMutationAction.DELETE, 1L, null, -7_000)
        );
        assertEquals(ProfileExtensionMutationOutcome.Status.DELETED, deleted.status());
        assertEquals(2, deleted.revision());
        assertTrue(exactValue().deleted());

        ProfileExtensionMutationOutcome recreated = submit(
                "40000000-0000-0000-0000-000000000004",
                "extension-put-2",
                mutation(KEY, ProfileExtensionMutationAction.PUT, null, "{\"value\":3}", -6_000)
        );
        assertEquals(ProfileExtensionMutationOutcome.Status.APPLIED, recreated.status());
        assertEquals(3, recreated.revision());
        assertEquals(3L, consumer.revisions.get(KEY.aggregateId()));
        assertEquals("{\"value\":3}", activeValue().jsonPayload());
    }

    @Test
    void publishedReplayReturnsSameOutcomeWithoutAdvancingCanonicalRevision()
            throws Exception {
        ProfileExtensionMutation mutation = mutation(
                KEY,
                ProfileExtensionMutationAction.PUT,
                0L,
                "{\"value\":1}",
                -9_000
        );

        ProfileExtensionMutationOutcome first = submit(
                "40000000-0000-0000-0000-000000000001",
                "extension-replay",
                mutation
        );
        ProfileExtensionMutationOutcome replay = submit(
                "40000000-0000-0000-0000-000000000001",
                "extension-replay",
                mutation
        );

        assertEquals(first, replay);
        assertEquals(1, exactValue().revision());
        assertEquals(1, consumer.appliedEvents);
    }

    @Test
    void missingProfileIsDurableDomainOutcomeRatherThanFabricatedAbsence()
            throws Exception {
        ProfileExtensionKey missingKey =
                new ProfileExtensionKey(MISSING_PROFILE, KEY.namespace(), KEY.dataKey());

        ProfileExtensionMutationOutcome result = submit(
                "40000000-0000-0000-0000-000000000001",
                "extension-missing-profile",
                mutation(
                        missingKey,
                        ProfileExtensionMutationAction.PUT,
                        null,
                        "{\"value\":1}",
                        -9_000
                )
        );

        assertEquals(ProfileExtensionMutationOutcome.Status.PROFILE_NOT_FOUND, result.status());
        assertEquals(0, result.revision());
        try (Connection connection = connections.openReadConnection()) {
            assertInstanceOf(
                    PersistenceReadResult.Absent.class,
                    new SqliteProfileExtensionDataStore(connection).find(missingKey)
            );
        }
    }

    private ProfileExtensionMutationOutcome submit(
            String operationId,
            String idempotencyKey,
            ProfileExtensionMutation mutation
    ) throws Exception {
        OperationWorkflowResult result = operations.submit(
                OperationId.parse(operationId),
                new IdempotencyKey(idempotencyKey),
                mutation
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        assertEquals(OperationPhase.PUBLISHED, result.operation().phase());
        assertEquals(1, result.events().size());
        ProjectionEvent event = result.events().getFirst();
        assertEquals(
                ProfileExtensionMutationEventCodec.EVENT_TYPE,
                event.eventType()
        );
        return ProfileExtensionMutationEventCodec.decode(
                event.payloadVersion(),
                event.payloadJson()
        );
    }

    private ProfileExtensionMutation mutation(
            ProfileExtensionKey key,
            ProfileExtensionMutationAction action,
            Long expectedRevision,
            String json,
            long requestedAtMs
    ) {
        return new ProfileExtensionMutation(
                key,
                action,
                expectedRevision,
                json,
                requestedAtMs
        );
    }

    private ProfileExtensionData activeValue() throws Exception {
        ProfileExtensionData value = exactValue();
        assertTrue(!value.deleted());
        return value;
    }

    private ProfileExtensionData exactValue() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            PersistenceReadResult.Found<ProfileExtensionData> found = assertInstanceOf(
                    PersistenceReadResult.Found.class,
                    new SqliteProfileExtensionDataStore(connection).find(KEY)
            );
            return found.value();
        }
    }

    private Connection transaction() throws Exception {
        Connection connection = connections.openWriterConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private static final class RevisionConsumer implements ProjectionConsumer {
        private final Map<String, Long> revisions = new HashMap<>();
        private int appliedEvents;

        @Override
        public ProjectionConsumerId consumerId() {
            return new ProjectionConsumerId("profile_extension_view");
        }

        @Override
        public ProjectionApplyOutcome apply(ProjectionEvent event) {
            if (!event.eventType().equals(
                    ProfileExtensionMutationEventCodec.EVENT_TYPE
            )) {
                return ProjectionApplyOutcome.IRRELEVANT;
            }
            long current = revisions.getOrDefault(event.aggregateId(), -1L);
            if (current >= event.aggregateRevision()) {
                return ProjectionApplyOutcome.ALREADY_APPLIED;
            }
            ProfileExtensionMutationEventCodec.decode(
                    event.payloadVersion(),
                    event.payloadJson()
            );
            revisions.put(event.aggregateId(), event.aggregateRevision());
            appliedEvents++;
            return ProjectionApplyOutcome.APPLIED;
        }
    }
}
