package com.alechilles.alecstamework.items;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards released recall behavior and the post-release relocation reliability fixes. */
class CommandNpcRelocationServiceTest {

    @Test
    void defaultLostDetectionWindowIsTenSeconds() throws Exception {
        String timingPolicy = source("CommandRelocationTimingPolicy.java");
        String defaultConfig = Files.readString(Path.of(
                "src", "main", "resources", "Server", "Tamework", "Global",
                "TwGlobalDefault.json"
        ), StandardCharsets.UTF_8);
        String config = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework",
                "config", "assets", "TwGlobalConfig.java"
        ), StandardCharsets.UTF_8);

        assertTrue(timingPolicy.contains("DEFAULT_MAX_WAIT_MS = 10000L"));
        assertTrue(defaultConfig.contains("\"RelocationMaxWaitMs\": 10000"));
        assertTrue(config.contains("commandRelocationMaxWaitMs = 10000"));
    }

    @Test
    void pendingRecallSnapshotExposesRetryDropCountdownForLinkedPanel() throws Exception {
        String service = source("CommandNpcRelocationService.java");
        String entryService = source("CommandLinkedPanelEntryService.java");
        String featureHandler = source("CommandItemFeatureHandler.java");

        assertTrue(service.contains("public PendingRecallSnapshot getPendingRecallSnapshot"));
        assertTrue(service.contains("record PendingRecallSnapshot"));
        assertTrue(service.contains("remainingUntilDropMs"));
        assertTrue(entryService.contains("CommandNpcRelocationService relocationService"));
        assertTrue(entryService.contains("getPendingRecallSnapshot(record.npcUuid)"));
        assertTrue(featureHandler.contains("relocationService,"));
        assertFalse(featureHandler.contains("CommandLinkedNpcLostService"));
    }

    @Test
    void relocationRevalidatesOwnerAndCoalescesRepeatedClicksBeforeMoving() throws Exception {
        String service = source("CommandNpcRelocationService.java");
        String dispatch = source("CommandRelocationDispatchService.java");

        int ownerCheck = service.indexOf("if (!hasExpectedLiveOwner(store, ref, pending))");
        int move = service.indexOf("npc.moveTo(ref, pending.destination.x");
        assertTrue(ownerCheck >= 0 && ownerCheck < move);
        assertTrue(service.contains("Objects.equals(owner.getOwnerId(), pending.ownerUuid)"));
        assertTrue(service.contains("current.hasSameCommandIntent(pending)"));
        assertTrue(dispatch.contains("relocationService.queueRelocation("));
        assertTrue(dispatch.contains(
                "relocationService.rememberSourceWorld(record.npcUuid, record.lastKnownWorldName)"
        ));
        assertFalse(dispatch.contains("candidate.npc.moveTo("));
    }

    @Test
    void crossWorldTransferWaitsForDestinationAndCannotRestartAfterInstall() throws Exception {
        String service = source("CommandNpcRelocationService.java");
        String chunkRequests = source("CommandRelocationChunkRequestService.java");
        int installedGuard = service.indexOf(
                "pending.crossWorldDestinationInstalled()",
                service.indexOf("private boolean maybeStartCrossWorldTransfer")
        );
        int sourceResolution = service.indexOf("World sourceWorld =", installedGuard);

        assertTrue(installedGuard >= 0 && installedGuard < sourceResolution);
        assertTrue(service.contains("!chunkRequests.isDestinationReady(destinationWorld, pending)"));
        assertTrue(chunkRequests.contains("pending.markChunkReady(worldName, chunkX, chunkZ)"));
        assertTrue(service.contains(
                "transferHolders.drainForDestination("
        ));
        assertTrue(service.contains(
                "transferHolders.restoreSource(drainedHolder, sourceTransform)"
        ));
    }

    @Test
    void crossWorldTransferMakesSourceRemovalDurableBeforePreservingHolder() throws Exception {
        String holderService = source("CommandRelocationTransferHolderService.java");
        int drainStart = holderService.indexOf("DrainResult drainForDestination");
        int markDirty = holderService.indexOf("sourceTransform.markChunkDirty(sourceStore)", drainStart);
        int unload = holderService.indexOf(
                "sourceStore.removeEntity(sourceRef, RemoveReason.UNLOAD)", drainStart);

        assertTrue(drainStart >= 0 && markDirty > drainStart);
        assertTrue(unload > markDirty, "The source chunk must be dirty before UNLOAD.");
        assertFalse(holderService.substring(markDirty, unload).contains("RemoveReason.REMOVE"));
    }

    private static String source(String fileName) throws Exception {
        return Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework",
                "items", fileName
        ), StandardCharsets.UTF_8);
    }
}
