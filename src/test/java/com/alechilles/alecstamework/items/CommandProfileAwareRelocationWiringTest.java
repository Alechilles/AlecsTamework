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
        String travel = read(ITEMS.resolve(
                "CommandWorldChangeTravelCoordinator.java"));
        String useOrchestrator = read(ITEMS.resolve("CommandItemUseOrchestrator.java"));
        String recipients = read(ITEMS.resolve("CommandRecipientService.java"));
        String menu = read(ITEMS.resolve("CommandMenuMoveService.java"));
        String inventory = read(ITEMS.resolve("CommandToolInventoryService.java"));

        assertTrue(handler.contains("new CommandNpcProfileActionResolver(npcIdentityService)"));
        assertTrue(travel.contains("resolveRelocationRecord(cachedRecord)"));
        assertTrue(travel.contains("linkMutationService.writeLinkedNpcRecords("));
        assertTrue(recipients.contains("resolveRelocationRecord(cachedRecord)"));
        assertTrue(recipients.contains(
                "linkedNpcRecordStore.write(context.workingItem, canonical.records())"));
        assertTrue(menu.contains("resolveRelocationTarget(record)"));
        assertTrue(menu.contains("replaceResolvedSelection("));
        assertTrue(menu.contains("linkMutationService.writeLinkedNpcRecords(stack, repairedRecords)"));
        assertTrue(travel.contains("transaction != null && transaction.succeeded()"));
        assertTrue(menu.contains("transaction != null && transaction.succeeded()"));
        assertTrue(inventory.contains("transaction != null && transaction.succeeded()"));

        int unloadedQuery = useOrchestrator.indexOf(
                "recipientService.queryUnloadedLinkedRecords(context, recipients)");
        int genericCommit = useOrchestrator.indexOf(
                "if (!commitCanonicalItemIfNeeded(use, context))", unloadedQuery);
        int dispatch = useOrchestrator.indexOf(
                "return dispatchRecipients(use, context, recipients, unloaded, cooldownMs)",
                genericCommit
        );
        assertTrue(unloadedQuery >= 0 && unloadedQuery < genericCommit);
        assertTrue(genericCommit < dispatch);

        int loadedExecution = useOrchestrator.indexOf(
                "LoadedDispatch loaded = executeLoadedRecipients(");
        int genericQueue = useOrchestrator.indexOf(
                "relocationDispatchService.queueRelocationsForUnloaded(",
                loadedExecution
        );
        assertTrue(loadedExecution >= 0 && loadedExecution < genericQueue);

        assertFalse(menu.contains("profileActionResolver.canonicalizeRecords(linkedRecords)"),
                "A selected companion action must not be blocked by unrelated damaged links.");
        int menuCommit = menu.indexOf("canonicalRecordCommitGate.commitBeforeAction");
        int menuQueue = menu.indexOf("queueRelocationsForUnloaded", menuCommit);
        assertTrue(menuCommit >= 0 && menuCommit < menuQueue);
    }

    @Test
    void terminalDropCannotReadPersistenceOrInferLost() throws Exception {
        String relocation = read(ITEMS.resolve("CommandNpcRelocationService.java"));
        String retry = read(ITEMS.resolve("CommandRelocationRetryCoordinator.java"));
        String handler = read(ITEMS.resolve("CommandItemFeatureHandler.java"));
        String reporter = read(ITEMS.resolve(
                "CommandRelocationDropReporter.java"
        ));

        assertTrue(Files.notExists(
                ITEMS.resolve("CommandLinkedNpcLostService.java")
        ));
        assertFalse(handler.contains("CommandLinkedNpcLostService"));
        assertFalse(relocation.contains("NpcIdentityRepository"));
        assertFalse(relocation.contains("CommandNpcProfileActionResolver"));
        assertFalse(retry.contains("CommandPersistenceView"));
        assertFalse(retry.contains("CommandNpcProfileActionResolver"));
        assertFalse(reporter.contains("lostTransitionSubmitted"));
        assertTrue(reporter.contains(
                "no lifecycle transition was inferred"
        ));
    }

    private String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
