package com.alechilles.alecstamework.avatarflight;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarFlightAbilityAudioAssetsTest {
    private static final Path RESOURCE_ROOT = Path.of("src", "main", "resources");
    private static final Path EVENT_ROOT = RESOURCE_ROOT.resolve(
            Path.of("Server", "Audio", "SoundEvents", "SFX", "Tamework", "AvatarFlight")
    );
    private static final List<String> IDS = List.of(
            "SFX_Tamework_AvatarFlight_Flap",
            "SFX_Tamework_AvatarFlight_Forward_Boost",
            "SFX_Tamework_AvatarFlight_Airbrake"
    );

    @Test
    void abilitySoundEventsReferenceBundledOneShotOggAssets() throws Exception {
        for (String id : IDS) {
            Path eventPath = EVENT_ROOT.resolve(id + ".json");
            JsonObject event = JsonParser.parseString(Files.readString(eventPath)).getAsJsonObject();
            JsonArray layers = event.getAsJsonArray("Layers");

            assertEquals(1, layers.size(), id);
            JsonObject layer = layers.get(0).getAsJsonObject();
            assertTrue(!layer.get("Looping").getAsBoolean(), id);
            JsonArray soundFiles = layer.getAsJsonArray("Files");
            assertEquals(1, soundFiles.size(), id);
            Path oggPath = RESOURCE_ROOT.resolve("Common").resolve(soundFiles.get(0).getAsString());
            assertTrue(Files.isRegularFile(oggPath), oggPath.toString());
            byte[] oggBytes = Files.readAllBytes(oggPath);
            assertArrayEquals(
                    "OggS".getBytes(StandardCharsets.US_ASCII),
                    oggBytes.length >= 4 ? java.util.Arrays.copyOf(oggBytes, 4) : new byte[0],
                    oggPath.toString()
            );
        }
    }
}
