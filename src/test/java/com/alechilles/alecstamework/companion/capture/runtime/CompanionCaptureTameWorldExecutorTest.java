package com.alechilles.alecstamework.companion.capture.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.alechilles.alecstamework.companion.capture.CaptureTameAndLinkTestFixtures;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureDefinition;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureTameWorldAttempt.AccessProbe;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureTameWorldAttempt.AttemptGateway;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureTameWorldAttempt.MarkerAttempt;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureTameWorldAttempt.MutationAttempt;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureTameWorldAttempt.ReceiptPersistence;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureTameWorldAttempt.TargetProbe;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class CompanionCaptureTameWorldExecutorTest {
    private final CompanionCaptureTameWorldExecutor executor =
            new CompanionCaptureTameWorldExecutor();

    @Test
    void savesEachReceiptBeforeTheNextDestructiveBoundary() {
        FakeAttempts attempts = new FakeAttempts();

        LiveOperationResult result = execute(attempts);

        assertResult(
                LiveOperationResult.Status.CONFIRMED,
                "capture_tame_link_target_saved_exact",
                result
        );
        assertEquals(
                List.of(
                        "source-receipt",
                        "actor-save",
                        "world-resume",
                        "source-spend",
                        "actor-save",
                        "world-resume",
                        "target-marker",
                        "target-save-marker",
                        "world-resume",
                        "target-mutate",
                        "role-poll",
                        "target-save-final",
                        "world-resume"
                ),
                attempts.actions
        );
    }

    @Test
    void missingCommandAccessDoesNotInstallOrSpendAnything() {
        FakeAttempts attempts = new FakeAttempts();
        attempts.access = AccessProbe.missing();

        LiveOperationResult result = execute(attempts);

        assertResult(
                LiveOperationResult.Status.RETRYABLE,
                "capture_tame_link_command_access_item_missing",
                result
        );
        assertEquals(List.of(), attempts.actions);
    }

    @Test
    void replayOfSavedTargetPerformsNoLiveMutation() {
        FakeAttempts attempts = new FakeAttempts();
        attempts.spend = ResolvedCaptureSourceWorldExecutor
                .SpendProbe.spent();
        attempts.target = TargetProbe.target();

        LiveOperationResult result = execute(attempts);

        assertEquals(LiveOperationResult.Status.CONFIRMED, result.status());
        assertEquals(
                List.of(
                        "actor-save",
                        "world-resume",
                        "target-save-final",
                        "world-resume"
                ),
                attempts.actions
        );
    }

    @Test
    void absentOrConflictingTargetIsNeverSuccessOrCompensation() {
        FakeAttempts absent = new FakeAttempts();
        absent.target = TargetProbe.absent();
        FakeAttempts conflict = new FakeAttempts();
        conflict.target = TargetProbe.conflict(null);

        assertEquals(
                LiveOperationResult.Status.UNKNOWN,
                execute(absent).status()
        );
        assertEquals(
                LiveOperationResult.Status.UNKNOWN,
                execute(conflict).status()
        );
        assertEquals(List.of(), absent.actions);
        assertEquals(List.of(), conflict.actions);
    }

    @Test
    void onlySpentExactOriginalWithoutMarkerMayCompensate() {
        FakeAttempts attempts = new FakeAttempts();
        attempts.spend = ResolvedCaptureSourceWorldExecutor
                .SpendProbe.spent();
        attempts.roleResolvable = false;

        LiveOperationResult result = execute(attempts);

        assertResult(
                LiveOperationResult.Status.COMPENSATE,
                "capture_tame_link_target_role_permanently_unavailable",
                result
        );
        assertFalse(attempts.actions.contains("target-marker"));
    }

    @Test
    void exactMarkerForbidsCompensationEvenWhenRoleDisappears() {
        FakeAttempts attempts = new FakeAttempts();
        attempts.spend = ResolvedCaptureSourceWorldExecutor
                .SpendProbe.spent();
        attempts.target = TargetProbe.applying(false);
        attempts.roleResolvable = false;

        LiveOperationResult result = execute(attempts);

        assertEquals(LiveOperationResult.Status.RETRYABLE, result.status());
        assertFalse(result.status() == LiveOperationResult.Status.COMPENSATE);
        assertEquals(
                List.of(
                        "actor-save",
                        "world-resume",
                        "target-save-marker",
                        "world-resume"
                ),
                attempts.actions
        );
    }

    @Test
    void failedMarkerSaveCannotReachTameMutation() {
        FakeAttempts attempts = new FakeAttempts();
        attempts.targetPersistence =
                ReceiptPersistence.retryable(null);

        LiveOperationResult result = execute(attempts);

        assertEquals(LiveOperationResult.Status.RETRYABLE, result.status());
        assertFalse(attempts.actions.contains("target-mutate"));
    }

    @Test
    void failedPlayerReceiptSaveCannotSpendSource() {
        FakeAttempts attempts = new FakeAttempts();
        attempts.actorPersistence =
                ReceiptPersistence.retryable(null);

        LiveOperationResult result = execute(attempts);

        assertEquals(LiveOperationResult.Status.RETRYABLE, result.status());
        assertEquals(
                List.of("source-receipt", "actor-save"),
                attempts.actions
        );
    }

    private LiveOperationResult execute(FakeAttempts attempts) {
        return executor.execute(
                CaptureTameAndLinkTestFixtures.request(),
                operation(),
                attempts
        ).toCompletableFuture().join();
    }

    private OperationEnvelope operation() {
        return new OperationEnvelope(
                CaptureTameAndLinkTestFixtures.OPERATION,
                new IdempotencyKey("capture-tame-live-world"),
                CompanionCaptureDefinition.KIND,
                CompanionCaptureDefinition.INSTANCE.payloadVersion(),
                "{}",
                OperationPhase.LIVE_APPLYING,
                "companion_capture",
                CaptureTameAndLinkTestFixtures.EXPECTED,
                null,
                0,
                0,
                null,
                null,
                -600,
                -500,
                null,
                null,
                null,
                List.of(OperationScope.operation(
                        CaptureTameAndLinkTestFixtures.OPERATION
                ))
        );
    }

    private void assertResult(
            LiveOperationResult.Status status,
            String code,
            LiveOperationResult result
    ) {
        assertEquals(status, result.status());
        assertEquals(code, result.code());
    }

    private static final class FakeAttempts implements AttemptGateway {
        private final List<String> actions = new ArrayList<>();
        private ResolvedCaptureSourceWorldExecutor.SpendProbe spend =
                ResolvedCaptureSourceWorldExecutor.SpendProbe.source();
        private AccessProbe access = AccessProbe.present();
        private TargetProbe target = TargetProbe.unchanged();
        private boolean roleResolvable = true;
        private ReceiptPersistence actorPersistence =
                ReceiptPersistence.saved();
        private ReceiptPersistence targetPersistence =
                ReceiptPersistence.saved();

        @Override
        public ResolvedCaptureSourceWorldExecutor.SpendProbe probe() {
            return spend;
        }

        @Override
        public ResolvedCaptureSourceWorldExecutor.ReceiptAttempt
        installReceipt() {
            actions.add("source-receipt");
            spend = ResolvedCaptureSourceWorldExecutor.SpendProbe
                    .receiptedSource();
            return ResolvedCaptureSourceWorldExecutor.ReceiptAttempt
                    .receipted();
        }

        @Override
        public ResolvedCaptureSourceWorldExecutor.ConsumptionAttempt
        consumeReceiptedSource() {
            actions.add("source-spend");
            spend = ResolvedCaptureSourceWorldExecutor.SpendProbe.spent();
            return ResolvedCaptureSourceWorldExecutor.ConsumptionAttempt
                    .spent();
        }

        @Override
        public AccessProbe probeCommandAccess() {
            return access;
        }

        @Override
        public TargetProbe probeTarget() {
            return target;
        }

        @Override
        public boolean targetRoleResolvable() {
            return roleResolvable;
        }

        @Override
        public MarkerAttempt installTargetMarker() {
            actions.add("target-marker");
            target = TargetProbe.applying(false);
            return MarkerAttempt.exact();
        }

        @Override
        public MutationAttempt convergeTarget() {
            actions.add("target-mutate");
            target = TargetProbe.applying(true);
            return MutationAttempt.rolePending();
        }

        @Override
        public CompletionStage<ReceiptPersistence> persistActor() {
            actions.add("actor-save");
            return CompletableFuture.completedFuture(actorPersistence);
        }

        @Override
        public CompletionStage<ReceiptPersistence> persistTarget() {
            actions.add(target.status()
                    == CompanionCaptureTameWorldAttempt.TargetStatus.TARGET
                    ? "target-save-final"
                    : "target-save-marker");
            return CompletableFuture.completedFuture(targetPersistence);
        }

        @Override
        public CompletionStage<LiveOperationResult> resumeOnWorldThread(
                Supplier<CompletionStage<LiveOperationResult>> continuation
        ) {
            actions.add("world-resume");
            return continuation.get();
        }

        @Override
        public CompletionStage<LiveOperationResult> resumeAfterWorldTick(
                Supplier<CompletionStage<LiveOperationResult>> continuation
        ) {
            actions.add("role-poll");
            target = TargetProbe.target();
            return continuation.get();
        }
    }
}
