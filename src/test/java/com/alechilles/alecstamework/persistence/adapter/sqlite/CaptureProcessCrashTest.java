package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.persistence.compensation.RefundClaim;
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

/** Forked-process restart gate for capture and refund external-effect seams. */
class CaptureProcessCrashTest {
    @TempDir
    Path tempDir;

    @Test
    void captureAndRefundCrashesResumeWithoutDuplicateExternalMutation()
            throws Exception {
        for (CaptureProcessCrashChild.Boundary boundary
                : CaptureProcessCrashChild.Boundary.values()) {
            verify(boundary);
        }
    }

    private void verify(CaptureProcessCrashChild.Boundary boundary)
            throws Exception {
        Path lane = tempDir.resolve(boundary.name().toLowerCase());
        Path database = lane.resolve("tamework-state.sqlite");
        Path haltMarker = lane.resolve("halt.txt");
        Path captureReceipt = lane.resolve("capture-receipt.txt");
        Path refundReceipt = lane.resolve("refund-receipt.txt");
        Files.createDirectories(lane);

        String output = haltChildAt(
                boundary,
                database,
                haltMarker,
                captureReceipt,
                refundReceipt
        );
        assertEquals(boundary.name(), Files.readString(haltMarker));
        assertEquals("capture", Files.readString(captureReceipt));

        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(database);
        assertCrashEvidence(boundary, connections, refundReceipt, output);
        resume(
                boundary,
                connections,
                captureReceipt,
                refundReceipt
        );
    }

    private void assertCrashEvidence(
            CaptureProcessCrashChild.Boundary boundary,
            SqliteConnectionFactory connections,
            Path refundReceipt,
            String output
    ) throws Exception {
        try (java.sql.Connection connection =
                     connections.openReadConnection()) {
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            OperationEnvelope operation = transaction.operations()
                    .find(CaptureProcessCrashChild.OPERATION)
                    .orElseThrow();
            CompanionLifecycle lifecycle = transaction.lifecycles()
                    .findByProfile(CaptureProcessCrashChild.PROFILE)
                    .orElseThrow();
            RefundClaim refund = transaction.refunds()
                    .findByOperation(CaptureProcessCrashChild.OPERATION)
                    .orElse(null);
            boolean snapshot = transaction.snapshots()
                    .findById(CaptureProcessCrashChild.request()
                            .snapshot()
                            .snapshotId())
                    .isPresent();

            switch (boundary) {
                case CAPTURE_DURABLE_UNCOMMITTED -> {
                    assertEquals(
                            OperationPhase.LIVE_APPLYING,
                            operation.phase(),
                            output
                    );
                    assertFenced(lifecycle);
                    assertNull(refund);
                    assertFalse(snapshot);
                    assertFalse(Files.exists(refundReceipt));
                }
                case CAPTURE_DURABLE_COMMITTED -> {
                    assertEquals(
                            OperationPhase.DURABLE,
                            operation.phase(),
                            output
                    );
                    assertEquals(LifecycleState.CAPTURED, lifecycle.state());
                    assertEquals(new LifecycleRevision(2), lifecycle.revision());
                    assertNull(lifecycle.activeOperationId());
                    assertTrue(snapshot);
                    assertNull(refund);
                    assertFalse(Files.exists(refundReceipt));
                }
                case REFUND_CLAIM_COMMITTED,
                     REFUND_DURABLE_UNCOMMITTED -> {
                    assertEquals(
                            OperationPhase.COMPENSATING,
                            operation.phase(),
                            output
                    );
                    assertFenced(lifecycle);
                    assertFalse(refund.delivered());
                    assertFalse(snapshot);
                    if (boundary
                            == CaptureProcessCrashChild.Boundary
                            .REFUND_DURABLE_UNCOMMITTED) {
                        assertEquals(
                                "refund",
                                Files.readString(refundReceipt)
                        );
                    } else {
                        assertFalse(Files.exists(refundReceipt));
                    }
                }
                case REFUND_DURABLE_COMMITTED -> {
                    assertEquals(
                            OperationPhase.COMPENSATED,
                            operation.phase(),
                            output
                    );
                    assertEquals(LifecycleState.ACTIVE, lifecycle.state());
                    assertEquals(new LifecycleRevision(2), lifecycle.revision());
                    assertNull(lifecycle.activeOperationId());
                    assertTrue(refund.delivered());
                    assertFalse(snapshot);
                    assertEquals("refund", Files.readString(refundReceipt));
                }
            }
        }
    }

    private void resume(
            CaptureProcessCrashChild.Boundary boundary,
            SqliteConnectionFactory connections,
            Path captureReceipt,
            Path refundReceipt
    ) throws Exception {
        SqliteSingleWriter writer = new SqliteSingleWriter(connections);
        SqliteReadExecutor reads = new SqliteReadExecutor(connections);
        AtomicInteger captureCalls = new AtomicInteger();
        AtomicInteger captureMutations = new AtomicInteger();
        AtomicInteger refundCalls = new AtomicInteger();
        AtomicInteger refundMutations = new AtomicInteger();
        try {
            SqliteCompanionCaptureOperations captures =
                    CaptureProcessCrashChild.operations(
                            writer,
                            reads,
                            () -> {
                                refundCalls.incrementAndGet();
                                if (!Files.exists(refundReceipt)) {
                                    refundMutations.incrementAndGet();
                                    Files.writeString(
                                            refundReceipt,
                                            "refund"
                                    );
                                }
                                return LiveOperationResult.confirmed(
                                        "refund_receipt_confirmed"
                                );
                            }
                    );
            OperationWorkflowResult result = captures.submit(
                    CaptureProcessCrashChild.OPERATION,
                    new IdempotencyKey("capture-process-crash"),
                    CaptureProcessCrashChild.request(),
                    (capture, operation) -> {
                        captureCalls.incrementAndGet();
                        if (!Files.exists(captureReceipt)) {
                            captureMutations.incrementAndGet();
                            Files.writeString(
                                    captureReceipt,
                                    "capture"
                            );
                        }
                        return boundary.compensating()
                                ? LiveOperationResult.compensate(
                                        "source_spent_target_proven_live",
                                        null
                                )
                                : LiveOperationResult.confirmed(
                                        "capture_receipt_and_target_"
                                                + "retirement_confirmed"
                                );
                    }
            ).completion().toCompletableFuture()
                    .get(20, TimeUnit.SECONDS);

            assertEquals(
                    boundary.compensating()
                            ? OperationWorkflowResult.Status.COMPENSATED
                            : OperationWorkflowResult.Status.PUBLISHED,
                    result.status()
            );
            int expectedCaptureCalls = boundary
                    == CaptureProcessCrashChild.Boundary
                    .CAPTURE_DURABLE_UNCOMMITTED ? 1 : 0;
            int expectedRefundCalls =
                    boundary == CaptureProcessCrashChild.Boundary
                            .REFUND_CLAIM_COMMITTED
                            || boundary == CaptureProcessCrashChild.Boundary
                            .REFUND_DURABLE_UNCOMMITTED ? 1 : 0;
            int expectedRefundMutations =
                    boundary == CaptureProcessCrashChild.Boundary
                            .REFUND_CLAIM_COMMITTED ? 1 : 0;
            assertEquals(expectedCaptureCalls, captureCalls.get());
            assertEquals(0, captureMutations.get());
            assertEquals(expectedRefundCalls, refundCalls.get());
            assertEquals(expectedRefundMutations, refundMutations.get());
        } finally {
            writer.shutdown(Duration.ofSeconds(5));
            reads.shutdown(Duration.ofSeconds(5));
        }
    }

    private void assertFenced(CompanionLifecycle lifecycle) {
        assertEquals(LifecycleState.ACTIVE, lifecycle.state());
        assertEquals(new LifecycleRevision(1), lifecycle.revision());
        assertEquals(
                CaptureProcessCrashChild.OPERATION,
                lifecycle.activeOperationId()
        );
    }

    private String haltChildAt(
            CaptureProcessCrashChild.Boundary boundary,
            Path database,
            Path haltMarker,
            Path captureReceipt,
            Path refundReceipt
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
                CaptureProcessCrashChild.class.getName(),
                boundary.name(),
                database.toString(),
                haltMarker.toString(),
                captureReceipt.toString(),
                refundReceipt.toString()
        ).redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new AssertionError(
                    "Capture crash child timed out at " + boundary
            );
        }
        String output = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );
        assertEquals(
                CaptureProcessCrashChild.HALT_EXIT_CODE,
                process.exitValue(),
                boundary + "\n" + output
        );
        return output;
    }
}
