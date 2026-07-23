package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.CompanionToolLink;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutationDefinition;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutationEventCodec;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutationOutcome;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.DatabaseOperationResult;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** End-to-end operation tests for atomic profile identity and tool-link mutations. */
class SqliteCompanionProfileOperationsTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    private static final UUID TOOL_A =
            UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID TOOL_B =
            UUID.fromString("50000000-0000-0000-0000-000000000002");

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteSingleWriter writer;
    private SqliteReadExecutor reads;
    private SqliteCompanionProfileOperations operations;
    private RevisionConsumer consumer;

    @BeforeEach
    void setUp() {
        connections = new SqliteConnectionFactory(tempDir.resolve("tamework-state.sqlite"));
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
        writer = new SqliteSingleWriter(connections);
        reads = new SqliteReadExecutor(connections);
        SqliteUnitOfWorkRunner units = new SqliteUnitOfWorkRunner(writer, reads);
        consumer = new RevisionConsumer();
        operations = new SqliteCompanionProfileOperations(
                new SqliteDatabaseOperationCoordinator(
                        new SqliteOperationEngine(
                                new OperationDefinitionRegistry(
                                        List.of(CompanionProfileMutationDefinition.INSTANCE)
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
    void createsUpdatesReplacesLinksAndPublishesStaleDenial() throws Exception {
        CompanionProfileMutationOutcome created = submit(
                1,
                "profile-create",
                create("Companion", List.of(link(TOOL_B, -9_000), link(TOOL_A, -9_000)))
        );
        assertEquals(CompanionProfileMutationOutcome.Status.CREATED, created.status());
        assertEquals(0, created.metadataRevision());
        assertEquals(List.of(TOOL_A, TOOL_B), storedToolIds());

        CompanionProfileMutation.Update update = new CompanionProfileMutation.Update(
                identity(1, "Updated", -8_000),
                0,
                List.of(link(TOOL_B, -8_000)),
                -8_000
        );
        CompanionProfileMutationOutcome updated =
                submit(2, "profile-update", update);
        assertEquals(CompanionProfileMutationOutcome.Status.UPDATED, updated.status());
        assertEquals(1, updated.metadataRevision());
        assertEquals("Updated", storedIdentity().displayName());
        assertEquals(List.of(TOOL_B), storedToolIds());

        CompanionProfileMutationOutcome stale = submit(
                3,
                "profile-update-stale",
                new CompanionProfileMutation.Update(
                        identity(1, "Stale", -7_000),
                        0,
                        List.of(),
                        -7_000
                )
        );
        assertEquals(
                CompanionProfileMutationOutcome.Status.REVISION_MISMATCH,
                stale.status()
        );
        assertEquals(1, stale.metadataRevision());
        assertEquals("Updated", storedIdentity().displayName());
        assertEquals(List.of(TOOL_B), storedToolIds());

        CompanionProfileMutationOutcome replay =
                submit(2, "profile-update", update);
        assertEquals(updated, replay);
        assertEquals(2, consumer.appliedEvents);
    }

    @Test
    void repeatedLogicalCreateIsUnchangedButDifferentRecordConflicts()
            throws Exception {
        CompanionProfileMutation.Create create =
                create("Companion", List.of(link(TOOL_A, -9_000)));
        submit(1, "profile-create", create);

        CompanionProfileMutationOutcome unchanged =
                submit(2, "profile-create-same", create);
        assertEquals(
                CompanionProfileMutationOutcome.Status.UNCHANGED,
                unchanged.status()
        );

        CompanionProfileMutationOutcome conflict = submit(
                3,
                "profile-create-conflict",
                create("Different", List.of(link(TOOL_A, -9_000)))
        );
        assertEquals(
                CompanionProfileMutationOutcome.Status.CONFLICT,
                conflict.status()
        );
        assertEquals("Companion", storedIdentity().displayName());
    }

    private CompanionProfileMutationOutcome submit(
            int operationNumber,
            String idempotencyKey,
            CompanionProfileMutation mutation
    ) throws Exception {
        DatabaseOperationResult result = operations.submit(
                OperationId.parse(String.format(
                        "40000000-0000-0000-0000-%012d",
                        operationNumber
                )),
                new IdempotencyKey(idempotencyKey),
                mutation
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(DatabaseOperationResult.Status.PUBLISHED, result.status());
        assertEquals(OperationPhase.PUBLISHED, result.operation().phase());
        assertEquals(1, result.events().size());
        return CompanionProfileMutationEventCodec.decode(
                result.events().getFirst().payloadVersion(),
                result.events().getFirst().payloadJson()
        );
    }

    private CompanionProfileMutation.Create create(
            String name,
            List<CompanionToolLink> links
    ) {
        return new CompanionProfileMutation.Create(
                identity(0, name, -9_000),
                new CompanionLifecycle(
                        PROFILE,
                        OwnerId.parse("10000000-0000-0000-0000-000000000001"),
                        LifecycleState.UNLOADED,
                        LifecycleLocation.none(),
                        LifecycleRevision.INITIAL,
                        null,
                        -9_000,
                        ReconciliationGeneration.INITIAL,
                        null
                ),
                links,
                -9_000
        );
    }

    private CompanionIdentity identity(long revision, String name, long updatedAtMs) {
        String metadata = "{\"source\":\"test\"}";
        return new CompanionIdentity(
                PROFILE,
                name,
                "role",
                metadata,
                Sha256Hash.ofUtf8(metadata),
                "world",
                -10_000,
                updatedAtMs,
                updatedAtMs,
                revision
        );
    }

    private CompanionToolLink link(UUID toolId, long updatedAtMs) {
        return new CompanionToolLink(
                PROFILE,
                toolId,
                "command",
                -9_000,
                updatedAtMs
        );
    }

    private CompanionIdentity storedIdentity() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionIdentityStore(connection)
                    .findProfile(PROFILE)
                    .orElseThrow();
        }
    }

    private List<UUID> storedToolIds() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionToolLinkStore(connection)
                    .findByProfile(PROFILE)
                    .stream()
                    .map(CompanionToolLink::toolId)
                    .toList();
        }
    }

    private static final class RevisionConsumer implements ProjectionConsumer {
        private final Map<String, Long> revisions = new HashMap<>();
        private int appliedEvents;

        @Override
        public ProjectionConsumerId consumerId() {
            return new ProjectionConsumerId("companion_profile_view");
        }

        @Override
        public ProjectionApplyOutcome apply(ProjectionEvent event) {
            if (!event.eventType().equals(SqliteCompanionProfileOperations.EVENT_TYPE)) {
                return ProjectionApplyOutcome.IRRELEVANT;
            }
            long current = revisions.getOrDefault(event.aggregateId(), -1L);
            if (current >= event.aggregateRevision()) {
                return ProjectionApplyOutcome.ALREADY_APPLIED;
            }
            CompanionProfileMutationEventCodec.decode(
                    event.payloadVersion(),
                    event.payloadJson()
            );
            revisions.put(event.aggregateId(), event.aggregateRevision());
            appliedEvents++;
            return ProjectionApplyOutcome.APPLIED;
        }
    }
}
