package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionAliasLiveBoundary;
import com.alechilles.alecstamework.companion.identity.CompanionAliasRotation;
import com.alechilles.alecstamework.companion.identity.CompanionAliasRotationDefinition;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Forked-process restart gate for alias lease and promotion commit boundaries. */
class AliasRotationProcessCrashTest {
    @TempDir
    Path tempDir;

    @Test
    void leaseAndPromotionCrashesResumeFromExactDurableEvidence()
            throws Exception {
        for (AliasRotationProcessCrashChild.Boundary boundary
                : AliasRotationProcessCrashChild.Boundary.values()) {
            verify(boundary);
        }
    }

    private void verify(AliasRotationProcessCrashChild.Boundary boundary)
            throws Exception {
        Path lane = tempDir.resolve(boundary.name().toLowerCase());
        Path database = lane.resolve("tamework-state.sqlite");
        Path marker = lane.resolve("crash-marker.txt");
        Files.createDirectories(lane);
        String output = haltChildAt(boundary, database, marker);
        assertEquals(boundary.name(), Files.readString(marker));

        SqliteConnectionFactory connections = new SqliteConnectionFactory(database);
        assertCrashEvidence(boundary, connections, output);
        resume(boundary, connections);
    }

    private void assertCrashEvidence(
            AliasRotationProcessCrashChild.Boundary boundary,
            SqliteConnectionFactory connections,
            String output
    ) throws Exception {
        try (java.sql.Connection connection = connections.openReadConnection()) {
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            OperationEnvelope operation = transaction.operations()
                    .find(AliasRotationProcessCrashChild.OPERATION)
                    .orElseThrow();
            CompanionAlias oldAlias = transaction.identities()
                    .resolveAlias(AliasRotationProcessCrashChild.OLD_ALIAS)
                    .orElseThrow();
            CompanionAlias target = transaction.identities()
                    .resolveAlias(AliasRotationProcessCrashChild.TARGET_ALIAS)
                    .orElseThrow();
            if (boundary
                    == AliasRotationProcessCrashChild.Boundary.LEASE_COMMITTED) {
                assertEquals(OperationPhase.PREPARED, operation.phase(), output);
            } else if (boundary
                    == AliasRotationProcessCrashChild.Boundary.PROMOTION_UNCOMMITTED) {
                assertEquals(OperationPhase.LIVE_APPLYING, operation.phase(), output);
            } else {
                assertEquals(OperationPhase.DURABLE, operation.phase(), output);
            }
            if (boundary
                    == AliasRotationProcessCrashChild.Boundary.PROMOTION_COMMITTED) {
                assertEquals(CompanionAlias.State.RETIRED, oldAlias.state(), output);
                assertEquals(CompanionAlias.State.CURRENT, target.state(), output);
                assertEquals(
                        1,
                        transaction.outbox()
                                .findByOperation(AliasRotationProcessCrashChild.OPERATION)
                                .size(),
                        output
                );
            } else {
                assertEquals(CompanionAlias.State.CURRENT, oldAlias.state(), output);
                assertEquals(CompanionAlias.State.LEASED, target.state(), output);
                assertTrue(
                        transaction.outbox()
                                .findByOperation(AliasRotationProcessCrashChild.OPERATION)
                                .isEmpty(),
                        output
                );
            }
        }
    }

    private void resume(
            AliasRotationProcessCrashChild.Boundary boundary,
            SqliteConnectionFactory connections
    ) throws Exception {
        SqliteSingleWriter writer = new SqliteSingleWriter(connections);
        SqliteReadExecutor reads = new SqliteReadExecutor(connections);
        try {
            SqliteUnitOfWorkRunner units = new SqliteUnitOfWorkRunner(writer, reads);
            SqliteOperationEngine engine = new SqliteOperationEngine(
                    new OperationDefinitionRegistry(
                            List.of(CompanionAliasRotationDefinition.INSTANCE)
                    ),
                    units
            );
            SqliteCompanionAliasRotationOperations rotations =
                    new SqliteCompanionAliasRotationOperations(
                            engine,
                            new SqliteOperationPublisher(
                                    engine,
                                    new SqliteOperationEvidenceReader(reads),
                                    new ProjectionCoordinator(
                                            new SqliteProjectionGateway(reads, units),
                                            ProjectionRetryPolicy.DEFAULT,
                                            () -> -4_000
                                    ),
                                    () -> -4_000
                            ),
                            () -> -4_000,
                            List.of()
                    );
            AtomicInteger liveCalls = new AtomicInteger();
            OperationWorkflowResult result = rotations.submit(
                    AliasRotationProcessCrashChild.OPERATION,
                    new IdempotencyKey("alias-process-crash"),
                    new CompanionAliasRotation(
                            AliasRotationProcessCrashChild.PROFILE,
                            AliasRotationProcessCrashChild.TARGET_ALIAS,
                            -9_000
                    ),
                    (rotation, operation) -> {
                        liveCalls.incrementAndGet();
                        return CompanionAliasLiveBoundary.Result.confirmed();
                    }
            ).completion().toCompletableFuture().get(20, TimeUnit.SECONDS);

            assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
            assertEquals(
                    boundary == AliasRotationProcessCrashChild.Boundary.PROMOTION_COMMITTED
                            ? 0
                            : 1,
                    liveCalls.get()
            );
            try (java.sql.Connection connection = connections.openReadConnection()) {
                SqlitePersistenceTransactionContext transaction =
                        new SqlitePersistenceTransactionContext(connection);
                assertEquals(
                        CompanionAlias.State.CURRENT,
                        transaction.identities()
                                .resolveAlias(AliasRotationProcessCrashChild.TARGET_ALIAS)
                                .orElseThrow()
                                .state()
                );
                assertEquals(
                        CompanionAlias.State.RETIRED,
                        transaction.identities()
                                .resolveAlias(AliasRotationProcessCrashChild.OLD_ALIAS)
                                .orElseThrow()
                                .state()
                );
            }
        } finally {
            writer.shutdown(Duration.ofSeconds(5));
            reads.shutdown(Duration.ofSeconds(5));
        }
    }

    private String haltChildAt(
            AliasRotationProcessCrashChild.Boundary boundary,
            Path database,
            Path marker
    ) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) {
            classpath = System.getProperty("java.class.path");
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
                AliasRotationProcessCrashChild.class.getName(),
                boundary.name(),
                database.toString(),
                marker.toString()
        ).redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new AssertionError("Alias crash child timed out at " + boundary);
        }
        String output = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );
        assertEquals(
                AliasRotationProcessCrashChild.HALT_EXIT_CODE,
                process.exitValue(),
                boundary + "\n" + output
        );
        return output;
    }
}
