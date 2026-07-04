package com.alechilles.alecstamework.avatarflight;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the prototype Dragon Reins item is available as a normal Tamework item. */
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
    private static final Path EN_US = Path.of(
            "src",
            "main",
            "resources",
            "Server",
            "Languages",
            "en-US",
            "server.lang"
    );

    @Test
    void dragonReinsItemIsCreativeToolWithLocalizedName() throws Exception {
        String item = Files.readString(ITEM, StandardCharsets.UTF_8);
        String lang = Files.readString(EN_US, StandardCharsets.UTF_8);

        assertTrue(item.contains("\"Name\": \"server.items.Tamework_Dragon_Reins.name\""));
        assertTrue(item.contains("\"Description\": \"server.items.Tamework_Dragon_Reins.description\""));
        assertTrue(item.contains("\"MaxStack\": 1"));
        assertTrue(item.contains("\"Items.Tools\""));
        assertTrue(lang.contains("items.Tamework_Dragon_Reins.name=Dragon Reins"));
        assertTrue(lang.contains("items.Tamework_Dragon_Reins.description="));
    }

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
