package com.alechilles.alecstamework.items;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protects the instance-teardown regression where an ACTIVE companion became unreachable after
 * its delete-on-remove source world was destroyed.
 */
class CommandDeleteOnRemoveWorldRecoveryWiringTest {
    private static final Path ROOT = Path.of("src", "main", "java", "com", "alechilles", "alecstamework");
    private static final Path ITEMS = ROOT.resolve("items");

    @Test
    void removeWorldEventSubmitsRecoveryBeforeTemporaryWorldShutdown() throws Exception {
        String plugin = read(ROOT.resolve("Tamework.java"));
        String relocation = read(ITEMS.resolve("CommandNpcRelocationService.java"));
        String tracker = read(ITEMS.resolve("CommandRelocationNpcTracker.java"));

        assertTrue(plugin.contains("this::onWorldRemovedForCompanionRecovery"));
        assertTrue(plugin.contains("event != null && !event.isCancelled()"));
        assertTrue(plugin.contains("commandNpcRelocationService.onWorldRemoved(event.getWorld())"));
        assertTrue(tracker.contains("world.getWorldConfig().isDeleteOnRemove()"));
        assertTrue(relocation.contains("npcTracker.detachDeleteOnRemoveWorld(world)"));
        assertTrue(relocation.contains("dropReporter.reportWorldRemoval(candidate, removedAtMs)"));
        assertTrue(relocation.contains("private final CommandRelocationNpcTracker npcTracker"),
                "The oversized relocation orchestrator must delegate NPC lifecycle tracking.");
    }

    @Test
    void terminalDiagnosticsDistinguishSubmissionFromDurableLostState() throws Exception {
        String reporter = read(ITEMS.resolve("CommandRelocationDropReporter.java"));
        String listener = read(ITEMS.resolve("CommandRelocationDropListener.java"));
        String lost = read(ITEMS.resolve("CommandLinkedNpcLostService.java"));

        assertTrue(listener.contains("boolean onRelocationDropped"));
        assertTrue(lost.contains("public boolean recordLostFromRelocationDrop"));
        assertTrue(reporter.contains("lostTransitionSubmitted="));
        assertFalse(reporter.contains("Dropped relocation as lost"));
    }

    private String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
