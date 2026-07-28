package com.alechilles.alecstamework.items;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureChannelAudioAssetsTest {
    private static final Path RESOURCE_ROOT = Path.of("src", "main", "resources");
    private static final Path EVENT_PATH = RESOURCE_ROOT.resolve(Path.of(
            "Server", "Audio", "SoundEvents", "SFX", "Tamework", "Capture",
            "SFX_Tamework_Capture_Channel_Dark_Magic.json"
    ));
    private static final String SOUND_FILE =
            "Sounds/Tamework/Capture/Tamework_Capture_Channel_Dark_Magic.ogg";

    @Test
    void captureChannelSoundEventUsesTheBundledNonLoopingAudio() throws Exception {
        JsonObject event = JsonParser.parseString(Files.readString(EVENT_PATH)).getAsJsonObject();
        JsonArray layers = event.getAsJsonArray("Layers");

        assertEquals(1, layers.size());
        JsonObject layer = layers.get(0).getAsJsonObject();
        assertFalse(layer.get("Looping").getAsBoolean());
        assertEquals(SOUND_FILE, layer.getAsJsonArray("Files").get(0).getAsString());
        Path audioPath = RESOURCE_ROOT.resolve("Common").resolve(SOUND_FILE);
        assertTrue(Files.isRegularFile(audioPath));
        byte[] audioBytes = Files.readAllBytes(audioPath);
        assertEquals(
                "OggS",
                new String(Arrays.copyOf(audioBytes, 4), StandardCharsets.US_ASCII)
        );
    }
}
