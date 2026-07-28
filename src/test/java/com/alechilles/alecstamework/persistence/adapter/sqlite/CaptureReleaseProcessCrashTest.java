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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Forked-process restart gate for inventory-first captured-artifact release. */
class CaptureReleaseProcessCrashTest {
    @TempDir
    Path tempDir;

    @Test
    void crashesResumeWithoutDuplicateInventoryOrSpawnMutation()
            throws Exception {
        for (CaptureReleaseProcessCrashChild.Boundary boundary
                : CaptureReleaseProcessCrashChild.Boundary.values()) {
            verify(boundary);
        }
    }

    private void verify(CaptureReleaseProcessCrashChild.Boundary boundary)
            throws Exception {
        Path lane = tempDir.resolve(boundary.name().toLowerCase());
        Path database = lane.resolve("tamework-state.sqlite");
        Path haltMarker = lane.resolve("halt.txt");
        Path inventoryReceipt = lane.resolve("inventory-receipt.txt");
        Path spawnReceipt = lane.resolve("spawn-receipt.txt");
        Files.createDirectories(lane);

        String output = haltChildAt(
                boundary,
                database,
                haltMarker,
                inventoryReceipt,
                spawnReceipt
        );

        assertEquals(boundary.name(), Files.readString(haltMarker));
        assertEquals("inventory", Files.readString(inventoryReceipt));
        if (boundary == CaptureReleaseProcessCrashChild.Boundary
                .AFTER_INVENTORY_RECEIPT) {
            assertFalse(Files.exists(spawnReceipt));
        } else {
            assertEquals("spawn", Files.readString(spawnReceipt));
        }
        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(database);
        assertCrashEvidence(boundary, connections, output);
        resume(boundary, connections, inventoryReceipt, spawnReceipt);
    }

    private void assertCrashEvidence(
            CaptureReleaseProcessCrashChild.Boundary boundary,
            SqliteConnectionFactory connections,
            String output
    ) throws Exception {
        try (java.sql.Connection connection =
                     connections.openReadConnection()) {
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            OperationEnvelope operation = transaction.operations()
                    .find(CaptureReleaseProcessCrashChild.OPERATION)
                    .orElseThrow();
            CompanionLifecycle lifecycle = transaction.lifecycles()
                    .findByProfile(CaptureReleaseProcessCrashChild.PROFILE)
                    .orElseThrow();
            CompanionAlias alias = transaction.identities()
                    .resolveAlias(CaptureReleaseProcessCrashChild.TARGET_ALIAS)
                    .orElseThrow();
            boolean snapshotCurrent = transaction.snapshots()
                    .findById(CaptureReleaseProcessCrashChild.SNAPSHOT)
                    .orElseThrow()
                    .current();

            if (boundary != CaptureReleaseProcessCrashChild.Boundary
                    .DURABLE_COMMITTED) {
                assertEquals(
                        OperationPhase.LIVE_APPLYING,
                        operation.phase(),
                        output
                );
                assertEquals(LifecycleState.CAPTURED, lifecycle.state());
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
            CaptureReleaseProcessCrashChild.Boundary boundary,
            SqliteConnectionFactory connections,
            Path inventoryReceipt,
            Path spawnReceipt
    ) throws Exception {
        SqliteSingleWriter writer = new SqliteSingleWriter(connections);
        SqliteReadExecutor reads = new SqliteReadExecutor(connections);
        AtomicInteger resolutions = new AtomicInteger();
        AtomicInteger inventoryMutations = new AtomicInteger();
        AtomicInteger spawnMutations = new AtomicInteger();
        try {
            OperationWorkflowResult result =
                    CaptureReleaseProcessCrashChild.operations(writer, reads)
                            .submit(
                                    CaptureReleaseProcessCrashChild.OPERATION,
                                    new IdempotencyKey(
                                            "capture-release-process-crash"
                                    ),
                                    CaptureReleaseProcessCrashChild.request(),
                                    (release, operation) -> {
                                        resolutions.incrementAndGet();
                                        if (!Files.exists(inventoryReceipt)) {
                                            inventoryMutations
                                                    .incrementAndGet();
                                            Files.writeString(
                                                    inventoryReceipt,
                                                    "inventory"
                                            );
                                        }
                                        if (!Files.exists(spawnReceipt)) {
                                            spawnMutations.incrementAndGet();
                                            Files.writeString(
                                                    spawnReceipt,
                                                    "spawn"
                                            );
                                        }
                                        return LiveOperationResult.confirmed(
                                                "capture_release_both_"
                                                        + "receipts_confirmed"
                                        ).completed();
                                    }
                            ).completion().toCompletableFuture()
                            .get(20, TimeUnit.SECONDS);

            assertEquals(
                    OperationWorkflowResult.Status.PUBLISHED,
                    result.status()
            );
            assertEquals(
                    boundary == CaptureReleaseProcessCrashChild.Boundary
                            .DURABLE_COMMITTED ? 0 : 1,
                    resolutions.get()
            );
            assertEquals(0, inventoryMutations.get());
            assertEquals(
                    boundary == CaptureReleaseProcessCrashChild.Boundary
                            .AFTER_INVENTORY_RECEIPT ? 1 : 0,
                    spawnMutations.get()
            );
            assertEquals("inventory", Files.readString(inventoryReceipt));
            assertEquals("spawn", Files.readString(spawnReceipt));
        } finally {
            writer.shutdown(Duration.ofSeconds(5));
            reads.shutdown(Duration.ofSeconds(5));
        }
    }

    private String haltChildAt(
            CaptureReleaseProcessCrashChild.Boundary boundary,
            Path database,
            Path haltMarker,
            Path inventoryReceipt,
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
                CaptureReleaseProcessCrashChild.class.getName(),
                boundary.name(),
                database.toString(),
                haltMarker.toString(),
                inventoryReceipt.toString(),
                spawnReceipt.toString()
        ).redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new AssertionError(
                    "Capture release crash child timed out at " + boundary
            );
        }
        String output = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );
        assertEquals(
                CaptureReleaseProcessCrashChild.HALT_EXIT_CODE,
                process.exitValue(),
                boundary + "\n" + output
        );
        return output;
    }
}
