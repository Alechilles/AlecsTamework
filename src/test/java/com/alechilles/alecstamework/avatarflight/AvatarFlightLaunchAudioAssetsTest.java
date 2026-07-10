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

class AvatarFlightLaunchAudioAssetsTest {
    private static final Path RESOURCE_ROOT = Path.of("src", "main", "resources");
    private static final Path EVENT_ROOT = RESOURCE_ROOT.resolve(
            Path.of("Server", "Audio", "SoundEvents", "SFX", "Tamework", "AvatarFlight")
    );
    private static final List<String> IDS = List.of(
            "SFX_Tamework_AvatarFlight_Launch_Charge_Pulse",
            "SFX_Tamework_AvatarFlight_Launch_Ready",
            "SFX_Tamework_AvatarFlight_Launch_Cancel",
            "SFX_Tamework_AvatarFlight_Launch_Release_Partial",
            "SFX_Tamework_AvatarFlight_Launch_Release_Mid",
            "SFX_Tamework_AvatarFlight_Launch_Release_Full"
    );

    @Test
    void launchSoundEventsReferenceBundledOggAssets() throws Exception {
        for (String id : IDS) {
            Path eventPath = EVENT_ROOT.resolve(id + ".json");
            JsonObject event = JsonParser.parseString(Files.readString(eventPath)).getAsJsonObject();
            JsonArray layers = event.getAsJsonArray("Layers");

            assertEquals(1, layers.size(), id);
            JsonObject layer = layers.get(0).getAsJsonObject();
            assertTrue(!layer.get("Looping").getAsBoolean(), id);
            String soundPath = layer.getAsJsonArray("Files").get(0).getAsString();
            Path oggPath = RESOURCE_ROOT.resolve("Common").resolve(soundPath);
            assertTrue(Files.isRegularFile(oggPath), soundPath);
            byte[] oggBytes = Files.readAllBytes(oggPath);
            assertArrayEquals(
                    "OggS".getBytes(StandardCharsets.US_ASCII),
                    oggBytes.length >= 4
                            ? java.util.Arrays.copyOf(oggBytes, 4)
                            : new byte[0],
                    soundPath
            );
        }
    }
}
