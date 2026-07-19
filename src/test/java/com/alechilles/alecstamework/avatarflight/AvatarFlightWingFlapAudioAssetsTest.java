package com.alechilles.alecstamework.avatarflight;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarFlightWingFlapAudioAssetsTest {
    private static final Path RESOURCE_ROOT = Path.of("src", "main", "resources");
    private static final Path EVENT_PATH = RESOURCE_ROOT.resolve(Path.of(
            "Server", "Audio", "SoundEvents", "SFX", "Tamework", "AvatarFlight",
            "SFX_Tamework_AvatarFlight_Wing_Flap_Footstep.json"
    ));
    private static final List<String> EXPECTED_FILES = List.of(
            "Sounds/Tamework/AvatarFlight/Flight/Footsteps/Tamework_AvatarFlight_Wing_Flap_01.ogg",
            "Sounds/Tamework/AvatarFlight/Flight/Footsteps/Tamework_AvatarFlight_Wing_Flap_02.ogg",
            "Sounds/Tamework/AvatarFlight/Flight/Footsteps/Tamework_AvatarFlight_Wing_Flap_03.ogg"
    );

    @Test
    void wingFlapFootstepEventRandomizesBundledOneShotOggAssets() throws Exception {
        JsonObject event = JsonParser.parseString(Files.readString(EVENT_PATH)).getAsJsonObject();
        JsonArray layers = event.getAsJsonArray("Layers");

        assertEquals(1, layers.size());
        JsonObject layer = layers.get(0).getAsJsonObject();
        assertFalse(layer.get("Looping").getAsBoolean());
        assertEquals(EXPECTED_FILES, layer.getAsJsonArray("Files").asList().stream()
                .map(element -> element.getAsString())
                .toList());

        for (String soundFile : EXPECTED_FILES) {
            Path oggPath = RESOURCE_ROOT.resolve("Common").resolve(soundFile);
            assertTrue(Files.isRegularFile(oggPath), oggPath.toString());
            byte[] oggBytes = Files.readAllBytes(oggPath);
            assertArrayEquals(
                    "OggS".getBytes(StandardCharsets.US_ASCII),
                    oggBytes.length >= 4 ? Arrays.copyOf(oggBytes, 4) : new byte[0],
                    oggPath.toString()
            );
        }
    }
}
