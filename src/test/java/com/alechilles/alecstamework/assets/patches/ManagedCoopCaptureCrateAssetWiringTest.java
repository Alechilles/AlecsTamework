package com.alechilles.alecstamework.assets.patches;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the narrow 0.5.6 capture-crate asset and interaction-codec cutover. */
final class ManagedCoopCaptureCrateAssetWiringTest {
    private static final String PATCH_RESOURCE =
            "Server/Tamework/Patches/Items/Tamework_Tool_Capture_Crate_Patch.json";

    @Test
    void bundledPatchOnlyReplacesTheVanillaCaptureInteractionType() throws Exception {
        assertFalse(Files.exists(Path.of(
                "src/main/resources/Server/Item/Items/Tool/Capture_Crate/Tool_Capture_Crate.json")),
                "Tamework must not ship a full vanilla capture-crate item override.");

        AssetPatchDefinition patch = AssetPatchDefinition.parse(
                object(readResource(PATCH_RESOURCE)), "Alec's Tamework!", PATCH_RESOURCE);
        JsonObject vanilla = object("""
                {
                  "MaxStack": 1,
                  "Interactions": {
                    "Primary": {
                      "Interactions": [
                        {
                          "Type": "Simple",
                          "RunTime": 0.05,
                          "Next": {
                            "Type": "UseCaptureCrate",
                            "AcceptedNpcGroups": ["Capture_Crate"],
                            "FullIcon": "Icons/ItemsGenerated/Tool_Capture_Crate_Full.png",
                            "Failed": {"Type": "Simple"},
                            "Next": {"Type": "Simple"}
                          }
                        }
                      ]
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
                "Server/Item/Items/Tool/Capture_Crate/Tool_Capture_Crate.json",
                patch.getTarget());
        assertEquals(-100, patch.getPriority());
        assertEquals("TameworkManagedCoopCaptureCrate", capture.get("Type").getAsString());
        assertEquals("Capture_Crate", capture.getAsJsonArray("AcceptedNpcGroups").get(0).getAsString());
        assertEquals(
                "Icons/ItemsGenerated/Tool_Capture_Crate_Full.png",
                capture.get("FullIcon").getAsString());
        assertEquals("Simple", capture.getAsJsonObject("Failed").get("Type").getAsString());
        assertEquals("Simple", capture.getAsJsonObject("Next").get("Type").getAsString());
        assertEquals(1, result.status().getApplied().size());
    }

    @Test
    void customInteractionCodecIsRegisteredBeforeItemAssetsLoad() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/Tamework.java"));
        String compact = source.replaceAll("\\s+", "");
        String registration = "Interaction.CODEC.register("
                + "\"TameworkManagedCoopCaptureCrate\","
                + "TameworkManagedCoopCaptureCrateInteraction.class,"
                + "TameworkManagedCoopCaptureCrateInteraction.CODEC);";

        assertTrue(source.contains(
                "import com.alechilles.alecstamework.interactions."
                        + "TameworkManagedCoopCaptureCrateInteraction;"));
        assertTrue(compact.contains(registration));
        assertTrue(compact.indexOf(registration) < compact.indexOf("registerSpawnerItemAssets();"));
    }

    private static JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static String readResource(String path) throws Exception {
        ClassLoader loader = ManagedCoopCaptureCrateAssetWiringTest.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(path)) {
            assertNotNull(stream, "Missing test resource " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
