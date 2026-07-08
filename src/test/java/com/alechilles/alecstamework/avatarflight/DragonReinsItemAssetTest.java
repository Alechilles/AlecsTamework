package com.alechilles.alecstamework.avatarflight;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies Flightmaster's Reins item interactions are wired for avatar-flight controls. */
class DragonReinsItemAssetTest {
    private static final Path ITEM = Path.of(
            "src",
            "main",
            "resources",
            "Server",
            "Item",
            "Items",
            "Tools",
            "Tamework_Dragon_Reins.json"
    );

    @Test
    void dragonReinsPrimaryAndSecondaryClicksUseFlightControlInteractions() throws Exception {
        String item = Files.readString(ITEM, StandardCharsets.UTF_8);
        String plugin = Files.readString(Path.of(
                "src",
                "main",
                "java",
                "com",
                "alechilles",
                "alecstamework",
                "Tamework.java"
        ), StandardCharsets.UTF_8);

        assertTrue(item.contains("\"Primary\""));
        assertTrue(item.contains("\"Secondary\""));
        assertTrue(item.contains("\"Type\": \"TameworkFlightFlap\""),
                "Dragon Reins primary clicks must queue avatar-flight flap input");
        assertTrue(item.contains("\"Type\": \"TameworkFlightAirbrake\""),
                "Dragon Reins secondary clicks must activate avatar-flight airbrake input");
        assertTrue(item.contains("\"DurationMs\": 350"));
        assertTrue(plugin.contains("\"TameworkFlightFlap\""));
        assertTrue(plugin.contains("TameworkFlightFlapInteraction.class"));
        assertTrue(plugin.contains("\"TameworkFlightAirbrake\""));
        assertTrue(plugin.contains("TameworkFlightAirbrakeInteraction.class"));
    }
}
