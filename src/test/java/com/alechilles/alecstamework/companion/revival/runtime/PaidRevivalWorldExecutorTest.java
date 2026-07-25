package com.alechilles.alecstamework.companion.revival.runtime;

import com.alechilles.alecstamework.companion.revival.PaidRevivalLiveResult;
import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.ChargeAttempt;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.ProjectionAttempt;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.ChargeProbe;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.ReceiptProbe;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.SpawnProbe;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Crash-seam, replay, ordering, and economic disposition coverage. */
class PaidRevivalWorldExecutorTest {
    private final PaidRevivalWorldExecutor executor =
            new PaidRevivalWorldExecutor();

    @Test
    void receiptAndChargeAreSavedBeforeProjectionAndTargetSave()
            throws Exception {
        PaidRevivalRequest request =
                PaidRevivalWorldTestFixture.request(false);
        FakePaidRevivalWorldAttempts attempts =
                new FakePaidRevivalWorldAttempts();

        PaidRevivalLiveResult result = execute(request, attempts);

        assertStatus(PaidRevivalLiveResult.Status.CONFIRMED, result);
        assertEquals(
                List.of(
                        "probe", "install", "save-actor", "resume",
                        "probe", "charge", "save-actor", "resume",
                        "probe", "project", "save-target", "resume",
                        "probe-target"
                ),
                attempts.calls
        );
    }

    @Test
    void crashAfterReceiptSaveResumesExactConsumption() throws Exception {
        PaidRevivalRequest request =
                PaidRevivalWorldTestFixture.request(false);
        FakePaidRevivalWorldAttempts attempts =
                new FakePaidRevivalWorldAttempts();
        attempts.receipt = ReceiptProbe.exact();

        PaidRevivalLiveResult result = execute(request, attempts);

        assertStatus(PaidRevivalLiveResult.Status.CONFIRMED, result);
        assertFalse(attempts.calls.contains("install"));
        assertEquals(1, count(attempts.calls, "charge"));
    }

    @Test
    void crashAfterChargeSaveResumesProjectionWithoutDoubleCharge()
            throws Exception {
        PaidRevivalRequest request =
                PaidRevivalWorldTestFixture.request(false);
        FakePaidRevivalWorldAttempts attempts =
                new FakePaidRevivalWorldAttempts();
        attempts.receipt = ReceiptProbe.exact();
        attempts.charge = ChargeProbe.charged();

        PaidRevivalLiveResult result = execute(request, attempts);

        assertStatus(PaidRevivalLiveResult.Status.CONFIRMED, result);
        assertFalse(attempts.calls.contains("charge"));
        assertEquals(1, count(attempts.calls, "project"));
    }

    @Test
    void crashAfterSpawnResavesAndReadsExactTargetWithoutRespawn()
            throws Exception {
        PaidRevivalRequest request =
                PaidRevivalWorldTestFixture.request(false);
        FakePaidRevivalWorldAttempts attempts =
                new FakePaidRevivalWorldAttempts();
        attempts.receipt = ReceiptProbe.exact();
        attempts.charge = ChargeProbe.charged();
        attempts.spawn = SpawnProbe.exact(
                PaidRevivalWorldTestFixture.TARGET_CHUNK
        );

        PaidRevivalLiveResult result = execute(request, attempts);

        assertStatus(PaidRevivalLiveResult.Status.CONFIRMED, result);
        assertFalse(attempts.calls.contains("charge"));
        assertFalse(attempts.calls.contains("project"));
        assertEquals(1, attempts.targetSaveCalls);
    }

    @Test
    void emptyRecipeStillUsesReceiptAndExactSpawnDurability()
            throws Exception {
        PaidRevivalRequest request =
                PaidRevivalWorldTestFixture.request(true);
        FakePaidRevivalWorldAttempts attempts =
                FakePaidRevivalWorldAttempts.emptyCost();

        PaidRevivalLiveResult result = execute(request, attempts);

        assertStatus(PaidRevivalLiveResult.Status.CONFIRMED, result);
        assertFalse(attempts.calls.contains("charge"));
        assertEquals(1, attempts.actorSaveCalls);
        assertEquals(1, attempts.targetSaveCalls);
    }

    @Test
    void positiveUnchangedRecipeAndSpawnAbsenceAreNoCharge()
            throws Exception {
        PaidRevivalRequest request =
                PaidRevivalWorldTestFixture.request(false);
        FakePaidRevivalWorldAttempts attempts =
                new FakePaidRevivalWorldAttempts();
        attempts.chargeAttempt = ChargeAttempt.unchanged(null);

        PaidRevivalLiveResult result = execute(request, attempts);

        assertStatus(PaidRevivalLiveResult.Status.NO_CHARGE, result);
        assertEquals(0, attempts.targetSaveCalls);
        assertFalse(attempts.calls.contains("project"));
    }

    @Test
    void terminalSpawnAbsenceAfterNonemptyChargeRequiresRefund()
            throws Exception {
        PaidRevivalRequest request =
                PaidRevivalWorldTestFixture.request(false);
        FakePaidRevivalWorldAttempts attempts =
                new FakePaidRevivalWorldAttempts();
        attempts.projection = ProjectionAttempt.terminalAbsent(null);

        PaidRevivalLiveResult result = execute(request, attempts);

        assertStatus(PaidRevivalLiveResult.Status.REFUND_REQUIRED, result);
        assertEquals(0, attempts.targetSaveCalls);
    }

    @Test
    void terminalSpawnAbsenceForEmptyRecipeIsNoCharge()
            throws Exception {
        PaidRevivalRequest request =
                PaidRevivalWorldTestFixture.request(true);
        FakePaidRevivalWorldAttempts attempts =
                FakePaidRevivalWorldAttempts.emptyCost();
        attempts.projection = ProjectionAttempt.terminalAbsent(null);

        PaidRevivalLiveResult result = execute(request, attempts);

        assertStatus(PaidRevivalLiveResult.Status.NO_CHARGE, result);
    }

    @Test
    void retryableProjectionAfterChargeRetainsFenceDisposition()
            throws Exception {
        PaidRevivalRequest request =
                PaidRevivalWorldTestFixture.request(false);
        FakePaidRevivalWorldAttempts attempts =
                new FakePaidRevivalWorldAttempts();
        attempts.projection = ProjectionAttempt.retryable(
                new IllegalStateException("chunk unavailable")
        );

        PaidRevivalLiveResult result = execute(request, attempts);

        assertStatus(PaidRevivalLiveResult.Status.RETRYABLE, result);
        assertEquals(ChargeProbe.charged(), attempts.charge);
        assertEquals(0, attempts.targetSaveCalls);
    }

    private PaidRevivalLiveResult execute(
            PaidRevivalRequest request,
            FakePaidRevivalWorldAttempts attempts
    ) throws Exception {
        return executor.execute(
                request,
                PaidRevivalWorldTestFixture.operation(request),
                attempts
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private int count(List<String> calls, String value) {
        return (int) calls.stream().filter(value::equals).count();
    }

    private void assertStatus(
            PaidRevivalLiveResult.Status expected,
            PaidRevivalLiveResult actual
    ) {
        assertEquals(expected, actual.status(), actual.code());
    }
}
