package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Forked-process restart gate for restoration's external entity insertion seam. */
class RestorationProcessCrashTest {
    @TempDir
    Path tempDir;

    @Test
    void restorationCrashesResumeWithoutDuplicateLivePresence()
            throws Exception {
        for (RestorationProcessCrashChild.Boundary boundary
                : RestorationProcessCrashChild.Boundary.values()) {
            verify(boundary);
        }
    }

    private void verify(RestorationProcessCrashChild.Boundary boundary)
            throws Exception {
        Path lane = tempDir.resolve(boundary.name().toLowerCase());
        Path database = lane.resolve("tamework-state.sqlite");
        Path haltMarker = lane.resolve("halt.txt");
        Path spawnReceipt = lane.resolve("spawn-receipt.txt");
        Files.createDirectories(lane);

        String output = haltChildAt(
                boundary, database, haltMarker, spawnReceipt
        );

        assertEquals(boundary.name(), Files.readString(haltMarker));
        assertEquals("spawn", Files.readString(spawnReceipt));
        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(database);
        assertCrashEvidence(boundary, connections, output);
        resume(boundary, connections, spawnReceipt);
    }

    private void assertCrashEvidence(
            RestorationProcessCrashChild.Boundary boundary,
            SqliteConnectionFactory connections,
            String output
    ) throws Exception {
        try (java.sql.Connection connection =
                     connections.openReadConnection()) {
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            OperationEnvelope operation = transaction.operations()
                    .find(RestorationProcessCrashChild.OPERATION)
                    .orElseThrow();
            CompanionLifecycle lifecycle = transaction.lifecycles()
                    .findByProfile(RestorationProcessCrashChild.PROFILE)
                    .orElseThrow();
            CompanionAlias alias = transaction.identities()
                    .resolveAlias(RestorationProcessCrashChild.TARGET_ALIAS)
                    .orElseThrow();
            boolean snapshotCurrent = transaction.snapshots()
                    .findById(RestorationProcessCrashChild.SNAPSHOT)
                    .orElseThrow()
                    .current();

            if (boundary == RestorationProcessCrashChild.Boundary
                    .DURABLE_UNCOMMITTED) {
                assertEquals(
                        OperationPhase.LIVE_APPLYING,
                        operation.phase(),
                        output
                );
                assertEquals(
                        LifecycleState.DEAD_REVIVABLE,
                        lifecycle.state()
                );
                assertEquals(
                        new LifecycleRevision(2),
                        lifecycle.revision()
                );
                assertEquals(
                        operation.operationId(),
                        lifecycle.activeOperationId()
                );
                assertEquals(CompanionAlias.State.LEASED, alias.state());
                assertTrue(snapshotCurrent);
            } else {
                assertEquals(
                        OperationPhase.DURABLE,
                        operation.phase(),
                        output
                );
                assertEquals(LifecycleState.ACTIVE, lifecycle.state());
                assertEquals(
                        new LifecycleRevision(3),
                        lifecycle.revision()
                );
                assertNull(lifecycle.activeOperationId());
                assertEquals(CompanionAlias.State.CURRENT, alias.state());
                assertTrue(!snapshotCurrent);
            }
        }
    }

    private void resume(
            RestorationProcessCrashChild.Boundary boundary,
            SqliteConnectionFactory connections,
            Path spawnReceipt
    ) throws Exception {
        SqliteSingleWriter writer = new SqliteSingleWriter(connections);
        SqliteReadExecutor reads = new SqliteReadExecutor(connections);
        AtomicInteger resolutions = new AtomicInteger();
        AtomicInteger insertions = new AtomicInteger();
        try {
            OperationWorkflowResult result =
                    RestorationProcessCrashChild.operations(writer, reads)
                            .submit(
                                    RestorationProcessCrashChild.OPERATION,
                                    new IdempotencyKey(
                                            "restoration-process-crash"
                                    ),
                                    RestorationProcessCrashChild.request(),
                                    (restoration, operation) -> {
                                        resolutions.incrementAndGet();
                                        if (!Files.exists(spawnReceipt)) {
                                            insertions.incrementAndGet();
                                            Files.writeString(
                                                    spawnReceipt, "spawn"
                                            );
                                        }
                                        return LiveOperationResult.confirmed(
                                                "spawn_receipt_confirmed"
                                        );
                                    }
                            ).completion().toCompletableFuture()
                            .get(20, TimeUnit.SECONDS);

            assertEquals(
                    OperationWorkflowResult.Status.PUBLISHED,
                    result.status()
            );
            assertEquals(
                    boundary == RestorationProcessCrashChild.Boundary
                            .DURABLE_UNCOMMITTED ? 1 : 0,
                    resolutions.get()
            );
            assertEquals(0, insertions.get());
            assertEquals("spawn", Files.readString(spawnReceipt));
        } finally {
            writer.shutdown(Duration.ofSeconds(5));
            reads.shutdown(Duration.ofSeconds(5));
        }
    }

    private String haltChildAt(
            RestorationProcessCrashChild.Boundary boundary,
            Path database,
            Path haltMarker,
            Path spawnReceipt
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
                RestorationProcessCrashChild.class.getName(),
                boundary.name(),
                database.toString(),
                haltMarker.toString(),
                spawnReceipt.toString()
        ).redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new AssertionError(
                    "Restoration crash child timed out at " + boundary
            );
        }
        String output = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );
        assertEquals(
                RestorationProcessCrashChild.HALT_EXIT_CODE,
                process.exitValue(),
                boundary + "\n" + output
        );
        return output;
    }
}
