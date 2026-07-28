package com.alechilles.alecstamework.companion.provisioning.runtime;

import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationRequest;
import com.alechilles.alecstamework.companion.provisioning.runtime.ProvisioningActivationWorldAttempt.ChunkPersistence;
import com.alechilles.alecstamework.companion.provisioning.runtime.ProvisioningActivationWorldAttempt.ProjectionAttempt;
import com.alechilles.alecstamework.companion.provisioning.runtime.ProvisioningActivationWorldAttempt.ProjectionProbe;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ordering, crash replay, and fail-closed activation protocol coverage. */
class ProvisioningActivationWorldExecutorTest {
    private final ProvisioningActivationWorldExecutor executor =
            new ProvisioningActivationWorldExecutor();

    @Test
    void appliesSavesAndReadsBackExactFrozenProjection() throws Exception {
        FakeProvisioningActivationWorldAttempt attempt =
                new FakeProvisioningActivationWorldAttempt();

        LiveOperationResult result = execute(
                ProvisioningActivationWorldTestFixture.request(), attempt
        );

        assertStatus(LiveOperationResult.Status.CONFIRMED, result);
        assertEquals(
                List.of(
                        "probe",
                        "project",
                        "save-target",
                        "resume",
                        "probe-target"
                ),
                attempt.calls
        );
    }

    @Test
    void crashAfterProjectionResavesWithoutDuplicateSpawn() throws Exception {
        FakeProvisioningActivationWorldAttempt attempt =
                new FakeProvisioningActivationWorldAttempt();
        attempt.projection = ProjectionProbe.exact(
                ProvisioningActivationWorldTestFixture.TARGET_CHUNK
        );

        LiveOperationResult result = execute(
                ProvisioningActivationWorldTestFixture.request(), attempt
        );

        assertStatus(LiveOperationResult.Status.CONFIRMED, result);
        assertFalse(attempt.calls.contains("project"));
        assertEquals(1, attempt.saveCalls);
    }

    @Test
    void failedChunkSaveReplaysFromExactProjection() throws Exception {
        FakeProvisioningActivationWorldAttempt attempt =
                new FakeProvisioningActivationWorldAttempt();
        attempt.saveFailureCall = 1;
        ProvisioningActivationRequest request =
                ProvisioningActivationWorldTestFixture.request();

        LiveOperationResult failed = execute(request, attempt);
        LiveOperationResult replayed = execute(request, attempt);

        assertStatus(LiveOperationResult.Status.RETRYABLE, failed);
        assertStatus(LiveOperationResult.Status.CONFIRMED, replayed);
        assertEquals(1, count(attempt.calls, "project"));
        assertEquals(2, attempt.saveCalls);
    }

    @Test
    void unchangedProjectionAndAbsenceRemainRetryable() throws Exception {
        FakeProvisioningActivationWorldAttempt attempt =
                new FakeProvisioningActivationWorldAttempt();
        attempt.projectionAttempt = ProjectionAttempt.unchanged(null);

        LiveOperationResult result = execute(
                ProvisioningActivationWorldTestFixture.request(), attempt
        );

        assertStatus(LiveOperationResult.Status.RETRYABLE, result);
        assertTrue(result.code().endsWith("projection_remains_absent"));
        assertEquals(0, attempt.saveCalls);
    }

    @Test
    void absenceAfterChunkSaveCannotConfirmCompletion() throws Exception {
        FakeProvisioningActivationWorldAttempt attempt =
                new FakeProvisioningActivationWorldAttempt();
        attempt.finalProjection = ProjectionProbe.absent();

        LiveOperationResult result = execute(
                ProvisioningActivationWorldTestFixture.request(), attempt
        );

        assertStatus(LiveOperationResult.Status.UNKNOWN, result);
        assertTrue(result.code().endsWith("durable_readback_absent"));
    }

    @Test
    void unavailableProbeIsRetryableWithoutMutation() throws Exception {
        FakeProvisioningActivationWorldAttempt attempt =
                new FakeProvisioningActivationWorldAttempt();
        attempt.projection = ProjectionProbe.unavailable(
                new IllegalStateException("world unavailable")
        );

        LiveOperationResult result = execute(
                ProvisioningActivationWorldTestFixture.request(), attempt
        );

        assertStatus(LiveOperationResult.Status.RETRYABLE, result);
        assertEquals(List.of("probe"), attempt.calls);
    }

    @Test
    void conflictingProjectionOrSaveEvidenceFailsClosed() throws Exception {
        FakeProvisioningActivationWorldAttempt projectionConflict =
                new FakeProvisioningActivationWorldAttempt();
        projectionConflict.projection = ProjectionProbe.conflict(null);
        FakeProvisioningActivationWorldAttempt saveConflict =
                new FakeProvisioningActivationWorldAttempt();
        saveConflict.persistence = ChunkPersistence.conflict(null);

        LiveOperationResult projection = execute(
                ProvisioningActivationWorldTestFixture.request(),
                projectionConflict
        );
        LiveOperationResult save = execute(
                ProvisioningActivationWorldTestFixture.request(),
                saveConflict
        );

        assertStatus(LiveOperationResult.Status.UNKNOWN, projection);
        assertStatus(LiveOperationResult.Status.UNKNOWN, save);
    }

    @Test
    void payloadMismatchStopsBeforeWorldAccess() throws Exception {
        ProvisioningActivationRequest expected =
                ProvisioningActivationWorldTestFixture.request();
        FakeProvisioningActivationWorldAttempt attempt =
                new FakeProvisioningActivationWorldAttempt();

        LiveOperationResult result = executor.execute(
                ProvisioningActivationWorldTestFixture.request(
                        "different-receipt"
                ),
                ProvisioningActivationWorldTestFixture.operation(expected),
                attempt
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertStatus(LiveOperationResult.Status.UNKNOWN, result);
        assertTrue(attempt.calls.isEmpty());
    }

    @Test
    void exceptionalSaveAndWorldResumeStayRetryable() throws Exception {
        FakeProvisioningActivationWorldAttempt saveFailure =
                new FakeProvisioningActivationWorldAttempt();
        saveFailure.saveThrows = true;
        FakeProvisioningActivationWorldAttempt resumeFailure =
                new FakeProvisioningActivationWorldAttempt();
        resumeFailure.resumeThrows = true;

        LiveOperationResult save = execute(
                ProvisioningActivationWorldTestFixture.request(),
                saveFailure
        );
        LiveOperationResult resume = execute(
                ProvisioningActivationWorldTestFixture.request(),
                resumeFailure
        );

        assertStatus(LiveOperationResult.Status.RETRYABLE, save);
        assertStatus(LiveOperationResult.Status.RETRYABLE, resume);
    }

    private LiveOperationResult execute(
            ProvisioningActivationRequest request,
            FakeProvisioningActivationWorldAttempt attempt
    ) throws Exception {
        return executor.execute(
                request,
                ProvisioningActivationWorldTestFixture.operation(request),
                attempt
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private int count(List<String> calls, String value) {
        return (int) calls.stream().filter(value::equals).count();
    }

    private void assertStatus(
            LiveOperationResult.Status expected,
            LiveOperationResult actual
    ) {
        assertEquals(expected, actual.status(), actual.code());
    }
}
