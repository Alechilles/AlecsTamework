package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.projection.ProjectionApplyOutcome;
import com.alechilles.alecstamework.persistence.projection.ProjectionCatchUpResult;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import com.alechilles.alecstamework.persistence.recovery.OperationRecoveryAction;
import com.alechilles.alecstamework.persistence.recovery.OperationRecoveryClaim;
import com.alechilles.alecstamework.persistence.recovery.OperationRecoveryScanResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Forked-process crash matrix for every shared replacement persistence boundary. */
class PersistenceProcessCrashMatrixTest {
    @TempDir
    Path tempDir;

    @Test
    void everySharedCrashBoundaryRestartsFromDurableEvidence() throws Exception {
        for (PersistenceProcessCrashChild.Boundary boundary
                : PersistenceProcessCrashChild.Boundary.values()) {
            verify(boundary);
        }
    }

    private void verify(PersistenceProcessCrashChild.Boundary boundary) throws Exception {
        Path lane = tempDir.resolve(boundary.name().toLowerCase());
        Path database = lane.resolve("tamework-state.sqlite");
        Path marker = lane.resolve("live-marker.txt");
        Files.createDirectories(lane);
        String output = haltChildAt(boundary, database, marker);

        SqliteConnectionFactory connections = new SqliteConnectionFactory(database);
        SqliteReadExecutor reads = new SqliteReadExecutor(connections);
        SqliteSingleWriter writer = new SqliteSingleWriter(connections);
        PersistenceProcessCrashChild.Definition definition =
                new PersistenceProcessCrashChild.Definition();
        OperationDefinitionRegistry definitions =
                new OperationDefinitionRegistry(List.of(definition));
        try {
            OperationEnvelope operation = readOperation(connections);
            Expected expected = expected(boundary);
            if (expected.phase() == null) {
                assertEquals(null, operation, boundary + "\n" + output);
                OperationRecoveryScanResult empty = recovery(
                        definitions, reads, writer
                ).scanAndClaim("restart", 1_000, 2_000, 10)
                        .toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertTrue(empty.claims().isEmpty(), boundary + "\n" + output);
                return;
            }
            assertEquals(expected.phase(), operation.phase(), boundary + "\n" + output);

            OperationRecoveryScanResult recovered = recovery(
                    definitions, reads, writer
            ).scanAndClaim("restart", 1_000, 2_000, 10)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(recovered.issues().isEmpty(), boundary + "\n" + output);
            assertEquals(1, recovered.claims().size(), boundary + "\n" + output);
            OperationRecoveryClaim claim = recovered.claims().getFirst();
            assertEquals(expected.action(), claim.action(), boundary + "\n" + output);

            if (claim.action() == OperationRecoveryAction.PUBLISH_DURABLE) {
                publishAndAcknowledge(
                        boundary, marker, definitions, reads, writer, claim, output
                );
            }
        } finally {
            writer.shutdown(Duration.ofSeconds(5));
            reads.shutdown(Duration.ofSeconds(5));
        }
    }

    private void publishAndAcknowledge(
            PersistenceProcessCrashChild.Boundary boundary,
            Path marker,
            OperationDefinitionRegistry definitions,
            SqliteReadExecutor reads,
            SqliteSingleWriter writer,
            OperationRecoveryClaim claim,
            String childOutput
    ) throws Exception {
        MarkerConsumer consumer = new MarkerConsumer(marker);
        ProjectionCoordinator projections = new ProjectionCoordinator(
                new SqliteProjectionGateway(
                        reads, new SqliteUnitOfWorkRunner(writer, reads)
                ),
                ProjectionRetryPolicy.DEFAULT,
                () -> 2_100
        );
        ProjectionCatchUpResult projection = projections.startupCatchUp(consumer, 10)
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(
                ProjectionCatchUpResult.Status.CAUGHT_UP,
                projection.status(),
                boundary + "\n" + childOutput
        );
        assertEquals("published", Files.readString(marker));
        if (boundary == PersistenceProcessCrashChild.Boundary.AFTER_PUBLICATION_BEFORE_ACK) {
            assertEquals(ProjectionApplyOutcome.ALREADY_APPLIED, consumer.lastOutcome);
        } else {
            assertEquals(ProjectionApplyOutcome.APPLIED, consumer.lastOutcome);
        }

        SqliteOperationEngine engine = new SqliteOperationEngine(
                definitions, new SqliteUnitOfWorkRunner(writer, reads)
        );
        PersistenceTransactionResult<OperationEnvelope> result = engine.transition(
                claim.operation(), OperationPhase.PUBLISHED, null, null, 2_200
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(result instanceof PersistenceTransactionResult.Committed<?>);
    }

    private SqliteOperationRecoveryCoordinator recovery(
            OperationDefinitionRegistry definitions,
            SqliteReadExecutor reads,
            SqliteSingleWriter writer
    ) {
        return new SqliteOperationRecoveryCoordinator(
                definitions, reads, new SqliteUnitOfWorkRunner(writer, reads)
        );
    }

    private OperationEnvelope readOperation(SqliteConnectionFactory connections) throws Exception {
        try (java.sql.Connection connection = connections.openReadConnection()) {
            return new SqliteOperationStore(connection)
                    .find(PersistenceProcessCrashChild.OPERATION)
                    .orElse(null);
        }
    }

    private Expected expected(PersistenceProcessCrashChild.Boundary boundary) {
        return switch (boundary) {
            case BEFORE_PREPARE_COMMIT -> new Expected(null, null);
            case AFTER_PREPARE_BEFORE_LIVE_APPLY, DURING_SHUTDOWN ->
                    new Expected(OperationPhase.PREPARED,
                            OperationRecoveryAction.RESUME_LIVE_APPLY);
            case DURING_LIVE_APPLY, AFTER_LIVE_APPLY_BEFORE_DURABLE_COMMIT ->
                    new Expected(OperationPhase.LIVE_APPLYING,
                            OperationRecoveryAction.VERIFY_LIVE_APPLY);
            case COMMIT_ERROR_MAY_HAVE_COMMITTED, AFTER_DURABLE_BEFORE_PUBLICATION,
                    DURING_PUBLICATION, AFTER_PUBLICATION_BEFORE_ACK ->
                    new Expected(OperationPhase.DURABLE,
                            OperationRecoveryAction.PUBLISH_DURABLE);
            case DURING_COMPENSATION ->
                    new Expected(OperationPhase.COMPENSATING,
                            OperationRecoveryAction.VERIFY_COMPENSATION);
        };
    }

    private String haltChildAt(PersistenceProcessCrashChild.Boundary boundary,
                               Path database,
                               Path marker) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) {
            classpath = System.getProperty("java.class.path");
        }
        if (classpath == null || classpath.isBlank()) {
            throw new IllegalStateException("Forked test JVM classpath is unavailable");
        }
        Path javaHome = Path.of(System.getProperty("java.home"), "bin");
        Path java = javaHome.resolve("java.exe");
        if (!Files.isRegularFile(java)) {
            java = javaHome.resolve("java");
        }
        Process process = new ProcessBuilder(
                java.toString(),
                "-cp",
                classpath,
                PersistenceProcessCrashChild.class.getName(),
                boundary.name(),
                database.toString(),
                marker.toString()
        ).redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new AssertionError("Crash child timed out at " + boundary);
        }
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8
        );
        assertEquals(
                PersistenceProcessCrashChild.HALT_EXIT_CODE,
                process.exitValue(),
                boundary + "\n" + output
        );
        return output;
    }

    private record Expected(OperationPhase phase, OperationRecoveryAction action) {
    }

    private static final class MarkerConsumer implements ProjectionConsumer {
        private final Path marker;
        private ProjectionApplyOutcome lastOutcome;

        private MarkerConsumer(Path marker) {
            this.marker = marker;
        }

        @Override
        public ProjectionConsumerId consumerId() {
            return new ProjectionConsumerId("process_crash_projection");
        }

        @Override
        public ProjectionApplyOutcome apply(ProjectionEvent event) throws Exception {
            String current = Files.isRegularFile(marker) ? Files.readString(marker) : "";
            if ("published".equals(current)) {
                lastOutcome = ProjectionApplyOutcome.ALREADY_APPLIED;
                return lastOutcome;
            }
            Files.writeString(marker, "published");
            lastOutcome = ProjectionApplyOutcome.APPLIED;
            return lastOutcome;
        }
    }
}
