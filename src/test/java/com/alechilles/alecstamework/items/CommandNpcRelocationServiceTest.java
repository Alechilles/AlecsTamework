package com.alechilles.alecstamework.items;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards linked companion recall/lost timing contracts.
 */
class CommandNpcRelocationServiceTest {

    @Test
    void defaultLostDetectionWindowIsTenSeconds() throws Exception {
        String timingPolicy = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "items", "CommandRelocationTimingPolicy.java"
        ), StandardCharsets.UTF_8);
        String defaultConfig = Files.readString(Path.of(
                "src", "main", "resources", "Server", "Tamework", "Global", "TwGlobalDefault.json"
        ), StandardCharsets.UTF_8);
        String config = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "config", "assets", "TwGlobalConfig.java"
        ), StandardCharsets.UTF_8);

        assertTrue(
                timingPolicy.contains("DEFAULT_MAX_WAIT_MS = 10000L"),
                "Relocation fallback wait should mark companions lost after 10 seconds."
        );
        assertTrue(
                defaultConfig.contains("\"RelocationMaxWaitMs\": 10000"),
                "Default global config should mark companions lost after 10 seconds."
        );
        assertTrue(
                config.contains("commandRelocationMaxWaitMs = 10000"),
                "TwGlobalConfig Java fallback should match the 10 second default."
        );
    }

    @Test
    void pendingRecallSnapshotExposesCountdownForLinkedPanel() throws Exception {
        String service = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "items", "CommandNpcRelocationService.java"
        ), StandardCharsets.UTF_8);
        String entryService = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "items", "CommandLinkedPanelEntryService.java"
        ), StandardCharsets.UTF_8);
        String featureHandler = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "items", "CommandItemFeatureHandler.java"
        ), StandardCharsets.UTF_8);

        assertTrue(
                service.contains("public PendingRecallSnapshot getPendingRecallSnapshot"),
                "Relocation service should expose read-only pending recall state."
        );
        assertTrue(
                service.contains("record PendingRecallSnapshot"),
                "Pending recall snapshot should be an explicit immutable record."
        );
        assertTrue(
                service.contains("remainingUntilLostMs"),
                "Pending recall snapshot should expose the remaining time until the companion becomes lost."
        );
        assertTrue(
                entryService.contains("CommandNpcRelocationService relocationService"),
                "Linked panel entry service should receive relocation state."
        );
        assertTrue(
                entryService.contains("getPendingRecallSnapshot(record.npcUuid)"),
                "Linked panel entries should read pending recall countdown state."
        );
        assertTrue(
                featureHandler.contains("lostService,\r\n                relocationService,")
                        || featureHandler.contains("lostService,\n                relocationService,"),
                "CommandItemFeatureHandler should wire relocation state into linked panel entries."
        );
    }

    @Test
    void loadedAndQueuedRecallsUseTheAdmissionGateBeforeMoving() throws Exception {
        String service = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "items",
                "CommandNpcRelocationService.java"
        ), StandardCharsets.UTF_8);
        String dispatch = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "items",
                "CommandRelocationDispatchService.java"
        ), StandardCharsets.UTF_8);
        String gate = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "items",
                "CommandRelocationAdmissionGate.java"
        ), StandardCharsets.UTF_8);

        assertTrue(service.indexOf("now < pending.executeAfterMs")
                        < service.indexOf("if (!ensureAdmission(world, pending))"),
                "Queued delay must elapse before claim/provider admission is prepared.");
        assertTrue(service.indexOf("Ref<EntityStore> ref = world.getEntityRef(npcUuid)")
                        < service.indexOf("if (!ensureAdmission(world, pending))"),
                "Same-world recall must load the NPC and publish live population state before admission.");
        assertTrue(service.indexOf("if (!ensureAdmission(world, pending))")
                        < service.indexOf("npc.moveTo(ref, pending.destination.x"),
                "Every queued same- or cross-world move must prepare population admission first.");
        assertTrue(dispatch.contains("relocationService.queueRelocation("),
                "Loaded recalls should enter the same gate-aware relocation queue.");
        assertTrue(!dispatch.contains("candidate.npc.moveTo("),
                "Loaded recalls must not bypass claim and owner admission.");
        assertTrue(service.contains("CompanionRelocationAdmissionService.ForcePolicy.ENFORCE"),
                "Player recall must enforce policy rather than use ENGINE_RELOCATION.");
        int expectedOwner = service.indexOf("pending.admissionReserved() && !hasExpectedLiveOwner");
        int sameWorldClaim = service.indexOf("!claimAdmissionImmediatelyBeforeMutation(world, pending)");
        int sameWorldMove = service.indexOf("npc.moveTo(ref, pending.destination.x");
        assertTrue(expectedOwner >= 0 && expectedOwner < sameWorldClaim && sameWorldClaim < sameWorldMove,
                "Live owner validation and final claim must immediately precede the same-world move.");
        String sameWorldBoundary = service.substring(sameWorldClaim, sameWorldMove);
        assertTrue(!sameWorldBoundary.contains("scheduleTryApply")
                        && !sameWorldBoundary.contains("retryPending")
                        && !sameWorldBoundary.contains(".execute("),
                "No queue, retry, or dispatch may sit between final claim and same-world move.");
        assertTrue(gate.contains("pending.installReservedAdmission(decision)"),
                "Async preparation must install a RESERVED capability without claiming it.");
        int finishPreparation = gate.indexOf("private static void finishPreparation");
        int closePreparation = gate.indexOf(
                "private static void closePreparationAfterDispatchFailure", finishPreparation
        );
        assertTrue(!gate.substring(finishPreparation, closePreparation).contains("service.claimForApply"),
                "Preparation callbacks must not claim across a queued world-thread delay.");
        assertTrue(gate.contains("service.cancel(admission)"),
                "Live-state or provider changes must close the prepared reservation.");
        assertTrue(service.contains("Objects.equals(owner.getOwnerId(), pending.ownerUuid)"),
                "Final claim must re-resolve and compare the live owner component.");
        assertTrue(service.contains("scheduleTryApply(world, snapshot.npcUuid, INITIAL_APPLY_DELAY_MS)"),
                "NPC-added callbacks must resume after the later population reconciliation system.");
        assertTrue(service.contains("current.hasSameCommandIntent(pending)"),
                "Repeated recall clicks must coalesce instead of replacing an in-flight request.");
        assertTrue(gate.contains("CommandRelocationAdmissionRetryPolicy.shouldRetry(decision)"),
                "Optimistic admission conflicts must retry within the original request.");
    }

    @Test
    void finalClaimHasNoSchedulingGapAndCommitPrecedesBestEffortEffects() throws Exception {
        String service = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "items",
                "CommandNpcRelocationService.java"
        ), StandardCharsets.UTF_8);
        String gate = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "items",
                "CommandRelocationAdmissionGate.java"
        ), StandardCharsets.UTF_8);
        String effects = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "items",
                "CommandRelocationPostMoveEffects.java"
        ), StandardCharsets.UTF_8);

        int crossStart = service.indexOf("private void transferPendingAcrossWorlds");
        int crossEnd = service.indexOf("private void restoreSourceEntityAndApplyFailure", crossStart);
        String crossWorld = service.substring(crossStart, crossEnd);
        int ownerValidation = crossWorld.indexOf("hasExpectedLiveOwner(sourceStore, sourceRef, pending)");
        int finalClaim = crossWorld.indexOf("claimAdmissionImmediatelyBeforeMutation(sourceWorld, pending)");
        int sourceRemove = crossWorld.indexOf("sourceStore.removeEntity(sourceRef, RemoveReason.UNLOAD)");
        assertTrue(ownerValidation >= 0 && ownerValidation < finalClaim && finalClaim < sourceRemove,
                "Cross-world owner validation and claim must precede source removal.");
        String mutationBoundary = crossWorld.substring(finalClaim, sourceRemove);
        assertTrue(!mutationBoundary.contains("scheduleTryApply")
                        && !mutationBoundary.contains("retryPending")
                        && !mutationBoundary.contains(".execute("),
                "No queue, retry, or world dispatch may sit between final claim and source removal.");

        int relocationIssued = service.indexOf("pending.markRelocationIssued(now)");
        int move = service.indexOf("npc.moveTo(ref, pending.destination.x");
        int confirmation = service.indexOf(
                "scheduleTryApply(world, npcUuid, RELOCATION_CONFIRMATION_DELAY_MS)", move
        );
        assertTrue(relocationIssued < move && move < confirmation,
                "Move exceptions must remain APPLYING and enter destination confirmation.");

        int commit = service.indexOf("commitAdmission(world, npcUuid, pending)");
        int postMove = service.indexOf("postMoveEffects.apply(", commit);
        assertTrue(commit < postMove,
                "Population commit must start as soon as destination position is confirmed.");
        assertTrue(!effects.contains("retryPending") && !effects.contains("scheduleTryApply"),
                "Missing owner targets or state side effects must never defer a confirmed commit.");
        assertTrue(service.contains(
                        "!pending.physicalMutationAttempted() && !pending.isStateAllowed(currentState)"
                ),
                "A post-mutation state change must not cancel an APPLYING capability.");
        assertTrue(gate.contains("catch (RuntimeException | LinkageError exception)")
                        && gate.contains("relocation-admission-ready-callback-failed"),
                "A throwing ready callback must close, rather than strand, its reservation.");
        assertTrue(service.contains(
                        "retryCoordinator.keepingAdmission(world, npcUuid, pending, true)"
                ),
                "A late same-world move must retain APPLYING while confirmation is retried.");
        String retry = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "items",
                "CommandRelocationRetryCoordinator.java"
        ), StandardCharsets.UTF_8);
        assertTrue(retry.contains("CommandRelocationTimeoutDecision.decide(")
                        && retry.contains("owner.cancelObservedSameWorldRelocation("),
                "A terminal timeout must distinguish a confirmed live same-world NPC from ambiguity.");
        assertTrue(service.contains("pending.markCrossWorldDestinationInstalled()"),
                "Completed cross-world installs must not use the same-world cancellation path.");
        assertTrue(service.contains(
                        "replaced.admissionApplying() && replaced.physicalMutationAttempted()"
                ),
                "Replacing an in-flight physical relocation must conservatively commit, not cancel it.");
    }
}
