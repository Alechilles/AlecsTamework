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
    void terminalRemoveWorldEventRetiresIdentityAndSubmitsRecoveryBeforeStoreShutdown() throws Exception {
        String plugin = read(ROOT.resolve("Tamework.java"));
        String snapshots = read(ITEMS.resolve("CommandLinkedNpcStateSnapshotService.java"));
        String relocation = read(ITEMS.resolve("CommandNpcRelocationService.java"));
        String lifecycle = read(ITEMS.resolve("CommandRelocationNpcLifecycle.java"));
        String tracker = read(ITEMS.resolve("CommandRelocationNpcTracker.java"));
        String dispatch = read(ITEMS.resolve("CommandRelocationDispatchService.java"));

        assertTrue(plugin.contains("this::onWorldRemovedForCompanionRecovery"));
        assertTrue(plugin.contains("Short.MAX_VALUE"),
                "Recovery must observe cancellation after all standard engine priorities.");
        assertTrue(plugin.contains("event == null || event.isCancelled()"));
        assertTrue(plugin.contains("retireDeleteOnRemoveWorld(world)"));
        assertTrue(plugin.contains("commandNpcRelocationService.onWorldRemoved(world)"));
        assertTrue(plugin.indexOf("retireDeleteOnRemoveWorld(world)")
                        < plugin.indexOf("commandNpcRelocationService.onWorldRemoved(world)"),
                "Terminal store identity must be retired before profile-safe Lost resolution.");
        assertTrue(snapshots.contains("loadedNpcIdentityIndex.clearLocation("));
        assertTrue(snapshots.contains("LoadedNpcLocationResolver.resolve("));
        assertTrue(tracker.contains("world.getWorldConfig().isDeleteOnRemove()"));
        assertTrue(tracker.contains("pendingWorldRemovalsByNpc"));
        assertTrue(lifecycle.contains("npcTracker.markDeleteOnRemoveWorld(world, removedAtMs)"));
        assertTrue(lifecycle.contains("dropReporter.reportWorldRemoval(candidate)"));
        int worldRemovalStart = lifecycle.indexOf("void onWorldRemoved");
        int worldRemovalEnd = lifecycle.indexOf(
                "boolean isDeleteOnRemoveRecoveryPending", worldRemovalStart);
        assertTrue(worldRemovalStart >= 0 && worldRemovalEnd > worldRemovalStart);
        assertTrue(lifecycle.substring(worldRemovalStart, worldRemovalEnd).contains(
                        "dropReporter.reportWorldRemoval"),
                "Store shutdown skips NPC removal callbacks, so the terminal event must submit.");
        assertTrue(lifecycle.contains("npcTracker.completeWorldRemoval(candidate.npcUuid())"));
        assertTrue(dispatch.contains(
                        "relocationService.isDeleteOnRemoveRecoveryPending(record.npcUuid)"),
                "Relocation dispatch must not race a terminal instance recovery marker.");
        assertTrue(relocation.contains("private final CommandRelocationNpcLifecycle npcLifecycle"),
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
