package com.alechilles.alecstamework.avatarflight;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarFlightFastFlightAudioAssetsTest {
    private static final Path RESOURCE_ROOT = Path.of("src", "main", "resources");
    private static final Path EVENT_PATH = RESOURCE_ROOT.resolve(Path.of(
            "Server", "Audio", "SoundEvents", "SFX", "Tamework", "AvatarFlight",
            "SFX_Tamework_AvatarFlight_Fast_Flight_Loop.json"
    ));
    private static final String SOUND_FILE =
            "Sounds/Tamework/AvatarFlight/Flight/Tamework_AvatarFlight_Fast_Flight_Loop.ogg";

    @Test
    void fastFlightSoundEventLoopsBundledOggAsset() throws Exception {
        JsonObject event = JsonParser.parseString(Files.readString(EVENT_PATH)).getAsJsonObject();
        JsonArray layers = event.getAsJsonArray("Layers");

        assertEquals(1, layers.size());
        JsonObject layer = layers.get(0).getAsJsonObject();
        assertTrue(layer.get("Looping").getAsBoolean());
        assertEquals(SOUND_FILE, layer.getAsJsonArray("Files").get(0).getAsString());

        Path oggPath = RESOURCE_ROOT.resolve("Common").resolve(SOUND_FILE);
        assertTrue(Files.isRegularFile(oggPath), oggPath.toString());
        byte[] oggBytes = Files.readAllBytes(oggPath);
        assertArrayEquals(
                "OggS".getBytes(StandardCharsets.US_ASCII),
                oggBytes.length >= 4 ? Arrays.copyOf(oggBytes, 4) : new byte[0],
                oggPath.toString()
        );
    }
}
