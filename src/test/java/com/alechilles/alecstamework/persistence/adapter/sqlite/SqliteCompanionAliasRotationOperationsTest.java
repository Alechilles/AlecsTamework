package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionAliasLiveBoundary;
import com.alechilles.alecstamework.companion.identity.CompanionAliasRotation;
import com.alechilles.alecstamework.companion.identity.CompanionAliasRotationDefinition;
import com.alechilles.alecstamework.companion.identity.CompanionAliasRotationEventCodec;
import com.alechilles.alecstamework.companion.identity.CompanionAliasRotationOutcome;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** End-to-end pre-lease, live resolution, promotion, retry, and unknown tests. */
class SqliteCompanionAliasRotationOperationsTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    private static final NpcAlias ALIAS_A =
            NpcAlias.parse("30000000-0000-0000-0000-000000000001");
    private static final NpcAlias ALIAS_B =
            NpcAlias.parse("30000000-0000-0000-0000-000000000002");

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteSingleWriter writer;
    private SqliteReadExecutor reads;
    private SqliteCompanionAliasRotationOperations rotations;
    private RevisionConsumer consumer;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionFactory(tempDir.resolve("tamework-state.sqlite"));
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            transaction.identities().createProfile(new CompanionIdentity(
                    PROFILE,
                    "Companion",
                    "role",
                    null,
                    null,
                    "world",
                    -10_000,
                    -10_000,
                    -10_000,
                    0
            ));
            transaction.lifecycles().create(new CompanionLifecycle(
                    PROFILE,
                    null,
                    LifecycleState.UNLOADED,
                    LifecycleLocation.none(),
                    LifecycleRevision.INITIAL,
                    null,
                    -10_000,
                    ReconciliationGeneration.INITIAL,
                    null
            ));
            connection.commit();
        }
        writer = new SqliteSingleWriter(connections);
        reads = new SqliteReadExecutor(connections);
        SqliteUnitOfWorkRunner units = new SqliteUnitOfWorkRunner(writer, reads);
        SqliteOperationEngine engine = new SqliteOperationEngine(
                new OperationDefinitionRegistry(
                        List.of(CompanionAliasRotationDefinition.INSTANCE)
                ),
                units
        );
        consumer = new RevisionConsumer();
        rotations = new SqliteCompanionAliasRotationOperations(
                engine,
                new SqliteOperationPublisher(
                        engine,
                        new SqliteOperationEvidenceReader(reads),
                        new ProjectionCoordinator(
                                new SqliteProjectionGateway(reads, units),
                                ProjectionRetryPolicy.DEFAULT,
                                () -> -5_000
                        ),
                        () -> -5_000
                ),
                () -> -5_000,
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
    void leaseIsCommittedBeforeLiveCallbackThenPromotionRetiresOldAlias()
            throws Exception {
        CompanionAliasRotationOutcome first = published(
                submit(1, ALIAS_A, (rotation, operation) -> {
                    assertEquals(OperationPhase.LIVE_APPLYING, operation.phase());
                    assertEquals(CompanionAlias.State.LEASED, alias(ALIAS_A).state());
                    return CompanionAliasLiveBoundary.Result.confirmed();
                })
        );
        assertEquals(ALIAS_A, first.currentAlias());
        assertEquals(0, first.generation());

        CompanionAliasRotationOutcome second = published(
                submit(2, ALIAS_B, (rotation, operation) -> {
                    assertEquals(CompanionAlias.State.LEASED, alias(ALIAS_B).state());
                    return CompanionAliasLiveBoundary.Result.confirmed();
                })
        );
        assertEquals(ALIAS_B, second.currentAlias());
        assertEquals(1, second.generation());
        assertEquals(CompanionAlias.State.RETIRED, alias(ALIAS_A).state());
        assertEquals(CompanionAlias.State.CURRENT, alias(ALIAS_B).state());
        assertEquals(1L, consumer.revisions.get(PROFILE.toString()));
    }

    @Test
    void retryableLiveResultKeepsLeaseAndResumesSameOperation()
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CompanionAliasLiveBoundary retryThenConfirm = (rotation, operation) ->
                calls.getAndIncrement() == 0
                        ? CompanionAliasLiveBoundary.Result.retryable(
                        "world_not_loaded",
                        null
                )
                        : CompanionAliasLiveBoundary.Result.confirmed();

        OperationWorkflowResult first =
                submit(1, ALIAS_A, retryThenConfirm);

        assertEquals(OperationWorkflowResult.Status.LIVE_RETRYABLE, first.status());
        assertEquals(OperationPhase.RETRYABLE, first.operation().phase());
        assertEquals(CompanionAlias.State.LEASED, alias(ALIAS_A).state());

        CompanionAliasRotationOutcome recovered =
                published(submit(1, ALIAS_A, retryThenConfirm));
        assertEquals(ALIAS_A, recovered.currentAlias());
        assertEquals(2, calls.get());
        assertEquals(CompanionAlias.State.CURRENT, alias(ALIAS_A).state());
    }

    @Test
    void unknownLiveEvidenceFailsClosedAndIsNotBlindlyReapplied()
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CompanionAliasLiveBoundary unknown = (rotation, operation) -> {
            calls.incrementAndGet();
            return CompanionAliasLiveBoundary.Result.unknown(
                    "world_scan_incomplete",
                    null
            );
        };

        OperationWorkflowResult first = submit(1, ALIAS_A, unknown);
        OperationWorkflowResult replay = submit(1, ALIAS_A, unknown);

        assertEquals(OperationWorkflowResult.Status.LIVE_UNKNOWN, first.status());
        assertEquals(OperationPhase.UNKNOWN, first.operation().phase());
        assertEquals(OperationWorkflowResult.Status.LIVE_UNKNOWN, replay.status());
        assertEquals(1, calls.get());
        assertEquals(CompanionAlias.State.LEASED, alias(ALIAS_A).state());
    }

    private OperationWorkflowResult submit(
            int number,
            NpcAlias alias,
            CompanionAliasLiveBoundary live
    ) throws Exception {
        SqliteCompanionAliasRotationOperations.Submission submission = rotations.submit(
                OperationId.parse(String.format(
                        "40000000-0000-0000-0000-%012d",
                        number
                )),
                new IdempotencyKey("alias-rotation-" + number),
                new CompanionAliasRotation(PROFILE, alias, -9_000 + number),
                live
        );
        assertNotNull(submission.acceptance());
        return submission.completion().toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
    }

    private CompanionAliasRotationOutcome published(OperationWorkflowResult result) {
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        assertEquals(OperationPhase.PUBLISHED, result.operation().phase());
        assertEquals(1, result.events().size());
        return CompanionAliasRotationEventCodec.decode(
                result.events().getFirst().payloadVersion(),
                result.events().getFirst().payloadJson()
        );
    }

    private CompanionAlias alias(NpcAlias alias) throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionIdentityStore(connection)
                    .resolveAlias(alias)
                    .orElseThrow();
        }
    }

    private static final class RevisionConsumer implements ProjectionConsumer {
        private final Map<String, Long> revisions = new HashMap<>();

        @Override
        public ProjectionConsumerId consumerId() {
            return new ProjectionConsumerId("companion_alias_view");
        }

        @Override
        public ProjectionApplyOutcome apply(ProjectionEvent event) {
            if (!event.eventType().equals(
                    SqliteCompanionAliasRotationOperations.EVENT_TYPE
            )) {
                return ProjectionApplyOutcome.IRRELEVANT;
            }
            long current = revisions.getOrDefault(event.aggregateId(), -1L);
            if (current >= event.aggregateRevision()) {
                return ProjectionApplyOutcome.ALREADY_APPLIED;
            }
            CompanionAliasRotationEventCodec.decode(
                    event.payloadVersion(),
                    event.payloadJson()
            );
            revisions.put(event.aggregateId(), event.aggregateRevision());
            return ProjectionApplyOutcome.APPLIED;
        }
    }
}
