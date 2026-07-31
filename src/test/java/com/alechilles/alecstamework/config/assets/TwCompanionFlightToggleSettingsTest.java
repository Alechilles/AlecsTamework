package com.alechilles.alecstamework.config.assets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the default-disabled companion flight-toggle capability contract. */
class TwCompanionFlightToggleSettingsTest {

    @Test
    void defaultsAreDisabledAndIncomplete() {
        TwCompanionFlightToggleSettings settings =
                new TwCompanionFlightToggleSettings();

        assertFalse(settings.isEnabled());
        assertFalse(settings.isConfigured());
        assertEquals("", settings.getHookId());
    }

    @Test
    void enabledCapabilityRequiresHook() {
        TwCompanionFlightToggleSettings settings = configured(
                true, "HyDragon.Command.ToggleAirborneMode"
        );

        assertTrue(settings.isConfigured());
        assertFalse(configured(true, "").isConfigured());
    }

    @Test
    void hookIdNormalizesNullAndWhitespace() {
        TwCompanionFlightToggleSettings settings =
                new TwCompanionFlightToggleSettings();

        settings.setHookId(null);
        assertEquals("", settings.getHookId());

        settings.setHookId("  HyDragon.Command.ToggleAirborneMode  ");
        assertEquals(
                "HyDragon.Command.ToggleAirborneMode",
                settings.getHookId()
        );
    }

    private TwCompanionFlightToggleSettings configured(
            boolean enabled,
            String hookId
    ) {
        TwCompanionFlightToggleSettings settings =
                new TwCompanionFlightToggleSettings();
        settings.setEnabled(enabled);
        settings.setHookId(hookId);
        return settings;
    }
}
