package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.capture.CaptureSourceEvidence;
import com.alechilles.alecstamework.companion.capture.CaptureTameAndLinkTestFixtures;
import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureDefinition;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureWorldExecutor.AttemptGateway;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureWorldExecutor.InventoryProbe;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureWorldExecutor.ReplacementAttempt;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureWorldExecutor.RetirementAttempt;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureWorldExecutor.TargetProbe;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression coverage for every receipt and ambiguity branch in live capture. */
class CompanionCaptureWorldExecutorTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");
    private static final NpcAlias TARGET =
            NpcAlias.parse("20000000-0000-0000-0000-000000000001");
    private static final SnapshotId SNAPSHOT =
            SnapshotId.parse("50000000-0000-0000-0000-000000000001");
    private static final OperationId OPERATION =
            OperationId.parse("60000000-0000-0000-0000-000000000001");
    private final CompanionCaptureWorldExecutor executor =
            new CompanionCaptureWorldExecutor();

    @Test
    void exactArtifactAndAbsentTargetConfirmFromPositiveReceipt() {
        FakeAttempts attempts = new FakeAttempts();
        attempts.inventory = InventoryProbe.artifact();
        attempts.target = TargetProbe.absent();

        LiveOperationResult result = execute(attempts);

        assertResult(
                LiveOperationResult.Status.CONFIRMED,
                "capture_artifact_receipt_target_absent",
                result
        );
        assertEquals(0, attempts.replacements);
        assertEquals(0, attempts.retirements);
    }

    @Test
    void exactSourceAndAbsentTargetRetryWithoutWritingInventory() {
        FakeAttempts attempts = new FakeAttempts();
        attempts.inventory = InventoryProbe.source();
        attempts.target = TargetProbe.absent();

        LiveOperationResult result = execute(attempts);

        assertResult(
                LiveOperationResult.Status.RETRYABLE,
                "capture_target_absent_before_receipt",
                result
        );
        assertEquals(0, attempts.replacements);
        assertEquals(0, attempts.retirements);
    }

    @Test
    void exactSourceAndTargetReplaceThenRetire() {
        FakeAttempts attempts = new FakeAttempts();
        attempts.inventory = InventoryProbe.source();
        attempts.replacement = ReplacementAttempt.artifact();
        attempts.retirement = RetirementAttempt.absent();

        LiveOperationResult result = execute(attempts);

        assertResult(
                LiveOperationResult.Status.CONFIRMED,
                "capture_artifact_receipt_target_retired",
                result
        );
        assertEquals(1, attempts.replacements);
        assertEquals(1, attempts.retirements);
    }

    @Test
    void failedCasWithExactSourceReadbackIsRetryable() {
        FakeAttempts attempts = new FakeAttempts();
        attempts.inventory = InventoryProbe.source();
        attempts.replacement = ReplacementAttempt.sourceUnchanged(null);

        LiveOperationResult result = execute(attempts);

        assertResult(
                LiveOperationResult.Status.RETRYABLE,
                "capture_inventory_source_unchanged",
                result
        );
        assertEquals(0, attempts.retirements);
    }

    @Test
    void failedCasWithArtifactReadbackContinuesRetirement() {
        FakeAttempts attempts = new FakeAttempts();
        attempts.inventory = InventoryProbe.source();
        attempts.replacement = ReplacementAttempt.artifact();
        attempts.retirement = RetirementAttempt.absent();

        LiveOperationResult result = execute(attempts);

        assertEquals(LiveOperationResult.Status.CONFIRMED, result.status());
        assertEquals(1, attempts.retirements);
    }

    @Test
    void ambiguousInventoryMutationFailsClosedWithoutRetirement() {
        FakeAttempts attempts = new FakeAttempts();
        attempts.inventory = InventoryProbe.source();
        attempts.replacement = ReplacementAttempt.ambiguous(
                new IllegalStateException("readback failed")
        );

        LiveOperationResult result = execute(attempts);

        assertResult(
                LiveOperationResult.Status.UNKNOWN,
                "capture_inventory_mutation_ambiguous",
                result
        );
        assertEquals(0, attempts.retirements);
    }

    @Test
    void sourceOrArtifactConflictIsUnknownBeforeTargetAccess() {
        FakeAttempts attempts = new FakeAttempts();
        attempts.inventory = InventoryProbe.conflict(null);

        LiveOperationResult result = execute(attempts);

        assertResult(
                LiveOperationResult.Status.UNKNOWN,
                "capture_inventory_conflict",
                result
        );
        assertEquals(0, attempts.targetProbes);
    }

    @Test
    void targetIdentityConflictIsUnknownWithoutMutation() {
        FakeAttempts attempts = new FakeAttempts();
        attempts.inventory = InventoryProbe.source();
        attempts.target = TargetProbe.conflict(null);

        LiveOperationResult result = execute(attempts);

        assertResult(
                LiveOperationResult.Status.UNKNOWN,
                "capture_target_identity_conflict",
                result
        );
        assertEquals(0, attempts.replacements);
    }

    @Test
    void targetStillValidAfterRemovalIsRetryable() {
        FakeAttempts attempts = new FakeAttempts();
        attempts.inventory = InventoryProbe.artifact();
        attempts.retirement = RetirementAttempt.stillPresent();

        LiveOperationResult result = execute(attempts);

        assertResult(
                LiveOperationResult.Status.RETRYABLE,
                "capture_artifact_receipt_target_still_present",
                result
        );
    }

    @Test
    void removalExceptionAfterReceiptIsRetryable() {
        FakeAttempts attempts = new FakeAttempts();
        attempts.inventory = InventoryProbe.artifact();
        attempts.retirement = RetirementAttempt.retryable(
                new IllegalStateException("remove failed")
        );

        LiveOperationResult result = execute(attempts);

        assertResult(
                LiveOperationResult.Status.RETRYABLE,
                "capture_artifact_receipt_target_retirement_failed",
                result
        );
    }

    @Test
    void removalIdentityConflictAfterReceiptIsUnknown() {
        FakeAttempts attempts = new FakeAttempts();
        attempts.inventory = InventoryProbe.artifact();
        attempts.retirement = RetirementAttempt.conflict(null);

        LiveOperationResult result = execute(attempts);

        assertResult(
                LiveOperationResult.Status.UNKNOWN,
                "capture_target_identity_conflict",
                result
        );
    }

    @Test
    void operationKindAndRevisionAreValidatedBeforeWorldAccess() {
        FakeAttempts attempts = new FakeAttempts();
        OperationEnvelope wrong = operation(
                new OperationKind("companion_coop_capture"),
                new LifecycleRevision(7)
        );

        LiveOperationResult result = executor.execute(
                request(),
                wrong,
                attempts
        );

        assertResult(
                LiveOperationResult.Status.UNKNOWN,
                "capture_operation_invariant_mismatch",
                result
        );
        assertEquals(0, attempts.inventoryProbes);
    }

    @Test
    void tameAndLinkRequestsCannotEnterArtifactCaptureExecutor() {
        FakeAttempts attempts = new FakeAttempts();

        LiveOperationResult result = executor.execute(
                CaptureTameAndLinkTestFixtures.request(),
                operation(
                        CompanionCaptureDefinition.KIND,
                        CaptureTameAndLinkTestFixtures.EXPECTED
                ),
                attempts
        );

        assertResult(
                LiveOperationResult.Status.UNKNOWN,
                "capture_operation_invariant_mismatch",
                result
        );
        assertEquals(0, attempts.inventoryProbes);
    }

    private LiveOperationResult execute(FakeAttempts attempts) {
        return executor.execute(
                request(),
                operation(
                        CompanionCaptureDefinition.KIND,
                        LifecycleRevision.INITIAL
                ),
                attempts
        );
    }

    private CompanionCaptureRequest request() {
        String payload = "{\"role\":\"wolf\"}";
        return new CompanionCaptureRequest(
                PROFILE,
                LifecycleRevision.INITIAL,
                null,
                TARGET,
                "world",
                new CompanionSnapshot(
                        SNAPSHOT,
                        PROFILE,
                        CompanionCaptureRequest.SNAPSHOT_KIND,
                        CompanionCaptureRequest.SNAPSHOT_VERSION,
                        payload,
                        Sha256Hash.ofUtf8(payload),
                        new LifecycleRevision(1),
                        true,
                        -700
                ),
                CapturedArtifact.create(
                        "capture-device-filled",
                        1,
                        0.0D,
                        0.0D,
                        "{\"" + TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID
                                + "\":\"" + SNAPSHOT + "\"}"
                ),
                new CaptureSourceEvidence(
                        UUID.fromString(
                                "40000000-0000-0000-0000-000000000001"
                        ),
                        "world",
                        2,
                        "capture-device",
                        1,
                        Sha256Hash.ofUtf8("source"),
                        SNAPSHOT.toString()
                ),
                -600
        );
    }

    private OperationEnvelope operation(
            OperationKind kind,
            LifecycleRevision expectedRevision
    ) {
        return new OperationEnvelope(
                OPERATION,
                new IdempotencyKey("capture-live-world"),
                kind,
                2,
                "{}",
                OperationPhase.LIVE_APPLYING,
                "capture-live-world",
                expectedRevision,
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
                List.of(OperationScope.operation(OPERATION))
        );
    }

    private void assertResult(
            LiveOperationResult.Status status,
            String code,
            LiveOperationResult actual
    ) {
        assertEquals(status, actual.status());
        assertEquals(code, actual.code());
    }

    private static final class FakeAttempts implements AttemptGateway {
        private InventoryProbe inventory = InventoryProbe.artifact();
        private TargetProbe target = TargetProbe.exact();
        private ReplacementAttempt replacement =
                ReplacementAttempt.artifact();
        private RetirementAttempt retirement = RetirementAttempt.absent();
        private int inventoryProbes;
        private int targetProbes;
        private int replacements;
        private int retirements;

        @Override
        public InventoryProbe probeInventory() {
            inventoryProbes++;
            return inventory;
        }

        @Override
        public TargetProbe probeTarget() {
            targetProbes++;
            return target;
        }

        @Override
        public ReplacementAttempt replaceSourceWithArtifact() {
            replacements++;
            return replacement;
        }

        @Override
        public RetirementAttempt retireExactTarget() {
            retirements++;
            return retirement;
        }
    }
}
