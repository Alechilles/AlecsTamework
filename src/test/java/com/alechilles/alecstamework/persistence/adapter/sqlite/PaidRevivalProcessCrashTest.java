package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.revival.PaidRevivalBoundaries;
import com.alechilles.alecstamework.companion.revival.PaidRevivalLiveResult;
import com.alechilles.alecstamework.persistence.compensation.RefundClaim;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Forked-process restart gate for paid revival and both economic outcomes. */
class PaidRevivalProcessCrashTest {
    @TempDir
    Path tempDir;

    @Test
    void everyReceiptAndDurabilitySeamConvergesThroughPublicRecovery()
            throws Exception {
        for (PaidRevivalProcessCrashChild.Boundary boundary
                : PaidRevivalProcessCrashChild.Boundary.values()) {
            verify(boundary);
        }
    }

    private void verify(PaidRevivalProcessCrashChild.Boundary boundary)
            throws Exception {
        Path lane = tempDir.resolve(boundary.name().toLowerCase());
        Files.createDirectories(lane);
        CrashFiles files = new CrashFiles(lane);
        String output = haltChildAt(boundary, files);
        assertEquals(boundary.name(), Files.readString(files.halt()));

        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(files.database());
        assertCrashEvidence(boundary, connections, files, output);
        recoverAndVerify(boundary, connections, files);
    }

    private void assertCrashEvidence(
            PaidRevivalProcessCrashChild.Boundary boundary,
            SqliteConnectionFactory connections,
            CrashFiles files,
            String output
    ) throws Exception {
        try (var connection = connections.openReadConnection()) {
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            OperationEnvelope operation = transaction.operations()
                    .find(PaidRevivalProcessCrashChild.OPERATION)
                    .orElseThrow();
            assertEquals(expectedPhase(boundary), operation.phase(), output);

            RefundClaim claim = transaction.refunds()
                    .findByOperation(
                            PaidRevivalProcessCrashChild.OPERATION
                    ).orElse(null);
            if (boundary.mode()
                    == PaidRevivalProcessCrashChild.Mode.REFUND
                    && compensationPrepared(boundary)) {
                assertFalse(claim == null, output);
                assertEquals(
                        boundary
                                == PaidRevivalProcessCrashChild.Boundary
                                .REFUND_DURABLE_COMMITTED,
                        claim.delivered(),
                        output
                );
            } else {
                assertNull(claim, output);
            }
            assertEquals(
                    boundary.mode()
                            != PaidRevivalProcessCrashChild.Mode.NO_CHARGE
                            && liveWasEntered(boundary),
                    Files.exists(files.charge()),
                    output
            );
            assertEquals(
                    boundary.mode()
                            == PaidRevivalProcessCrashChild.Mode.SUCCESS
                            && liveWasEntered(boundary),
                    Files.exists(files.spawn()),
                    output
            );
        }
    }

    private void recoverAndVerify(
            PaidRevivalProcessCrashChild.Boundary boundary,
            SqliteConnectionFactory connections,
            CrashFiles files
    ) throws Exception {
        AtomicInteger liveCalls = new AtomicInteger();
        AtomicInteger liveMutations = new AtomicInteger();
        AtomicInteger releaseCalls = new AtomicInteger();
        AtomicInteger releaseMutations = new AtomicInteger();
        AtomicInteger refundCalls = new AtomicInteger();
        AtomicInteger refundMutations = new AtomicInteger();
        SqlitePersistenceKernel kernel =
                new SqlitePersistenceKernel(connections);
        try {
            SqlitePublicPersistenceAdapter adapter =
                    new SqlitePublicPersistenceAdapter(
                            PublicPersistenceFeatureRegistry.create(),
                            kernel,
                            PersistenceOperationAdmissionGate.allowAll(),
                            () -> PaidRevivalTestSupport.CLOCK,
                            (claim, operation) -> {
                                refundCalls.incrementAndGet();
                                if (writeOnce(files.refund(), "refund")) {
                                    refundMutations.incrementAndGet();
                                }
                                return LiveOperationResult.confirmed(
                                        "refund-receipt"
                                ).completed();
                            },
                            event -> {
                            }
                    );
            SqlitePublicRecoveryResult result = adapter.recover(
                    boundaries(
                            boundary,
                            files,
                            liveCalls,
                            liveMutations,
                            releaseCalls,
                            releaseMutations
                    ),
                    "paid-revival-process-recovery"
            ).toCompletableFuture().get(20, TimeUnit.SECONDS);

            assertEquals(
                    SqlitePublicRecoveryResult.Status.COMPLETE,
                    result.status(),
                    boundary + ": " + result.failure()
            );
            assertEquals(
                    terminalAtCrash(boundary) ? 0 : 1,
                    result.completedCount()
            );
            assertExternalCalls(
                    boundary,
                    liveCalls,
                    liveMutations,
                    releaseCalls,
                    releaseMutations,
                    refundCalls,
                    refundMutations
            );
            assertFinalState(boundary.mode(), connections, files);
        } finally {
            kernel.shutdown(Duration.ofSeconds(5));
        }
    }

    private PublicPersistenceLiveBoundaries boundaries(
            PaidRevivalProcessCrashChild.Boundary boundary,
            CrashFiles files,
            AtomicInteger liveCalls,
            AtomicInteger liveMutations,
            AtomicInteger releaseCalls,
            AtomicInteger releaseMutations
    ) {
        return new PublicPersistenceLiveBoundaries(
                (request, operation) ->
                        LiveOperationResult.confirmed("capture").completed(),
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
                                request.spawnReceiptKey()
                        ).completed(),
                new PaidRevivalBoundaries(
                        (request, operation) -> {
                            liveCalls.incrementAndGet();
                            return resolveLive(
                                    boundary.mode(),
                                    files,
                                    liveMutations
                            );
                        },
                        (request, operation) -> {
                            releaseCalls.incrementAndGet();
                            if (writeOnce(
                                    files.release(), "release"
                            )) {
                                releaseMutations.incrementAndGet();
                            }
                            return LiveOperationResult.confirmed(
                                    "release-receipt"
                            ).completed();
                        }
                )
        );
    }

    private java.util.concurrent.CompletionStage<PaidRevivalLiveResult>
            resolveLive(
            PaidRevivalProcessCrashChild.Mode mode,
            CrashFiles files,
            AtomicInteger mutations
    ) {
        if (mode == PaidRevivalProcessCrashChild.Mode.NO_CHARGE) {
            return PaidRevivalLiveResult.noCharge(
                    "no-charge-no-spawn"
            ).completed();
        }
        if (writeOnce(files.charge(), "charge")) {
            mutations.incrementAndGet();
        }
        if (mode == PaidRevivalProcessCrashChild.Mode.REFUND) {
            return PaidRevivalLiveResult.refundRequired(
                    "charge-without-spawn"
            ).completed();
        }
        if (writeOnce(files.spawn(), "spawn")) {
            mutations.incrementAndGet();
        }
        return PaidRevivalLiveResult.confirmed(
                "charge-and-spawn-receipts"
        ).completed();
    }

    private void assertExternalCalls(
            PaidRevivalProcessCrashChild.Boundary boundary,
            AtomicInteger liveCalls,
            AtomicInteger liveMutations,
            AtomicInteger releaseCalls,
            AtomicInteger releaseMutations,
            AtomicInteger refundCalls,
            AtomicInteger refundMutations
    ) {
        int expectedLiveCalls = boundary.mode()
                == PaidRevivalProcessCrashChild.Mode.SUCCESS
                && boundary
                != PaidRevivalProcessCrashChild.Boundary
                .SUCCESS_DURABLE_COMMITTED
                && !terminalAtCrash(boundary) ? 1 : 0;
        int expectedLiveMutations = boundary
                == PaidRevivalProcessCrashChild.Boundary.PREPARE_COMMITTED
                || boundary == PaidRevivalProcessCrashChild.Boundary
                .LIVE_APPLYING_COMMITTED ? 2 : 0;
        int expectedReleaseCalls = boundary.mode()
                == PaidRevivalProcessCrashChild.Mode.NO_CHARGE
                && !terminalAtCrash(boundary) ? 1 : 0;
        int expectedReleaseMutations = boundary
                == PaidRevivalProcessCrashChild.Boundary
                .NO_CHARGE_COMPENSATION_COMMITTED ? 1 : 0;
        int expectedRefundCalls = boundary.mode()
                == PaidRevivalProcessCrashChild.Mode.REFUND
                && !terminalAtCrash(boundary) ? 1 : 0;
        int expectedRefundMutations = boundary
                == PaidRevivalProcessCrashChild.Boundary
                .REFUND_CLAIM_COMMITTED ? 1 : 0;
        assertEquals(expectedLiveCalls, liveCalls.get(), boundary.name());
        assertEquals(
                expectedLiveMutations, liveMutations.get(), boundary.name()
        );
        assertEquals(
                expectedReleaseCalls, releaseCalls.get(), boundary.name()
        );
        assertEquals(
                expectedReleaseMutations,
                releaseMutations.get(),
                boundary.name()
        );
        assertEquals(
                expectedRefundCalls, refundCalls.get(), boundary.name()
        );
        assertEquals(
                expectedRefundMutations,
                refundMutations.get(),
                boundary.name()
        );
    }

    private void assertFinalState(
            PaidRevivalProcessCrashChild.Mode mode,
            SqliteConnectionFactory connections,
            CrashFiles files
    ) throws Exception {
        try (var connection = connections.openReadConnection()) {
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            OperationEnvelope operation = transaction.operations()
                    .find(PaidRevivalProcessCrashChild.OPERATION)
                    .orElseThrow();
            CompanionLifecycle lifecycle = transaction.lifecycles()
                    .findByProfile(PaidRevivalTestSupport.PROFILE)
                    .orElseThrow();
            CompanionAlias alias = transaction.identities()
                    .resolveAlias(PaidRevivalTestSupport.ALIAS)
                    .orElseThrow();
            assertEquals(
                    mode == PaidRevivalProcessCrashChild.Mode.SUCCESS
                            ? OperationPhase.PUBLISHED
                            : OperationPhase.COMPENSATED,
                    operation.phase()
            );
            assertEquals(new LifecycleRevision(2), lifecycle.revision());
            assertNull(lifecycle.activeOperationId());
            assertEquals(
                    mode == PaidRevivalProcessCrashChild.Mode.SUCCESS
                            ? LifecycleState.ACTIVE
                            : LifecycleState.DEAD_REVIVABLE,
                    lifecycle.state()
            );
            assertEquals(
                    mode == PaidRevivalProcessCrashChild.Mode.SUCCESS
                            ? CompanionAlias.State.CURRENT
                            : CompanionAlias.State.RETIRED,
                    alias.state()
            );
            assertEquals(
                    mode != PaidRevivalProcessCrashChild.Mode.SUCCESS,
                    transaction.snapshots()
                            .findById(PaidRevivalTestSupport.SNAPSHOT)
                            .orElseThrow().current()
            );
            assertEquals(
                    0,
                    transaction.populationGroups().findReservations(
                            PaidRevivalProcessCrashChild.OPERATION
                    ).size()
            );
            RefundClaim claim = transaction.refunds()
                    .findByOperation(
                            PaidRevivalProcessCrashChild.OPERATION
                    ).orElse(null);
            if (mode == PaidRevivalProcessCrashChild.Mode.REFUND) {
                assertTrue(claim.delivered());
                assertEquals(2, claim.items().size());
                assertTrue(Files.exists(files.refund()));
            } else {
                assertNull(claim);
            }
        }
    }

    private OperationPhase expectedPhase(
            PaidRevivalProcessCrashChild.Boundary boundary
    ) {
        return switch (boundary) {
            case PREPARE_COMMITTED -> OperationPhase.PREPARED;
            case LIVE_APPLYING_COMMITTED, LIVE_RECEIPTS_APPLIED,
                 SUCCESS_DURABLE_UNCOMMITTED ->
                    OperationPhase.LIVE_APPLYING;
            case SUCCESS_DURABLE_COMMITTED -> OperationPhase.DURABLE;
            case NO_CHARGE_COMPENSATION_COMMITTED,
                 NO_CHARGE_RELEASE_APPLIED,
                 NO_CHARGE_DURABLE_UNCOMMITTED,
                 REFUND_CLAIM_COMMITTED,
                 REFUND_RECEIPT_APPLIED,
                 REFUND_DURABLE_UNCOMMITTED ->
                    OperationPhase.COMPENSATING;
            case NO_CHARGE_DURABLE_COMMITTED,
                 REFUND_DURABLE_COMMITTED ->
                    OperationPhase.COMPENSATED;
        };
    }

    private boolean compensationPrepared(
            PaidRevivalProcessCrashChild.Boundary boundary
    ) {
        return expectedPhase(boundary) == OperationPhase.COMPENSATING
                || expectedPhase(boundary) == OperationPhase.COMPENSATED;
    }

    private boolean liveWasEntered(
            PaidRevivalProcessCrashChild.Boundary boundary
    ) {
        return boundary
                != PaidRevivalProcessCrashChild.Boundary.PREPARE_COMMITTED
                && boundary
                != PaidRevivalProcessCrashChild.Boundary
                .LIVE_APPLYING_COMMITTED;
    }

    private boolean terminalAtCrash(
            PaidRevivalProcessCrashChild.Boundary boundary
    ) {
        return expectedPhase(boundary) == OperationPhase.COMPENSATED;
    }

    private boolean writeOnce(Path path, String value) {
        if (Files.exists(path)) {
            return false;
        }
        try {
            Files.writeString(path, value);
            return true;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private String haltChildAt(
            PaidRevivalProcessCrashChild.Boundary boundary,
            CrashFiles files
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
                PaidRevivalProcessCrashChild.class.getName(),
                boundary.name(),
                files.database().toString(),
                files.halt().toString(),
                files.charge().toString(),
                files.spawn().toString(),
                files.release().toString(),
                files.refund().toString()
        ).redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new AssertionError(
                    "Paid revival crash child timed out at " + boundary
            );
        }
        String output = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );
        assertEquals(
                PaidRevivalProcessCrashChild.HALT_EXIT_CODE,
                process.exitValue(),
                boundary + "\n" + output
        );
        return output;
    }

    private record CrashFiles(Path lane) {
        Path database() {
            return lane.resolve("tamework-state.sqlite");
        }

        Path halt() {
            return lane.resolve("halt.txt");
        }

        Path charge() {
            return lane.resolve("charge.txt");
        }

        Path spawn() {
            return lane.resolve("spawn.txt");
        }

        Path release() {
            return lane.resolve("release.txt");
        }

        Path refund() {
            return lane.resolve("refund.txt");
        }
    }
}
