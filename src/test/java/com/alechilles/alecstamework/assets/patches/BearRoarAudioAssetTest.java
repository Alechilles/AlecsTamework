package com.alechilles.alecstamework.assets.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Guards the derived bear-roar sound and both vanilla model roar bindings. */
final class BearRoarAudioAssetTest {
    private static final String ROAR_EVENT_ID = "SFX_Tamework_Bear_Grizzly_Roar";
    private static final String ROAR_EVENT_RESOURCE =
            "Server/Audio/SoundEvents/SFX/NPC/Tamework/Bear_Grizzly/"
                    + ROAR_EVENT_ID + ".json";
    private static final String PATCH_RESOURCE =
            "Server/Tamework/Patches/Models/Tamework_Bear_Grizzly_Roar_Audio_Patch.json";

    @Test
    void roarEventDeepensTheVanillaAlertedSamplesWithoutCopyingThem() throws Exception {
        JsonObject event = object(readResource(ROAR_EVENT_RESOURCE));

        assertEquals("SFX_Bear_Grizzly_Alerted", event.get("Parent").getAsString());
        assertEquals(-4.0, event.get("Pitch").getAsDouble());
        assertEquals(2, event.size(), "The derived event should inherit vanilla samples and tuning.");
    }

    @Test
    void bearModelPatchRebindsEveryRoarSlotAndPreservesOtherSounds() throws Exception {
        assertFalse(Files.exists(Path.of(
                "src/main/resources/Server/Models/Beast/Bear_Grizzly.json")),
                "Tamework should patch the vanilla model instead of shipping a stale full copy.");

        AssetPatchDefinition patch = AssetPatchDefinition.parse(
                object(readResource(PATCH_RESOURCE)), "Alec's Tamework!", PATCH_RESOURCE);
        JsonObject vanilla = object("""
                {
                  "AnimationSets": {
                    "Alerted": {
                      "Animations": [
                        {"SoundEventId": "SFX_Bear_Grizzly_Alerted"}
                      ]
                    },
                    "Roar": {
                      "Animations": [
                        {"SoundEventId": "SFX_Bear_Grizzly_Alerted"}
                      ]
                    },
                    "Hurt": {
                      "Animations": [
                        {"SoundEventId": "SFX_Bear_Grizzly_Hurt"}
                      ]
                    }
                  }
                }
                """);

        AssetPatchEngine.PatchResult result =
                new AssetPatchEngine().apply(vanilla, List.of(patch));
        JsonObject animationSets = result.patched().getAsJsonObject("AnimationSets");

        assertEquals("Server/Models/Beast/Bear_Grizzly.json", patch.getTarget());
        assertEquals(ROAR_EVENT_ID, soundEvent(animationSets, "Alerted"));
        assertEquals(ROAR_EVENT_ID, soundEvent(animationSets, "Roar"));
        assertEquals("SFX_Bear_Grizzly_Hurt", soundEvent(animationSets, "Hurt"));
        assertEquals(2, result.status().getApplied().size());
    }

    private static String soundEvent(JsonObject animationSets, String setId) {
        return animationSets.getAsJsonObject(setId)
                .getAsJsonArray("Animations")
                .get(0)
                .getAsJsonObject()
                .get("SoundEventId")
                .getAsString();
    }

    private static JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static String readResource(String path) throws Exception {
        ClassLoader loader = BearRoarAudioAssetTest.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(path)) {
            assertNotNull(stream, "Missing test resource " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
