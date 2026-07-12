package com.alechilles.alecstamework.npc.actions;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static guards for the world-thread snapshot and asynchronous durability seam. */
class BreedingCaptureCancellationWiringTest {
    @Test
    void publicHandoffIsWorldThreadBoundAndCarriesNoLiveStateOffThread() throws Exception {
        String service = source("BreedingCaptureCancellationService.java");
        String handoff = source("BreedingCaptureSnapshotFenceHandoff.java");
        String combined = service + handoff;

        assertTrue(service.contains("store.assertThread()"));
        assertTrue(service.contains("cancelThenCaptureSnapshot"));
        assertTrue(service.contains("cancelForCapturedParentDurably"));
        assertTrue(service.contains("cancellationAttempts.findAll("));
        assertTrue(service.contains("BreedingCaptureSnapshotFenceHandoff.capture("));
        assertTrue(handoff.contains("snapshotCapture.capture()"));
        assertTrue(handoff.contains("failedCaptureRelease.run()"));
        assertTrue(service.contains("releaseCaptureFenceInScope("));
        assertFalse(combined.contains("Executor"));
        assertFalse(combined.contains("supplyAsync"));
        assertFalse(combined.contains("PlayerRef"));
        assertFalse(combined.contains("Universe"));
        assertFalse(combined.contains("sqlite"));
    }

    @Test
    void liveRollbackChecksFingerprintBeforeMutationAndClearsOnlyOwnedPresentation() throws Exception {
        String rollback = source("BreedingCaptureParentRollbackService.java");
        int fingerprintCheck = rollback.indexOf("parentStateService.matchesFingerprint");
        int stateRestore = rollback.indexOf("parentStateService.restoreIfFingerprintMatches");

        assertTrue(fingerprintCheck >= 0 && fingerprintCheck < stateRestore);
        assertTrue(rollback.contains("hook.matchesHook(PAIR_HOOK_ID)"));
        assertTrue(rollback.contains("Objects.equals(current, partnerRef)"));
        assertTrue(rollback.contains("PAIR_STATE.equalsIgnoreCase(state.getStateName())"));
        assertTrue(rollback.contains("ownedPresentation && clearLockedPartner"));
        assertTrue(rollback.contains("SKIPPED_NEWER_STATE"));
        assertFalse(rollback.contains("Universe"));
        assertFalse(rollback.contains("PlayerRef"));
        assertFalse(rollback.contains("world.execute"));
        assertFalse(rollback.contains("NpcProfileRepository"));
    }

    private static String source(String fileName) throws Exception {
        return Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/actions",
                fileName
        )).replace("\r\n", "\n");
    }
}
