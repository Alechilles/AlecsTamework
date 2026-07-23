package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupBucket;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupCounts;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
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

/** Forked-process restart gate for initial activation and live fences. */
class ProvisioningActivationProcessCrashTest {
    @TempDir
    Path tempDir;

    @Test
    void everyActivationCommitBoundaryRecoversExactlyOnce()
            throws Exception {
        for (ProvisioningActivationProcessCrashChild.Boundary boundary
                : ProvisioningActivationProcessCrashChild.Boundary.values()) {
            verify(boundary);
        }
    }

    private void verify(
            ProvisioningActivationProcessCrashChild.Boundary boundary
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
            ProvisioningActivationProcessCrashChild.Boundary boundary,
            SqliteConnectionFactory connections,
            String output
    ) throws Exception {
        try (var connection = connections.openReadConnection()) {
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            OperationEnvelope operation = transaction.operations()
                    .find(ProvisioningActivationProcessCrashChild.OPERATION)
                    .orElseThrow();
            CompanionLifecycle lifecycle = transaction.lifecycles()
                    .findByProfile(
                            ProvisioningActivationProcessCrashChild
                                    .ORIGIN.profileId()
                    ).orElseThrow();
            CompanionAlias alias = transaction.identities()
                    .resolveAlias(
                            ProvisioningActivationProcessCrashChild.ALIAS
                    ).orElseThrow();
            boolean durable = boundary
                    == ProvisioningActivationProcessCrashChild.Boundary
                    .DURABLE_COMMITTED;
            OperationPhase expectedPhase = switch (boundary) {
                case PREPARE_COMMITTED -> OperationPhase.PREPARED;
                case LIVE_APPLYING_COMMITTED, DURABLE_UNCOMMITTED ->
                        OperationPhase.LIVE_APPLYING;
                case DURABLE_COMMITTED -> OperationPhase.DURABLE;
            };
            assertEquals(expectedPhase, operation.phase(), output);
            assertEquals(
                    durable ? new LifecycleRevision(2)
                            : new LifecycleRevision(1),
                    lifecycle.revision(),
                    output
            );
            assertEquals(
                    durable
                            ? CompanionAlias.State.CURRENT
                            : CompanionAlias.State.LEASED,
                    alias.state(),
                    output
            );
            assertEquals(
                    durable ? 0 : 1,
                    transaction.populationGroups().findReservations(
                            ProvisioningActivationProcessCrashChild.OPERATION
                    ).size(),
                    output
            );
            assertEquals(
                    durable ? 3 : 0,
                    transaction.outbox().findByOperation(
                            ProvisioningActivationProcessCrashChild.OPERATION
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
                            () -> ProvisioningActivationProcessCrashChild
                                    .ACTIVATED_AT,
                            (claim, operation) ->
                                    LiveOperationResult.confirmed(
                                            "refund"
                                    ).completed(),
                            event -> {
                            }
                    );
            SqlitePublicRecoveryResult result = adapter.recover(
                    boundaries(),
                    "provisioning-activation-process-recovery"
            ).toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(
                    SqlitePublicRecoveryResult.Status.COMPLETE,
                    result.status()
            );
            assertEquals(1, result.completedCount());

            try (var connection = connections.openReadConnection()) {
                SqlitePersistenceTransactionContext transaction =
                        new SqlitePersistenceTransactionContext(connection);
                OperationEnvelope operation = transaction.operations()
                        .find(
                                ProvisioningActivationProcessCrashChild
                                        .OPERATION
                        ).orElseThrow();
                CompanionLifecycle lifecycle =
                        transaction.lifecycles().findByProfile(
                                ProvisioningActivationProcessCrashChild
                                        .ORIGIN.profileId()
                        ).orElseThrow();
                assertEquals(
                        OperationPhase.PUBLISHED, operation.phase()
                );
                assertEquals(
                        new LifecycleRevision(2),
                        lifecycle.revision()
                );
                assertEquals(
                        CompanionAlias.State.CURRENT,
                        transaction.identities().resolveAlias(
                                ProvisioningActivationProcessCrashChild
                                        .ALIAS
                        ).orElseThrow().state()
                );
                assertEquals(
                        0,
                        transaction.populationGroups()
                                .findReservations(
                                        ProvisioningActivationProcessCrashChild
                                                .OPERATION
                                ).size()
                );
                assertEquals(
                        3,
                        transaction.outbox().findByOperation(
                                ProvisioningActivationProcessCrashChild
                                        .OPERATION
                        ).size()
                );
                assertEquals(
                        ProvisioningActivationProcessCrashChild.ORIGIN,
                        transaction.provisioning().findByProfile(
                                ProvisioningActivationProcessCrashChild
                                        .ORIGIN.profileId()
                        ).orElseThrow().origin()
                );
                assertEquals(
                        new PopulationGroupCounts(1, 1, 0, 0),
                        transaction.populationGroups().counts(bucket())
                );
            }
        } finally {
            kernel.shutdown(Duration.ofSeconds(5));
        }
    }

    private PublicPersistenceLiveBoundaries boundaries() {
        return new PublicPersistenceLiveBoundaries(
                (request, operation) ->
                        LiveOperationResult.confirmed("capture")
                                .completed(),
                (request, operation) ->
                        LiveOperationResult.confirmed("restoration")
                                .completed(),
                (request, operation) ->
                        LiveOperationResult.confirmed("coop_capture")
                                .completed(),
                (request, operation) ->
                        LiveOperationResult.confirmed("coop_release")
                                .completed(),
                (request, operation) ->
                        LiveOperationResult.confirmed("timed")
                                .completed(),
                (request, operation) ->
                        LiveOperationResult.confirmed(
                                request.spawnReceiptKey()
                        ).completed(),
                com.alechilles.alecstamework.companion.revival
                        .PaidRevivalBoundaries.unavailable()
        );
    }

    private PopulationGroupBucket bucket() {
        return new PopulationGroupBucket(
                ProvisioningActivationProcessCrashChild.OWNER,
                "mod:mini",
                PopulationGroupScope.GLOBAL,
                null
        );
    }

    private String haltChildAt(
            ProvisioningActivationProcessCrashChild.Boundary boundary,
            Path database,
            Path marker
    ) throws Exception {
        String classpath = System.getProperty(
                "surefire.test.class.path"
        );
        if (classpath == null || classpath.isBlank()) {
            classpath = System.getProperty("java.class.path");
        }
        Path javaHome = Path.of(
                System.getProperty("java.home"), "bin"
        );
        Path java = javaHome.resolve("java.exe");
        if (!Files.isRegularFile(java)) {
            java = javaHome.resolve("java");
        }
        Process process = new ProcessBuilder(
                java.toString(),
                "-cp",
                classpath,
                ProvisioningActivationProcessCrashChild.class.getName(),
                boundary.name(),
                database.toString(),
                marker.toString()
        ).redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new AssertionError(
                    "Activation crash child timed out at " + boundary
            );
        }
        String output = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );
        assertEquals(
                ProvisioningActivationProcessCrashChild.HALT_EXIT_CODE,
                process.exitValue(),
                boundary + "\n" + output
        );
        return output;
    }
}
