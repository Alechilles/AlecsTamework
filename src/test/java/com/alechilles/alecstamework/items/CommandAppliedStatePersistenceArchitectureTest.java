package com.alechilles.alecstamework.items;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandAppliedStatePersistenceArchitectureTest {
    @Test
    void successfulSetStateIntentOverridesThePreTickNpcStateSnapshot() throws Exception {
        String execution = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandStepExecutionService.java"
        )).replace("\r\n", "\n");
        String orchestrator = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandItemUseOrchestrator.java"
        )).replace("\r\n", "\n");
        String menuMove = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandMenuMoveService.java"
        )).replace("\r\n", "\n");
        String links = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandLinkMutationService.java"
        )).replace("\r\n", "\n");

        assertTrue(execution.contains("new RelocationState(\n                            stateStep.getState()"));
        int loadedDispatch = orchestrator.indexOf(
                "LoadedDispatch loaded = executeLoadedRecipients(context, recipients);");
        int linkedRecordRefresh = orchestrator.indexOf(
                "refreshLinkedPositions(use, context, recipients, loaded.appliedCommandStates());");
        int stepExecution = orchestrator.indexOf(
                "StepResult result = stepExecutionService.executeCommand(context, candidate);");
        int appliedStateCapture = orchestrator.indexOf(
                "recordAppliedState(appliedStates, candidate, result);");
        assertTrue(loadedDispatch >= 0 && linkedRecordRefresh > loadedDispatch);
        assertTrue(stepExecution >= 0 && appliedStateCapture > stepExecution);
        assertTrue(orchestrator.contains("String cachedState = result.appliedState.cachedValue();"));
        assertTrue(orchestrator.contains("appliedStates.put(candidate.npc.getUuid(), cachedState);"));
        assertTrue(orchestrator.contains("context.workingItem, recipients, use.store, appliedStates"));
        assertTrue(menuMove.contains("appliedCommandStates.put"));
        assertTrue(menuMove.contains("store,\n                    appliedCommandStates"));
        assertTrue(links.contains("commandState != null ? commandState : resolveCachedCommandState"));
    }

    @Test
    void interactionPassesItsActualHeldSlotIntoAsyncSourceFinalization() throws Exception {
        String interaction = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/interactions/TameworkSpawnInteraction.java"
        ));

        assertTrue(interaction.contains("(int) context.getHeldItemSlot()"));
    }

}
