package com.alechilles.alecstamework.companion.revival.runtime;

import com.alechilles.alecstamework.companion.revival.PaidRevivalLiveResult;
import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.ChargeAttempt;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.ProjectionAttempt;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.ChargeProbe;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.ReceiptProbe;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.SpawnProbe;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Fail-closed coverage for unavailable, conflicting, and ambiguous attempts. */
class PaidRevivalWorldExecutorFailureTest {
    private final PaidRevivalWorldExecutor executor =
            new PaidRevivalWorldExecutor();

    @Test
    void nullInvariantInputsAreUnknown() throws Exception {
        PaidRevivalRequest request =
                PaidRevivalWorldTestFixture.request(false);
        FakePaidRevivalWorldAttempts attempts =
                new FakePaidRevivalWorldAttempts();

        assertStatus(
                PaidRevivalLiveResult.Status.UNKNOWN,
                executor.execute(
                        null,
                        PaidRevivalWorldTestFixture.operation(request),
                        attempts
                ).toCompletableFuture().get(5, TimeUnit.SECONDS)
        );
        assertStatus(
                PaidRevivalLiveResult.Status.UNKNOWN,
                executor.execute(request, null, attempts)
                        .toCompletableFuture().get(5, TimeUnit.SECONDS)
        );
        assertStatus(
                PaidRevivalLiveResult.Status.UNKNOWN,
                executor.execute(
                        request,
                        PaidRevivalWorldTestFixture.operation(request),
                        null
                ).toCompletableFuture().get(5, TimeUnit.SECONDS)
        );
    }

    @Test
    void unavailableProbeIsRetryableButThrownProbeIsUnknown()
            throws Exception {
        FakePaidRevivalWorldAttempts unavailable =
                new FakePaidRevivalWorldAttempts();
        unavailable.receipt = ReceiptProbe.unavailable(
                new IllegalStateException("player offline")
        );
        assertStatus(
                PaidRevivalLiveResult.Status.RETRYABLE,
                execute(unavailable)
        );

        FakePaidRevivalWorldAttempts thrown =
                new FakePaidRevivalWorldAttempts();
        thrown.probeThrows = true;
        assertStatus(PaidRevivalLiveResult.Status.UNKNOWN, execute(thrown));
    }

    @Test
    void sourcePartialAndTargetConflictsAreUnknown() throws Exception {
        FakePaidRevivalWorldAttempts partial =
                new FakePaidRevivalWorldAttempts();
        partial.charge = ChargeProbe.partial(null);
        assertStatus(PaidRevivalLiveResult.Status.UNKNOWN, execute(partial));

        FakePaidRevivalWorldAttempts target =
                new FakePaidRevivalWorldAttempts();
        target.spawn = SpawnProbe.conflict(null);
        assertStatus(PaidRevivalLiveResult.Status.UNKNOWN, execute(target));
    }

    @Test
    void ambiguousMutationExceptionsAreUnknown() throws Exception {
        FakePaidRevivalWorldAttempts install =
                new FakePaidRevivalWorldAttempts();
        install.installThrows = true;
        assertStatus(PaidRevivalLiveResult.Status.UNKNOWN, execute(install));

        FakePaidRevivalWorldAttempts charge =
                new FakePaidRevivalWorldAttempts();
        charge.receipt = ReceiptProbe.exact();
        charge.chargeThrows = true;
        assertStatus(PaidRevivalLiveResult.Status.UNKNOWN, execute(charge));

        FakePaidRevivalWorldAttempts projection =
                chargedAttempts();
        projection.projectionThrows = true;
        assertStatus(
                PaidRevivalLiveResult.Status.UNKNOWN,
                execute(projection)
        );
    }

    @Test
    void partialChargeAttemptIsUnknownAndCannotRefund() throws Exception {
        FakePaidRevivalWorldAttempts attempts =
                new FakePaidRevivalWorldAttempts();
        attempts.receipt = ReceiptProbe.exact();
        attempts.chargeAttempt = ChargeAttempt.partial(null);

        PaidRevivalLiveResult result = execute(attempts);

        assertStatus(PaidRevivalLiveResult.Status.UNKNOWN, result);
        assertFalse(
                result.status()
                        == PaidRevivalLiveResult.Status.REFUND_REQUIRED
        );
    }

    @Test
    void bothActorSaveSeamsAreRetryable() throws Exception {
        FakePaidRevivalWorldAttempts receiptSave =
                new FakePaidRevivalWorldAttempts();
        receiptSave.actorSaveFailureCall = 1;
        assertStatus(
                PaidRevivalLiveResult.Status.RETRYABLE,
                execute(receiptSave)
        );

        FakePaidRevivalWorldAttempts chargeSave =
                new FakePaidRevivalWorldAttempts();
        chargeSave.actorSaveFailureCall = 2;
        assertStatus(
                PaidRevivalLiveResult.Status.RETRYABLE,
                execute(chargeSave)
        );
    }

    @Test
    void actorSaveNullAndTargetSaveFailureAreRetryable()
            throws Exception {
        FakePaidRevivalWorldAttempts actor =
                new FakePaidRevivalWorldAttempts();
        actor.actorSaveNull = true;
        assertStatus(
                PaidRevivalLiveResult.Status.RETRYABLE,
                execute(actor)
        );

        FakePaidRevivalWorldAttempts target = chargedAttempts();
        target.targetSaveFails = true;
        assertStatus(
                PaidRevivalLiveResult.Status.RETRYABLE,
                execute(target)
        );
    }

    @Test
    void worldResumeFailuresAreRetryable() throws Exception {
        FakePaidRevivalWorldAttempts thrown =
                new FakePaidRevivalWorldAttempts();
        thrown.resumeThrows = true;
        assertStatus(
                PaidRevivalLiveResult.Status.RETRYABLE,
                execute(thrown)
        );

        FakePaidRevivalWorldAttempts missing =
                new FakePaidRevivalWorldAttempts();
        missing.resumeNull = true;
        assertStatus(
                PaidRevivalLiveResult.Status.RETRYABLE,
                execute(missing)
        );
    }

    @Test
    void chargedSaveReadbackRegressionIsUnknown() throws Exception {
        FakePaidRevivalWorldAttempts attempts =
                new FakePaidRevivalWorldAttempts();
        attempts.chargeAfterActorSaveCall = 2;
        attempts.chargeAfterActorSave = ChargeProbe.unchanged();

        PaidRevivalLiveResult result = execute(attempts);

        assertStatus(PaidRevivalLiveResult.Status.UNKNOWN, result);
        assertFalse(attempts.calls.contains("project"));
    }

    @Test
    void missingFinalSpawnAfterSaveIsUnknownNotRefund()
            throws Exception {
        FakePaidRevivalWorldAttempts attempts = chargedAttempts();
        attempts.targetReadbackAbsent = true;

        PaidRevivalLiveResult result = execute(attempts);

        assertStatus(PaidRevivalLiveResult.Status.UNKNOWN, result);
        assertFalse(
                result.status()
                        == PaidRevivalLiveResult.Status.REFUND_REQUIRED
        );
    }

    @Test
    void terminalAbsenceWithUnavailableChargeCannotRefund()
            throws Exception {
        FakePaidRevivalWorldAttempts attempts = chargedAttempts();
        attempts.projection = ProjectionAttempt.terminalAbsent(null);
        attempts.chargeAfterProjection = ChargeProbe.unavailable(null);

        assertStatus(
                PaidRevivalLiveResult.Status.RETRYABLE,
                execute(attempts)
        );
    }

    private FakePaidRevivalWorldAttempts chargedAttempts() {
        FakePaidRevivalWorldAttempts attempts =
                new FakePaidRevivalWorldAttempts();
        attempts.receipt = ReceiptProbe.exact();
        attempts.charge = ChargeProbe.charged();
        return attempts;
    }

    private PaidRevivalLiveResult execute(
            FakePaidRevivalWorldAttempts attempts
    ) throws Exception {
        PaidRevivalRequest request =
                PaidRevivalWorldTestFixture.request(false);
        return executor.execute(
                request,
                PaidRevivalWorldTestFixture.operation(request),
                attempts
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private void assertStatus(
            PaidRevivalLiveResult.Status expected,
            PaidRevivalLiveResult actual
    ) {
        assertEquals(expected, actual.status(), actual.code());
    }
}
