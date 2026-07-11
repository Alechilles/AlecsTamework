package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.RetirementReady;
import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceEvidence.CapturedItemSource;
import com.alechilles.alecstamework.items.ManagedCoopItemCaptureRecoveryService.Outcome;
import com.alechilles.alecstamework.items.ManagedCoopItemCaptureRecoveryService.ReceiptResolution;
import com.alechilles.alecstamework.items.ManagedCoopItemCaptureRecoveryService.RecoveryStatus;
import com.alechilles.alecstamework.items.ManagedCoopItemIntakeHandler.ItemRetirementReceipt;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Restart recovery coverage for crash-after-receipt and receipt-conflict paths. */
class ManagedCoopItemCaptureRecoveryServiceTest {
    private static final UUID NPC = new UUID(0L, 91L);
    private static final String FINGERPRINT = "a".repeat(64);
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world", 1, 2, 3);

    @Test
    void exactReceiptFinalizesThenCleansAfterRestart() {
        List<String> order = new ArrayList<>();
        ManagedCoopItemCaptureRecoveryService recovery =
                new ManagedCoopItemCaptureRecoveryService(
                        (ready, source) -> {
                            order.add("verify");
                            return CompletableFuture.completedFuture(ReceiptResolution.verified(
                                    new ItemRetirementReceipt(
                                            FINGERPRINT,
                                            ready.operationId(),
                                            () -> {
                                                order.add("cleanup");
                                                return CompletableFuture.completedFuture(true);
                                            }
                                    )));
                        },
                        ready -> {
                            order.add("finalize");
                            return CompletableFuture.completedFuture(
                                    new ManagedCoopItemCaptureFinalizer.Outcome(true, null));
                        }
                );

        Outcome outcome = recovery.recover(ready(), resident(itemSnapshot()))
                .toCompletableFuture().join();

        assertTrue(outcome.completed());
        assertEquals(List.of("verify", "finalize", "cleanup"), order);
    }

    @Test
    void mismatchedReceiptOrOfflinePlayerNeverFinalizes() {
        AtomicInteger finalizations = new AtomicInteger();
        ManagedCoopItemCaptureRecoveryService conflict = service(
                ReceiptResolution.conflict("receipt_mismatch"), finalizations);
        ManagedCoopItemCaptureRecoveryService offline = service(
                ReceiptResolution.waiting("player_offline"), finalizations);

        Outcome conflictOutcome = conflict.recover(ready(), resident(itemSnapshot()))
                .toCompletableFuture().join();
        Outcome offlineOutcome = offline.recover(ready(), resident(itemSnapshot()))
                .toCompletableFuture().join();

        assertEquals(RecoveryStatus.BLOCKED, conflictOutcome.status());
        assertEquals(RecoveryStatus.WAITING, offlineOutcome.status());
        assertEquals(0, finalizations.get());
    }

    @Test
    void entitySourceInvalidMarkerAndHashMismatchFailClosed() {
        AtomicInteger verifications = new AtomicInteger();
        ManagedCoopItemCaptureRecoveryService recovery =
                new ManagedCoopItemCaptureRecoveryService(
                        (ready, source) -> {
                            verifications.incrementAndGet();
                            return CompletableFuture.completedFuture(
                                    ReceiptResolution.waiting("unexpected"));
                        },
                        ready -> CompletableFuture.completedFuture(
                                new ManagedCoopItemCaptureFinalizer.Outcome(true, null))
                );

        Outcome entity = recovery.recover(ready(), resident("{}"))
                .toCompletableFuture().join();
        ResidentRecord corrupted = resident(itemSnapshot());
        corrupted = new ResidentRecord(
                corrupted.residentId(), corrupted.authorityKey(), corrupted.coopId(),
                corrupted.residentSlot(), corrupted.profileId(), corrupted.roleId(),
                corrupted.residentUuid(), corrupted.sourceNpcUuid(), corrupted.deployedNpcUuid(),
                corrupted.snapshotJson(), "b".repeat(64), corrupted.snapshotVersion(),
                corrupted.state(), corrupted.generation(), corrupted.active(),
                corrupted.capturedAtMs(), corrupted.releasedAtMs(), corrupted.createdAtMs(),
                corrupted.updatedAtMs());
        Outcome invalidHash = recovery.recover(ready(), corrupted)
                .toCompletableFuture().join();

        assertFalse(entity.completed());
        assertEquals(RecoveryStatus.BLOCKED, entity.status());
        assertEquals(RecoveryStatus.BLOCKED, invalidHash.status());
        assertEquals(0, verifications.get());
    }

    private ManagedCoopItemCaptureRecoveryService service(
            ReceiptResolution resolution,
            AtomicInteger finalizations) {
        return new ManagedCoopItemCaptureRecoveryService(
                (ready, source) -> CompletableFuture.completedFuture(resolution),
                ready -> {
                    finalizations.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            new ManagedCoopItemCaptureFinalizer.Outcome(true, null));
                }
        );
    }

    private String itemSnapshot() {
        return ManagedCoopCaptureSourceEvidence.markCapturedItem(
                "{}",
                new CapturedItemSource(
                        new UUID(0L, 92L), (short) 2,
                        "Tool_Capture_Crate", FINGERPRINT)
        );
    }

    private RetirementReady ready() {
        String snapshot = itemSnapshot();
        return new RetirementReady(
                NPC, "profile-a", "resident-a", "capture-a", AUTHORITY, "coop-a", 0,
                ManagedCoopCaptureClaimValidator.snapshotSha256(snapshot),
                2L, OperationState.SOURCE_RETIRE_REQUESTED, 1L
        );
    }

    private ResidentRecord resident(String snapshot) {
        String hash = ManagedCoopCaptureClaimValidator.snapshotSha256(snapshot);
        return new ResidentRecord(
                "resident-a", AUTHORITY, "coop-a", 0, "profile-a", "mob_chicken",
                NPC, NPC, null, snapshot, hash, 1, ResidentState.HOUSED,
                0L, true, -100L, 0L, -100L, -100L
        );
    }
}
