package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.population.OwnerPopulationScope;
import com.alechilles.alecstamework.persistence.control.PersistenceOperationAdmissionGate;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Forked-process restart gate for owner reservations and lifecycle finalization. */
class OwnerPopulationProcessCrashTest {
    @TempDir
    Path tempDir;

    @Test
    void everyPopulationCommitBoundaryRecoversExactlyOnce()
            throws Exception {
        for (OwnerPopulationProcessCrashChild.Boundary boundary
                : OwnerPopulationProcessCrashChild.Boundary.values()) {
            verify(boundary);
        }
    }

    private void verify(
            OwnerPopulationProcessCrashChild.Boundary boundary
    ) throws Exception {
        Path lane = tempDir.resolve(boundary.name().toLowerCase());
        Path database = lane.resolve("tamework-state.sqlite");
        Path marker = lane.resolve("crash-marker.txt");
        Files.createDirectories(lane);
        String output = haltChildAt(boundary, database, marker);
        assertEquals(boundary.name(), Files.readString(marker));

        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(database);
        assertCrashEvidence(boundary, connections, output);
        recoverAndVerify(connections);
    }

    private void assertCrashEvidence(
            OwnerPopulationProcessCrashChild.Boundary boundary,
            SqliteConnectionFactory connections,
            String output
    ) throws Exception {
        try (var connection = connections.openReadConnection()) {
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            OperationEnvelope operation = transaction.operations()
                    .find(OwnerPopulationProcessCrashChild.OPERATION)
                    .orElseThrow();
            CompanionLifecycle lifecycle = transaction.lifecycles()
                    .findByProfile(OwnerPopulationProcessCrashChild.PROFILE)
                    .orElseThrow();
            boolean durable = boundary
                    == OwnerPopulationProcessCrashChild.Boundary
                    .DURABLE_COMMITTED;
            assertEquals(
                    durable ? OperationPhase.DURABLE : OperationPhase.PREPARED,
                    operation.phase(),
                    output
            );
            assertEquals(
                    durable ? OwnerPopulationProcessCrashChild.OWNER : null,
                    lifecycle.ownerId(),
                    output
            );
            assertEquals(
                    durable ? new LifecycleRevision(1)
                            : LifecycleRevision.INITIAL,
                    lifecycle.revision(),
                    output
            );
            assertEquals(
                    durable ? 0 : 2,
                    transaction.population().findByOperation(
                            OwnerPopulationProcessCrashChild.OPERATION
                    ).size(),
                    output
            );
            assertEquals(
                    durable ? 3 : 0,
                    transaction.outbox().findByOperation(
                            OwnerPopulationProcessCrashChild.OPERATION
                    ).size(),
                    output
            );
        }
    }

    private void recoverAndVerify(
            SqliteConnectionFactory connections
    ) throws Exception {
        SqlitePersistenceKernel kernel =
                new SqlitePersistenceKernel(connections);
        try {
            SqlitePublicPersistenceAdapter adapter =
                    new SqlitePublicPersistenceAdapter(
                            PublicPersistenceFeatureRegistry.create(),
                            kernel,
                            PersistenceOperationAdmissionGate.allowAll(),
                            () -> -4_000,
                            (claim, operation) ->
                                    LiveOperationResult.confirmed(
                                            "refund"
                                    ).completed(),
                            event -> {
                            }
                    );
            SqlitePublicRecoveryResult result = adapter.recover(
                    boundaries(),
                    "population-process-recovery"
            ).toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(SqlitePublicRecoveryResult.Status.COMPLETE, result.status());
            assertEquals(1, result.completedCount());

            try (var connection = connections.openReadConnection()) {
                SqlitePersistenceTransactionContext transaction =
                        new SqlitePersistenceTransactionContext(connection);
                OperationEnvelope operation = transaction.operations()
                        .find(OwnerPopulationProcessCrashChild.OPERATION)
                        .orElseThrow();
                CompanionLifecycle lifecycle = transaction.lifecycles()
                        .findByProfile(
                                OwnerPopulationProcessCrashChild.PROFILE
                        ).orElseThrow();
                assertEquals(OperationPhase.PUBLISHED, operation.phase());
                assertEquals(
                        OwnerPopulationProcessCrashChild.OWNER,
                        lifecycle.ownerId()
                );
                assertEquals(new LifecycleRevision(1), lifecycle.revision());
                assertEquals(
                        0,
                        transaction.population().findByOperation(
                                OwnerPopulationProcessCrashChild.OPERATION
                        ).size()
                );
                assertEquals(
                        3,
                        transaction.outbox().findByOperation(
                                OwnerPopulationProcessCrashChild.OPERATION
                        ).size()
                );
            }
            assertEquals(
                    1,
                    adapter.ownerPopulationIndex().count(
                            OwnerPopulationScope.global(
                                    OwnerPopulationProcessCrashChild.OWNER
                            )
                    )
            );
        } finally {
            kernel.shutdown(Duration.ofSeconds(5));
        }
    }

    private PublicPersistenceLiveBoundaries boundaries() {
        return new PublicPersistenceLiveBoundaries(
                (request, operation) ->
                        LiveOperationResult.confirmed("capture").completed(),
                (request, operation) ->
                        LiveOperationResult.confirmed("capture_release")
                                .completed(),
                (request, operation) ->
                        LiveOperationResult.confirmed("restoration").completed(),
                (request, operation) ->
                        LiveOperationResult.confirmed("coop_capture").completed(),
                (request, operation) ->
                        LiveOperationResult.confirmed("coop_release").completed(),
                (request, operation) ->
                        LiveOperationResult.confirmed("timed").completed(),
                (request, operation) ->
                        LiveOperationResult.confirmed(
                                "provisioning_activation"
                        ).completed()
        );
    }

    private String haltChildAt(
            OwnerPopulationProcessCrashChild.Boundary boundary,
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
                OwnerPopulationProcessCrashChild.class.getName(),
                boundary.name(),
                database.toString(),
                marker.toString()
        ).redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new AssertionError(
                    "Population crash child timed out at " + boundary
            );
        }
        String output = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );
        assertEquals(
                OwnerPopulationProcessCrashChild.HALT_EXIT_CODE,
                process.exitValue(),
                boundary + "\n" + output
        );
        return output;
    }
}
