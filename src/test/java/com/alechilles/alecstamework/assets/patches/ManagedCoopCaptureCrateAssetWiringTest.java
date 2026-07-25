package com.alechilles.alecstamework.assets.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Guards the narrow capture-crate asset cutover used by managed captured-item intake. */
final class ManagedCoopCaptureCrateAssetWiringTest {
    private static final String PATCH_RESOURCE =
            "Server/Tamework/Patches/Items/"
                    + "Tamework_Tool_Capture_Crate_Patch.json";

    @Test
    void bundledPatchOnlyReplacesVanillaCaptureInteractionType()
            throws Exception {
        assertFalse(Files.exists(Path.of(
                "src/main/resources/Server/Item/Items/Tool/"
                        + "Capture_Crate/Tool_Capture_Crate.json"
        )), "Tamework must not ship a full vanilla item override");

        AssetPatchDefinition patch = AssetPatchDefinition.parse(
                object(readResource(PATCH_RESOURCE)),
                "Alec's Tamework!",
                PATCH_RESOURCE
        );
        JsonObject vanilla = object("""
                {
                  "MaxStack": 1,
                  "Interactions": {
                    "Primary": {
                      "Interactions": [{
                        "Type": "Simple",
                        "Next": {
                          "Type": "UseCaptureCrate",
                          "AcceptedNpcGroups": ["Capture_Crate"],
                          "FullIcon": "capture-full.png",
                          "Failed": {"Type": "Simple"},
                          "Next": {"Type": "Simple"}
                        }
                      }]
                    }
                  }
                }
                """);

        AssetPatchEngine.PatchResult result =
                new AssetPatchEngine().apply(vanilla, List.of(patch));
        JsonObject capture = result.patched()
                .getAsJsonObject("Interactions")
                .getAsJsonObject("Primary")
                .getAsJsonArray("Interactions")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("Next");

        assertEquals(
                "Server/Item/Items/Tool/Capture_Crate/"
                        + "Tool_Capture_Crate.json",
                patch.getTarget()
        );
        assertEquals(-100, patch.getPriority());
        assertEquals(
                "TameworkManagedCoopCaptureCrate",
                capture.get("Type").getAsString()
        );
        assertEquals(
                "Capture_Crate",
                capture.getAsJsonArray("AcceptedNpcGroups")
                        .get(0).getAsString()
        );
        assertEquals(
                "capture-full.png",
                capture.get("FullIcon").getAsString()
        );
        assertEquals(
                "Simple",
                capture.getAsJsonObject("Failed")
                        .get("Type").getAsString()
        );
        assertEquals(
                "Simple",
                capture.getAsJsonObject("Next")
                        .get("Type").getAsString()
        );
        assertEquals(1, result.status().getApplied().size());
    }

    @Test
    void sharedInteractionBoundaryKeepsExactSectionLocalAndFailClosedEvidence()
            throws Exception {
        String captureInteraction = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/"
                        + "interactions/"
                        + "TameworkManagedCoopCaptureCrateInteraction.java"
        ));
        String service = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/"
                        + "HytaleCapturedItemCoopInteractionService.java"
        ));
        String spawnerInteraction = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/"
                        + "interactions/TameworkSpawnInteraction.java"
        ));

        assertTrue(captureInteraction.contains(
                "extends UseCaptureCrateInteraction"
        ));
        assertTrue(captureInteraction.contains("intake.attempt("));
        assertTrue(service.contains(
                "InventoryComponent.HOTBAR_SECTION_ID"
        ));
        assertTrue(service.contains(
                "InventoryComponent.STORAGE_SECTION_ID"
        ));
        assertTrue(service.contains(
                "InventoryComponent.BACKPACK_SECTION_ID"
        ));
        assertTrue(service.contains("Byte.toUnsignedInt(heldSlot)"));
        assertFalse(captureInteraction.contains("Combined"));
        assertFalse(service.contains("Combined"));
        assertTrue(service.indexOf("receiptMarked(held)")
                < service.indexOf("targets.resolve(world, position)"));

        int intakeAttempt = spawnerInteraction.indexOf(
                "coopIntake.attempt("
        );
        assertTrue(intakeAttempt >= 0);
        assertTrue(intakeAttempt < spawnerInteraction.indexOf(
                "handler.canSpawnInteraction(heldItem)"
        ));
        assertTrue(intakeAttempt < spawnerInteraction.indexOf(
                "handler.spawnFromItemInteraction("
        ));
    }

    private static JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static String readResource(String path) throws Exception {
        ClassLoader loader =
                ManagedCoopCaptureCrateAssetWiringTest.class
                        .getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(path)) {
            assertNotNull(stream, "Missing test resource " + path);
            return new String(
                    stream.readAllBytes(), StandardCharsets.UTF_8
            );
        }
    }
}
