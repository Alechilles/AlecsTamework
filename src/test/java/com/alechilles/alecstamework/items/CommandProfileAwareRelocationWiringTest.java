package com.alechilles.alecstamework.items;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the event-boundary wiring that keeps SQLite out of relocation retry callbacks. */
class CommandProfileAwareRelocationWiringTest {
    private static final Path ITEMS = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework", "items");

    @Test
    void commandAndWorldChangeRelocationResolveProfileBeforeQueueing() throws Exception {
        String handler = read(ITEMS.resolve("CommandItemFeatureHandler.java"));
        String recipients = read(ITEMS.resolve("CommandRecipientService.java"));
        String menu = read(ITEMS.resolve("CommandMenuMoveService.java"));
        String inventory = read(ITEMS.resolve("CommandToolInventoryService.java"));

        assertTrue(handler.contains("new CommandNpcProfileActionResolver(npcIdentityService)"));
        assertTrue(handler.contains("resolveRelocationRecord(cachedRecord)"));
        assertTrue(handler.contains("linkMutationService.writeLinkedNpcRecords(stack, linkedRecords)"));
        assertTrue(recipients.contains("resolveRelocationRecord(cachedRecord)"));
        assertTrue(recipients.contains(
                "linkedNpcRecordStore.write(context.workingItem, canonical.records())"));
        assertTrue(menu.contains("resolveRelocationTarget(record)"));
        assertTrue(menu.contains("replaceResolvedSelection("));
        assertTrue(menu.contains("linkMutationService.writeLinkedNpcRecords(stack, repairedRecords)"));
        assertTrue(handler.contains("transaction != null && transaction.succeeded()"));
        assertTrue(menu.contains("transaction != null && transaction.succeeded()"));
        assertTrue(inventory.contains("transaction != null && transaction.succeeded()"));

        int genericCommit = handler.indexOf(
                "if (!canonicalRecordCommitGate.commitBeforeAction(",
                handler.indexOf("queryUnloadedLinkedRecords"));
        int loadedExecution = handler.indexOf("int affected = 0", genericCommit);
        int genericQueue = handler.indexOf("queueRelocationsForUnloaded", genericCommit);
        assertTrue(genericCommit >= 0 && genericCommit < loadedExecution);
        assertTrue(genericCommit < genericQueue);

        assertFalse(menu.contains("profileActionResolver.canonicalizeRecords(linkedRecords)"),
                "A selected companion action must not be blocked by unrelated damaged links.");
        int menuCommit = menu.indexOf("canonicalRecordCommitGate.commitBeforeAction");
        int menuQueue = menu.indexOf("queueRelocationsForUnloaded", menuCommit);
        assertTrue(menuCommit >= 0 && menuCommit < menuQueue);
    }

    @Test
    void terminalDropRevalidatesProfileButRetryLoopDoesNotReadPersistence() throws Exception {
        String lost = read(ITEMS.resolve("CommandLinkedNpcLostService.java"));
        String relocation = read(ITEMS.resolve("CommandNpcRelocationService.java"));
        String plugin = read(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "Tamework.java"));

        assertTrue(lost.contains("profileActionResolver.resolveLostTransition(droppedNpcUuid)"));
        assertTrue(plugin.contains("persistenceRuntime.getNpcIdentityRepository()"));
        assertTrue(plugin.contains(
                "commandLinkedNpcStateSnapshotService.getLoadedNpcIdentityIndex()"));
        assertFalse(relocation.contains("NpcIdentityRepository"));
        assertFalse(relocation.contains("CommandNpcProfileActionResolver"));
    }

    private String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
