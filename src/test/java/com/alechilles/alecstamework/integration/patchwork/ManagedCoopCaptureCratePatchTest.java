package com.alechilles.alecstamework.integration.patchwork;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Guards Tamework's capture-crate patch without depending on Patchwork implementation classes. */
final class ManagedCoopCaptureCratePatchTest {
    @Test
    void sharedInteractionBoundaryKeepsSectionLocalAndFailClosedEvidence() throws Exception {
        String captureInteraction = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/interactions/"
                        + "TameworkManagedCoopCaptureCrateInteraction.java"));
        String service = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/"
                        + "HytaleCapturedItemCoopInteractionService.java"));
        String spawnerInteraction = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/interactions/TameworkSpawnInteraction.java"));
        String plugin = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/Tamework.java"));

        assertTrue(captureInteraction.contains("extends UseCaptureCrateInteraction"));
        assertTrue(plugin.contains("TameworkManagedCoopCaptureCrateInteraction.TYPE_ID"));
        assertTrue(plugin.contains("TameworkManagedCoopCaptureCrateInteraction.CODEC"));
        assertTrue(captureInteraction.contains("intake.attempt("));
        assertTrue(service.contains("InventoryComponent.HOTBAR_SECTION_ID"));
        assertTrue(service.contains("InventoryComponent.STORAGE_SECTION_ID"));
        assertTrue(service.contains("InventoryComponent.BACKPACK_SECTION_ID"));
        assertTrue(service.contains("Byte.toUnsignedInt(heldSlot)"));
        assertFalse(captureInteraction.contains("Combined"));
        assertFalse(service.contains("Combined"));
        assertTrue(service.indexOf("receiptMarked(held)") < service.indexOf("targets.resolve(world, position)"));

        int intakeAttempt = spawnerInteraction.indexOf("coopIntake.attempt(");
        assertTrue(intakeAttempt >= 0);
        assertTrue(intakeAttempt < spawnerInteraction.indexOf("handler.canSpawnInteraction(heldItem)"));
        assertTrue(intakeAttempt < spawnerInteraction.indexOf("handler.spawnFromItemInteraction("));
    }
}
