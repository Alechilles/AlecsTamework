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

/** Forked-process restart gate for timed summon's external spawn seam. */
class TimedSummonProcessCrashTest {
    @TempDir
    Path tempDir;

    @Test
    void summonAndStoreCrashesResumeWithoutDuplicateLiveEffects()
            throws Exception {
        for (TimedSummonProcessCrashChild.Boundary boundary
                : TimedSummonProcessCrashChild.Boundary.values()) {
            verify(boundary);
        }
    }

    private void verify(TimedSummonProcessCrashChild.Boundary boundary)
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
        assertEquals(
                boundary.storing() ? "store" : "spawn",
                Files.readString(spawnReceipt)
        );
        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(database);
        assertCrashEvidence(boundary, connections, output);
        resume(boundary, connections, spawnReceipt);
    }

    private void assertCrashEvidence(
            TimedSummonProcessCrashChild.Boundary boundary,
            SqliteConnectionFactory connections,
            String output
    ) throws Exception {
        try (var connection = connections.openReadConnection()) {
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            OperationEnvelope operation = transaction.operations()
                    .find(TimedSummonProcessCrashChild.OPERATION)
                    .orElseThrow();
            CompanionLifecycle lifecycle = transaction.lifecycles()
                    .findByProfile(TimedSummonProcessCrashChild.PROFILE)
                    .orElseThrow();
            CompanionAlias alias = transaction.identities()
                    .resolveAlias(TimedSummonProcessCrashChild.ALIAS)
                    .orElseThrow();
            var snapshot = transaction.snapshots()
                    .findById(TimedSummonProcessCrashChild.SNAPSHOT)
                    .orElse(null);
            boolean activeSession = transaction.timedSummons()
                    .find(TimedSummonProcessCrashChild.PROFILE)
                    .orElseThrow().activeSession();
            int reservations = transaction.populationGroups()
                    .findReservations(
                            TimedSummonProcessCrashChild.OPERATION
                    ).size();

            if (!boundary.committed()) {
                assertEquals(
                        OperationPhase.LIVE_APPLYING,
                        operation.phase(),
                        output
                );
                assertEquals(
                        new LifecycleRevision(1), lifecycle.revision()
                );
                assertEquals(
                        operation.operationId(),
                        lifecycle.activeOperationId()
                );
                if (boundary.storing()) {
                    assertEquals(LifecycleState.ACTIVE, lifecycle.state());
                    assertEquals(
                            CompanionAlias.State.CURRENT, alias.state()
                    );
                    assertTrue(snapshot == null);
                    assertTrue(activeSession);
                    assertEquals(0, reservations);
                } else {
                    assertEquals(
                            LifecycleState.ROSTER_STORED,
                            lifecycle.state()
                    );
                    assertEquals(
                            CompanionAlias.State.LEASED, alias.state()
                    );
                    assertTrue(snapshot != null && snapshot.current());
                    assertTrue(!activeSession);
                    assertEquals(1, reservations);
                }
            } else {
                assertEquals(
                        OperationPhase.DURABLE,
                        operation.phase(),
                        output
                );
                assertEquals(
                        new LifecycleRevision(2), lifecycle.revision()
                );
                assertNull(lifecycle.activeOperationId());
                assertTrue(snapshot != null);
                assertEquals(0, reservations);
                if (boundary.storing()) {
                    assertEquals(
                            LifecycleState.ROSTER_STORED,
                            lifecycle.state()
                    );
                    assertEquals(
                            CompanionAlias.State.RETIRED, alias.state()
                    );
                    assertTrue(snapshot.current());
                    assertTrue(!activeSession);
                } else {
                    assertEquals(
                            LifecycleState.ACTIVE, lifecycle.state()
                    );
                    assertEquals(
                            CompanionAlias.State.CURRENT, alias.state()
                    );
                    assertTrue(!snapshot.current());
                    assertTrue(activeSession);
                }
            }
        }
    }

    private void resume(
            TimedSummonProcessCrashChild.Boundary boundary,
            SqliteConnectionFactory connections,
            Path spawnReceipt
    ) throws Exception {
        SqliteSingleWriter writer = new SqliteSingleWriter(connections);
        SqliteReadExecutor reads = new SqliteReadExecutor(connections);
        AtomicInteger resolutions = new AtomicInteger();
        AtomicInteger insertions = new AtomicInteger();
        try {
            OperationWorkflowResult result =
                    TimedSummonProcessCrashChild.operations(writer, reads)
                            .submit(
                                    TimedSummonProcessCrashChild.OPERATION,
                                    new IdempotencyKey(
                                            "timed-process-crash"
                                    ),
                                    TimedSummonProcessCrashChild.request(
                                            boundary
                                    ),
                                    (transition, operation) -> {
                                        resolutions.incrementAndGet();
                                        if (!Files.exists(spawnReceipt)) {
                                            insertions.incrementAndGet();
                                            Files.writeString(
                                                    spawnReceipt,
                                                    boundary.storing()
                                                            ? "store"
                                                            : "spawn"
                                            );
                                        }
                                        return LiveOperationResult.confirmed(
                                                "spawn_receipt_confirmed"
                                        ).completed();
                                    }
                            ).completion().toCompletableFuture()
                            .get(20, TimeUnit.SECONDS);

            assertEquals(
                    OperationWorkflowResult.Status.PUBLISHED,
                    result.status(),
                    () -> messages(result.failure())
            );
            assertEquals(
                    boundary.committed() ? 0 : 1,
                    resolutions.get()
            );
            assertEquals(0, insertions.get());
        } finally {
            writer.shutdown(Duration.ofSeconds(5));
            reads.shutdown(Duration.ofSeconds(5));
        }
    }

    private String haltChildAt(
            TimedSummonProcessCrashChild.Boundary boundary,
            Path database,
            Path haltMarker,
            Path spawnReceipt
    ) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) {
            classpath = System.getProperty("java.class.path");
        }
        Path java = Path.of(
                System.getProperty("java.home"), "bin", "java.exe"
        );
        if (!Files.isRegularFile(java)) {
            java = Path.of(
                    System.getProperty("java.home"), "bin", "java"
            );
        }
        Process process = new ProcessBuilder(
                java.toString(),
                "-cp",
                classpath,
                TimedSummonProcessCrashChild.class.getName(),
                boundary.name(),
                database.toString(),
                haltMarker.toString(),
                spawnReceipt.toString()
        ).redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new AssertionError(
                    "Timed summon child timed out at " + boundary
            );
        }
        String output = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );
        assertEquals(
                TimedSummonProcessCrashChild.HALT_EXIT_CODE,
                process.exitValue(),
                boundary + "\n" + output
        );
        return output;
    }

    private String messages(Throwable failure) {
        StringBuilder result = new StringBuilder();
        while (failure != null) {
            result.append(failure.getClass().getSimpleName())
                    .append(':')
                    .append(failure.getMessage())
                    .append('\n');
            failure = failure.getCause();
        }
        return result.toString();
    }
}
