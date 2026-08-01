package com.alechilles.alecstamework.integration.patchwork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Guards Tamework's capture-crate patch without depending on Patchwork implementation classes. */
final class ManagedCoopCaptureCratePatchTest {
    private static final Path PATCH = Path.of(
            "src/main/resources/Server/Patchwork/Patches/Items/Tamework_Tool_Capture_Crate_Patch.json");

    @Test
    void bundledPatchOnlyReplacesTheVanillaCaptureInteractionType() throws Exception {
        assertFalse(Files.exists(Path.of(
                "src/main/resources/Server/Item/Items/Tool/Capture_Crate/Tool_Capture_Crate.json")),
                "Tamework must not ship a full vanilla item override");

        JsonObject patch = JsonParser.parseString(Files.readString(PATCH)).getAsJsonObject();
        JsonObject operation = patch.getAsJsonArray("Operations").get(0).getAsJsonObject();

        assertEquals("Server/Item/Items/Tool/Capture_Crate/Tool_Capture_Crate.json",
                patch.get("Target").getAsString());
        assertEquals(-100, patch.get("Priority").getAsInt());
        assertEquals(1, patch.getAsJsonArray("Operations").size());
        assertEquals("Replace", operation.get("Op").getAsString());
        assertEquals("/Interactions/Primary/Interactions/0/Next/Type",
                operation.get("Path").getAsString());
        assertEquals("TameworkManagedCoopCaptureCrate", operation.get("Value").getAsString());
    }

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
