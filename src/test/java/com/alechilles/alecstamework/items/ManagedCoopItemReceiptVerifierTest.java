package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.RetirementReady;
import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceEvidence.CapturedItemSource;
import com.alechilles.alecstamework.items.ManagedCoopItemReceiptVerifier.VerificationStatus;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exact receipt version, operation, fingerprint, item, and filled-state validation tests. */
class ManagedCoopItemReceiptVerifierTest {
    private static final String FINGERPRINT = "d".repeat(64);
    private static final CapturedItemSource SOURCE = new CapturedItemSource(
            new UUID(0L, 81L), (short) 3, "Tool_Capture_Crate", FINGERPRINT);
    private final ManagedCoopItemRetirementReceiptCodec codec =
            new ManagedCoopItemRetirementReceiptCodec();
    private final ManagedCoopItemReceiptVerifier verifier = new ManagedCoopItemReceiptVerifier(codec);

    @Test
    void exactReceiptOnlyItemIsVerified() {
        ManagedCoopItemReceiptVerifier.Verification result = verifier.verify(
                ready(), SOURCE, "Tool_Capture_Crate", 1,
                codec.encode("capture-a", FINGERPRINT), false, false);

        assertEquals(VerificationStatus.VERIFIED, result.status());
    }

    @Test
    void missingOfflineOrNotYetRetiredStateWaits() {
        assertEquals(VerificationStatus.WAITING,
                verifier.verify(ready(), SOURCE, null, 0, null, false, false).status());
        assertEquals(VerificationStatus.WAITING,
                verifier.verify(ready(), SOURCE, "Other_Item", 1,
                        codec.encode("capture-a", FINGERPRINT), false, false).status());
        assertEquals(VerificationStatus.WAITING,
                verifier.verify(ready(), SOURCE, "Tool_Capture_Crate", 1,
                        codec.encode("capture-a", FINGERPRINT), true, true).status());
    }

    @Test
    void malformedOrMismatchedReceiptConflicts() {
        assertEquals(VerificationStatus.CONFLICT,
                verifier.verify(ready(), SOURCE, "Tool_Capture_Crate", 1,
                        "{\"version\":\"2\"}", false, false).status());
        assertEquals(VerificationStatus.CONFLICT,
                verifier.verify(ready(), SOURCE, "Tool_Capture_Crate", 1,
                        codec.encode("capture-other", FINGERPRINT), false, false).status());
        assertEquals(VerificationStatus.CONFLICT,
                verifier.verify(ready(), SOURCE, "Tool_Capture_Crate", 1,
                        codec.encode("capture-a", "e".repeat(64)), false, false).status());
    }

    private RetirementReady ready() {
        return new RetirementReady(
                new UUID(0L, 82L), "profile-a", "resident-a", "capture-a",
                new ManagedCoopAuthorityKey("world", 1, 2, 3), "coop-a", 0,
                "f".repeat(64), 2L, OperationState.SOURCE_RETIRE_REQUESTED, 1L
        );
    }
}
