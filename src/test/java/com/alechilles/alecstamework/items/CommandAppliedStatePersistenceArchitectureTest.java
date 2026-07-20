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
        String handler = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandItemFeatureHandler.java"
        )).replace("\r\n", "\n");
        String menuMove = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandMenuMoveService.java"
        )).replace("\r\n", "\n");
        String links = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandLinkMutationService.java"
        )).replace("\r\n", "\n");

        assertTrue(execution.contains("new RelocationState(\n                            stateStep.getState()"));
        assertTrue(handler.contains("appliedCommandStates.put"));
        assertTrue(handler.contains("recipients, store, appliedCommandStates"));
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

    @Test
    void pendingSpawnerRecoveryRequiresExactSourceBeforeClosingAndReconciling() throws Exception {
        String recovery = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/SpawnerPendingSourceRecoveryService.java"
        ));

        int sourceMutation = recovery.indexOf("source.prepare(replacement)");
        int journalClose = recovery.indexOf("repository.completeSourceFinalizationAsync");

        assertTrue(recovery.contains("State.APPLIED"));
        assertTrue(recovery.contains("Kind.SPAWNER_ITEM"));
        assertTrue(recovery.contains("SpawnerSourceSlotResolver.resolveMatching("));
        assertTrue(sourceMutation >= 0);
        assertTrue(journalClose > sourceMutation);
        assertTrue(recovery.contains("restartReconciliationAfterExternalRepair"));
    }
}
