package com.alechilles.alecstamework.items;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protects the positive-evidence world-deletion boundary used to make loaded companions dormant.
 */
class CommandDeleteOnRemoveWorldRecoveryWiringTest {
    private static final Path ROOT = Path.of("src", "main", "java", "com", "alechilles", "alecstamework");
    private static final Path ITEMS = ROOT.resolve("items");

    @Test
    void terminalDeleteOnRemoveEventScansLoadedNpcsThroughCanonicalDormantAuthor() throws Exception {
        String composition = read(ROOT.resolve("TameworkPersistenceComposition.java"));
        String registration = read(ROOT.resolve("TameworkDormantPersistenceRegistration.java"));
        String worldRemoval = read(ITEMS.resolve(
                "persistence/DormantCompanionWorldRemovalBridge.java"
        ));

        assertTrue(composition.contains("TameworkDormantPersistenceRegistration.register("));
        assertTrue(registration.contains("new DormantCompanionWorldRemovalBridge("));
        assertTrue(registration.contains("Short.MAX_VALUE"),
                "Dormant evidence must observe final cancellation state.");
        assertTrue(registration.contains("RemoveWorldEvent.class"));
        assertTrue(registration.contains("worldRemoval::onWorldRemoved"));
        assertTrue(worldRemoval.contains("!event.isCancelled()"));
        assertTrue(worldRemoval.contains("isDeleteOnRemove()"));
        assertTrue(worldRemoval.contains("store.forEachEntityParallel("));
        assertTrue(worldRemoval.contains("bridge.onWorldDeletion("),
                "World deletion must enter the same canonical dormant author as ECS evidence.");
        assertTrue(worldRemoval.contains(
                "CompletableFuture.runAsync(() -> scan(world), world)"
        ));
        assertTrue(worldRemoval.contains(".whenCompleteAsync("));
        assertFalse(worldRemoval.contains(".join()"));
        assertFalse(worldRemoval.contains(".get()"));
    }

    /** Protects the 2026-07-20 persistent-world Recall failure from GIGATestWorld. */
    @Test
    void persistentWorldRemovalIsNotAcceptedAsDormantEvidence() throws Exception {
        String plugin = read(ROOT.resolve("Tamework.java"));
        String bootstrap = read(ITEMS.resolve("LoadedNpcIdentityBootstrapService.java"));
        String worldRemoval = read(ITEMS.resolve(
                "persistence/DormantCompanionWorldRemovalBridge.java"
        ));

        int predicateStart = worldRemoval.indexOf(
                "static boolean authoritativeWorldDeletion"
        );
        int predicateEnd = worldRemoval.indexOf(
                "private Throwable unwrap", predicateStart
        );
        assertTrue(predicateStart >= 0 && predicateEnd > predicateStart);
        String predicate = worldRemoval.substring(predicateStart, predicateEnd);

        assertTrue(predicate.contains("!event.isCancelled()"));
        assertTrue(predicate.contains("isDeleteOnRemove()"),
                "Ordinary persistent-world removal is not proof that a companion became dormant.");
        assertFalse(bootstrap.contains("RemoveWorldEvent"),
                "The live identity index must not trust cancellable persistent-world removal.");
        assertFalse(plugin.contains("onWorldRemovedForCompanionRecovery"),
                "The superseded bulk Lost-recovery event path must remain disconnected.");
    }

    @Test
    void relocationTimeoutCannotSubmitDurableLostState() throws Exception {
        String reporter = read(ITEMS.resolve("CommandRelocationDropReporter.java"));
        String relocation = read(ITEMS.resolve(
                "CommandNpcRelocationService.java"
        ));

        assertTrue(Files.notExists(
                ITEMS.resolve("CommandRelocationDropListener.java")
        ));
        assertTrue(Files.notExists(
                ITEMS.resolve("CommandLinkedNpcLostService.java")
        ));
        assertTrue(reporter.contains(
                "no lifecycle transition was inferred"
        ));
        assertFalse(reporter.contains("lostTransitionSubmitted"));
        assertFalse(relocation.contains("setRelocationDropListener"));
    }

    private String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
