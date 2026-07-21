package com.alechilles.alecstamework.avatarflight;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies Flightmaster's Talisman item interactions are wired for avatar-flight controls. */
class FlightmastersTalismanItemAssetTest {
    private static final Path ITEM = Path.of(
            "src",
            "main",
            "resources",
            "Server",
            "Item",
            "Items",
            "Tools",
            "Tamework_Flightmasters_Talisman.json"
    );
    private static final Path LEGACY_ITEM = ITEM.resolveSibling("Tamework_Dragon_Reins.json");

    @Test
    void talismanPrimaryAndSecondaryClicksUseFlightControlInteractions() throws Exception {
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
                "Flightmaster's Talisman primary clicks must queue avatar-flight flap input");
        assertTrue(item.contains("\"Type\": \"TameworkFlightAirbrake\""),
                "Flightmaster's Talisman secondary clicks must activate avatar-flight airbrake input");
        assertTrue(item.contains("\"DurationMs\": 350"));
        assertTrue(item.contains("\"Name\": \"server.items.Tamework_Flightmasters_Talisman.name\""));
        assertTrue(item.contains("\"Description\": \"server.items.Tamework_Flightmasters_Talisman.description\""));
        assertTrue(item.contains("\"Model\": \"Items/Tamework/FlightmasterTalisman/Flightmaster_Talisman.blockymodel\""));
        assertFalse(item.contains("\"Items.Weapons\""),
                "The talisman must stay out of the weapon category so Hytale hides its native ability strip");
        assertFalse(Files.exists(LEGACY_ITEM), "Legacy Dragon Reins item asset must be removed");
        assertTrue(plugin.contains("\"TameworkFlightFlap\""));
        assertTrue(plugin.contains("TameworkFlightFlapInteraction.class"));
        assertTrue(plugin.contains("\"TameworkFlightAirbrake\""));
        assertTrue(plugin.contains("TameworkFlightAirbrakeInteraction.class"));
    }
}
