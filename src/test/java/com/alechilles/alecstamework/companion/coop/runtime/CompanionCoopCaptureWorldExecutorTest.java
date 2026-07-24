package com.alechilles.alecstamework.companion.coop.runtime;

import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureDefinition;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CoopCaptureSourceEvidence;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCaptureWorldExecutor.AttemptGateway;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCaptureWorldExecutor.ReceiptPersistence;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCaptureWorldExecutor.ReceiptProbe;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCaptureWorldExecutor.RetirementAttempt;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCaptureWorldExecutor.SourceProbe;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Crash, ordering, and replay coverage for receipt-first coop source retirement. */
class CompanionCoopCaptureWorldExecutorTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");
    private static final NpcAlias SOURCE =
            NpcAlias.parse("20000000-0000-0000-0000-000000000001");
    private static final CoopSlotKey SLOT =
            new CoopSlotKey("world", "coop", 10, 64, 20, 0);

    @Test
    void crashBeforeChunkSaveCompletionCannotRetireSource() {
        FakeAttempts attempts = new FakeAttempts(
                ReceiptProbe.absent(), SourceProbe.exact()
        );
        CompletableFuture<LiveOperationResult> result =
                new CompanionCoopCaptureWorldExecutor().execute(
                        request(), operation(true), attempts
                ).toCompletableFuture();

        assertFalse(result.isDone());
        assertEquals(0, attempts.retirementCalls);
        assertEquals(
                List.of("receipt", "source", "persist"),
                attempts.events
        );
    }

    @Test
    void sourceRetiresOnlyAfterDurableSaveAndWorldThreadResume()
            throws Exception {
        FakeAttempts attempts = new FakeAttempts(
                ReceiptProbe.absent(), SourceProbe.exact()
        );
        CompletableFuture<LiveOperationResult> result =
                new CompanionCoopCaptureWorldExecutor().execute(
                        request(), operation(true), attempts
                ).toCompletableFuture();

        attempts.receipt = ReceiptProbe.exact();
        attempts.events.add("save-complete");
        attempts.persistence.complete(ReceiptPersistence.saved());
        LiveOperationResult resolved = result.get(5, TimeUnit.SECONDS);

        assertEquals(LiveOperationResult.Status.CONFIRMED, resolved.status());
        assertEquals(
                "coop_capture_durable_receipt_source_retired",
                resolved.code()
        );
        assertEquals(1, attempts.retirementCalls);
        assertTrue(
                attempts.events.indexOf("save-complete")
                        < attempts.events.indexOf("retire")
        );
        assertTrue(
                attempts.events.indexOf("resume")
                        < attempts.events.indexOf("retire")
        );
    }

    @Test
    void replayWithExactReceiptAndAbsentSourceStillForceSavesReceipt()
            throws Exception {
        FakeAttempts attempts = new FakeAttempts(
                ReceiptProbe.exact(), SourceProbe.absent()
        );
        CompletableFuture<LiveOperationResult> result =
                new CompanionCoopCaptureWorldExecutor().execute(
                        request(), operation(true), attempts
                ).toCompletableFuture();

        assertFalse(result.isDone());
        attempts.persistence.complete(ReceiptPersistence.saved());
        LiveOperationResult resolved = result.get(5, TimeUnit.SECONDS);

        assertEquals(LiveOperationResult.Status.CONFIRMED, resolved.status());
        assertEquals(
                "coop_capture_durable_receipt_source_absent",
                resolved.code()
        );
        assertEquals(0, attempts.retirementCalls);
        assertTrue(attempts.events.contains("persist"));
    }

    @Test
    void absentSourceWithoutExactReceiptIsUnknownAndNeverSaved()
            throws Exception {
        FakeAttempts attempts = new FakeAttempts(
                ReceiptProbe.absent(), SourceProbe.absent()
        );

        LiveOperationResult result =
                new CompanionCoopCaptureWorldExecutor().execute(
                        request(), operation(true), attempts
                ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(LiveOperationResult.Status.UNKNOWN, result.status());
        assertEquals(
                "coop_capture_source_absent_without_receipt",
                result.code()
        );
        assertFalse(attempts.events.contains("persist"));
        assertEquals(0, attempts.retirementCalls);
    }

    @Test
    void failedChunkSaveIsRetryableAndLeavesSourceLive()
            throws Exception {
        FakeAttempts attempts = new FakeAttempts(
                ReceiptProbe.absent(), SourceProbe.exact()
        );
        CompletableFuture<LiveOperationResult> result =
                new CompanionCoopCaptureWorldExecutor().execute(
                        request(), operation(true), attempts
                ).toCompletableFuture();

        attempts.persistence.complete(ReceiptPersistence.retryable(
                new IllegalStateException("save failed")
        ));
        LiveOperationResult resolved = result.get(5, TimeUnit.SECONDS);

        assertEquals(LiveOperationResult.Status.RETRYABLE, resolved.status());
        assertEquals(0, attempts.retirementCalls);
    }

    @Test
    void operationMustOwnBothProfileAndPhysicalSlot() throws Exception {
        FakeAttempts attempts = new FakeAttempts(
                ReceiptProbe.absent(), SourceProbe.exact()
        );

        LiveOperationResult result =
                new CompanionCoopCaptureWorldExecutor().execute(
                        request(), operation(false), attempts
                ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(LiveOperationResult.Status.UNKNOWN, result.status());
        assertTrue(attempts.events.isEmpty());
    }

    private CompanionCoopCaptureRequest request() {
        String payload = "{\"health\":100}";
        return new CompanionCoopCaptureRequest(
                PROFILE,
                LifecycleRevision.INITIAL,
                SLOT,
                new CompanionSnapshot(
                        SnapshotId.parse(
                                "50000000-0000-0000-0000-000000000001"
                        ),
                        PROFILE,
                        CompanionCoopCaptureRequest.SNAPSHOT_KIND,
                        1,
                        payload,
                        Sha256Hash.ofUtf8(payload),
                        new LifecycleRevision(1),
                        true,
                        -700
                ),
                new CoopCaptureSourceEvidence(
                        SOURCE, "world", "capture-receipt"
                ),
                -600
        );
    }

    private OperationEnvelope operation(boolean includeSlotScope) {
        OperationId id = OperationId.parse(
                "60000000-0000-0000-0000-000000000001"
        );
        List<OperationScope> scopes = new ArrayList<>();
        scopes.add(OperationScope.operation(id));
        scopes.add(OperationScope.profile(PROFILE));
        if (includeSlotScope) {
            scopes.add(OperationScope.coop(SLOT.toString()));
        }
        return new OperationEnvelope(
                id,
                new IdempotencyKey("coop-capture-executor"),
                CompanionCoopCaptureDefinition.KIND,
                1,
                "{}",
                OperationPhase.LIVE_APPLYING,
                "companion_coop_capture",
                LifecycleRevision.INITIAL,
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
                scopes
        );
    }

    private static final class FakeAttempts implements AttemptGateway {
        private ReceiptProbe receipt;
        private SourceProbe source;
        private final CompletableFuture<ReceiptPersistence> persistence =
                new CompletableFuture<>();
        private final List<String> events = new ArrayList<>();
        private int retirementCalls;

        private FakeAttempts(ReceiptProbe receipt, SourceProbe source) {
            this.receipt = receipt;
            this.source = source;
        }

        @Override
        public ReceiptProbe probeReceipt() {
            events.add("receipt");
            return receipt;
        }

        @Override
        public SourceProbe probeSource() {
            events.add("source");
            return source;
        }

        @Override
        public CompletableFuture<ReceiptPersistence> persistExactReceipt() {
            events.add("persist");
            return persistence;
        }

        @Override
        public CompletableFuture<LiveOperationResult> resumeOnWorldThread(
                Supplier<LiveOperationResult> continuation
        ) {
            events.add("resume");
            return CompletableFuture.completedFuture(continuation.get());
        }

        @Override
        public RetirementAttempt retireExactSource() {
            events.add("retire");
            retirementCalls++;
            source = SourceProbe.absent();
            return RetirementAttempt.absent();
        }
    }
}
