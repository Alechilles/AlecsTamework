package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.coop.CoopResidency;
import com.alechilles.alecstamework.companion.coop.CoopSlot;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
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

/** Forked-process restart gates for both coop live-world mutation seams. */
class CoopProcessCrashTest {
    @TempDir
    Path tempDir;

    @Test
    void captureAndReleaseResumeAcrossBothDurableCommitBoundaries()
            throws Exception {
        for (CoopProcessCrashChild.Boundary boundary
                : CoopProcessCrashChild.Boundary.values()) {
            verify(boundary);
        }
    }

    private void verify(CoopProcessCrashChild.Boundary boundary)
            throws Exception {
        Path lane = tempDir.resolve(boundary.name().toLowerCase());
        Path database = lane.resolve("tamework-state.sqlite");
        Path haltMarker = lane.resolve("halt.txt");
        Path liveReceipt = lane.resolve("live-receipt.txt");
        Files.createDirectories(lane);

        String output = haltChildAt(
                boundary, database, haltMarker, liveReceipt
        );

        assertEquals(boundary.name(), Files.readString(haltMarker));
        assertEquals(
                boundary.kind() == CoopProcessCrashChild.Kind.CAPTURE
                        ? "capture"
                        : "release",
                Files.readString(liveReceipt)
        );
        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(database);
        assertCrashEvidence(boundary, connections, output);
        resume(boundary, connections, liveReceipt);
        assertFinalEvidence(boundary.kind(), connections);
    }

    private void assertCrashEvidence(
            CoopProcessCrashChild.Boundary boundary,
            SqliteConnectionFactory connections,
            String output
    ) throws Exception {
        try (java.sql.Connection connection =
                     connections.openReadConnection()) {
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            OperationEnvelope operation = transaction.operations()
                    .find(CoopProcessCrashChild.OPERATION)
                    .orElseThrow();
            CompanionLifecycle lifecycle = transaction.lifecycles()
                    .findByProfile(CoopProcessCrashChild.PROFILE)
                    .orElseThrow();
            CoopSlot slot = transaction.coops()
                    .findSlot(CoopProcessCrashChild.SLOT)
                    .orElseThrow();
            CoopResidency residency = transaction.coops()
                    .findResidencyBySlot(CoopProcessCrashChild.SLOT)
                    .orElse(null);

            assertEquals(
                    boundary.committed()
                            ? OperationPhase.DURABLE
                            : OperationPhase.LIVE_APPLYING,
                    operation.phase(),
                    output
            );
            if (boundary.kind() == CoopProcessCrashChild.Kind.CAPTURE) {
                assertCaptureCrashEvidence(
                        boundary, lifecycle, slot, residency, transaction
                );
            } else {
                assertReleaseCrashEvidence(
                        boundary, lifecycle, slot, residency, transaction
                );
            }
        }
    }

    private void assertCaptureCrashEvidence(
            CoopProcessCrashChild.Boundary boundary,
            CompanionLifecycle lifecycle,
            CoopSlot slot,
            CoopResidency residency,
            SqlitePersistenceTransactionContext transaction
    ) {
        if (!boundary.committed()) {
            assertEquals(LifecycleState.ACTIVE, lifecycle.state());
            assertEquals(new LifecycleRevision(1), lifecycle.revision());
            assertEquals(
                    CoopProcessCrashChild.OPERATION,
                    lifecycle.activeOperationId()
            );
            assertTrue(slot.reserved());
            assertEquals(0, slot.residencyRevision());
            assertNull(residency);
            assertTrue(transaction.snapshots()
                    .findById(CoopProcessCrashChild.SNAPSHOT)
                    .isEmpty());
            return;
        }
        assertEquals(LifecycleState.COOP, lifecycle.state());
        assertEquals(new LifecycleRevision(2), lifecycle.revision());
        assertNull(lifecycle.activeOperationId());
        assertFalse(slot.reserved());
        assertEquals(1, slot.residencyRevision());
        assertEquals(
                CoopProcessCrashChild.PROFILE,
                residency.profileId()
        );
        assertTrue(transaction.snapshots()
                .findById(CoopProcessCrashChild.SNAPSHOT)
                .orElseThrow()
                .current());
    }

    private void assertReleaseCrashEvidence(
            CoopProcessCrashChild.Boundary boundary,
            CompanionLifecycle lifecycle,
            CoopSlot slot,
            CoopResidency residency,
            SqlitePersistenceTransactionContext transaction
    ) {
        CompanionAlias targetAlias = transaction.identities()
                .resolveAlias(CoopProcessCrashChild.TARGET_ALIAS)
                .orElseThrow();
        CompanionSnapshot snapshot = transaction.snapshots()
                .findById(CoopProcessCrashChild.SNAPSHOT)
                .orElseThrow();
        if (!boundary.committed()) {
            assertEquals(LifecycleState.COOP, lifecycle.state());
            assertEquals(new LifecycleRevision(3), lifecycle.revision());
            assertEquals(
                    CoopProcessCrashChild.OPERATION,
                    lifecycle.activeOperationId()
            );
            assertTrue(slot.reserved());
            assertEquals(1, slot.residencyRevision());
            assertEquals(
                    CoopProcessCrashChild.PROFILE,
                    residency.profileId()
            );
            assertTrue(snapshot.current());
            assertEquals(
                    CompanionAlias.State.LEASED,
                    targetAlias.state()
            );
            return;
        }
        assertEquals(LifecycleState.ACTIVE, lifecycle.state());
        assertEquals(new LifecycleRevision(4), lifecycle.revision());
        assertNull(lifecycle.activeOperationId());
        assertFalse(slot.reserved());
        assertEquals(2, slot.residencyRevision());
        assertNull(residency);
        assertFalse(snapshot.current());
        assertEquals(CompanionAlias.State.CURRENT, targetAlias.state());
    }

    private void resume(
            CoopProcessCrashChild.Boundary boundary,
            SqliteConnectionFactory connections,
            Path liveReceipt
    ) throws Exception {
        SqliteSingleWriter writer = new SqliteSingleWriter(connections);
        SqliteReadExecutor reads = new SqliteReadExecutor(connections);
        AtomicInteger resolutions = new AtomicInteger();
        AtomicInteger liveMutations = new AtomicInteger();
        try {
            OperationWorkflowResult result;
            CoopProcessCrashChild.Operations operations =
                    CoopProcessCrashChild.operations(writer, reads);
            if (boundary.kind() == CoopProcessCrashChild.Kind.CAPTURE) {
                result = operations.capture().submit(
                        CoopProcessCrashChild.OPERATION,
                        new IdempotencyKey("coop-capture-process-crash"),
                        CoopProcessCrashChild.captureRequest(),
                        (capture, operation) -> resolveReceipt(
                                liveReceipt,
                                "capture",
                                resolutions,
                                liveMutations
                        )
                ).completion().toCompletableFuture()
                        .get(20, TimeUnit.SECONDS);
            } else {
                result = operations.release().submit(
                        CoopProcessCrashChild.OPERATION,
                        new IdempotencyKey("coop-release-process-crash"),
                        CoopProcessCrashChild.releaseRequest(),
                        (release, operation) -> resolveReceipt(
                                liveReceipt,
                                "release",
                                resolutions,
                                liveMutations
                        )
                ).completion().toCompletableFuture()
                        .get(20, TimeUnit.SECONDS);
            }
            assertEquals(
                    OperationWorkflowResult.Status.PUBLISHED,
                    result.status()
            );
            assertEquals(boundary.committed() ? 0 : 1, resolutions.get());
            assertEquals(0, liveMutations.get());
        } finally {
            writer.shutdown(Duration.ofSeconds(5));
            reads.shutdown(Duration.ofSeconds(5));
        }
    }

    private java.util.concurrent.CompletionStage<LiveOperationResult>
    resolveReceipt(
            Path liveReceipt,
            String expectedReceipt,
            AtomicInteger resolutions,
            AtomicInteger liveMutations
    ) throws Exception {
        resolutions.incrementAndGet();
        if (!Files.exists(liveReceipt)) {
            liveMutations.incrementAndGet();
            Files.writeString(liveReceipt, expectedReceipt);
        }
        assertEquals(expectedReceipt, Files.readString(liveReceipt));
        return LiveOperationResult.confirmed(
                expectedReceipt + "_receipt_confirmed"
        ).completed();
    }

    private void assertFinalEvidence(
            CoopProcessCrashChild.Kind kind,
            SqliteConnectionFactory connections
    ) throws Exception {
        try (java.sql.Connection connection =
                     connections.openReadConnection()) {
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            assertEquals(
                    OperationPhase.PUBLISHED,
                    transaction.operations()
                            .find(CoopProcessCrashChild.OPERATION)
                            .orElseThrow()
                            .phase()
            );
            CompanionLifecycle lifecycle = transaction.lifecycles()
                    .findByProfile(CoopProcessCrashChild.PROFILE)
                    .orElseThrow();
            CoopSlot slot = transaction.coops()
                    .findSlot(CoopProcessCrashChild.SLOT)
                    .orElseThrow();
            if (kind == CoopProcessCrashChild.Kind.CAPTURE) {
                assertEquals(LifecycleState.COOP, lifecycle.state());
                assertEquals(1, slot.residencyRevision());
                assertTrue(transaction.coops()
                        .findResidencyBySlot(CoopProcessCrashChild.SLOT)
                        .isPresent());
                assertTrue(transaction.snapshots()
                        .findById(CoopProcessCrashChild.SNAPSHOT)
                        .orElseThrow()
                        .current());
            } else {
                assertEquals(LifecycleState.ACTIVE, lifecycle.state());
                assertEquals(2, slot.residencyRevision());
                assertTrue(transaction.coops()
                        .findResidencyBySlot(CoopProcessCrashChild.SLOT)
                        .isEmpty());
                assertFalse(transaction.snapshots()
                        .findById(CoopProcessCrashChild.SNAPSHOT)
                        .orElseThrow()
                        .current());
            }
        }
    }

    private String haltChildAt(
            CoopProcessCrashChild.Boundary boundary,
            Path database,
            Path haltMarker,
            Path liveReceipt
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
                CoopProcessCrashChild.class.getName(),
                boundary.name(),
                database.toString(),
                haltMarker.toString(),
                liveReceipt.toString()
        ).redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new AssertionError(
                    "Coop crash child timed out at " + boundary
            );
        }
        String output = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );
        assertEquals(
                CoopProcessCrashChild.HALT_EXIT_CODE,
                process.exitValue(),
                boundary + "\n" + output
        );
        return output;
    }
}
