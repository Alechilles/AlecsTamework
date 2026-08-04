package com.alechilles.alecstamework.config.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Verifies the default-disabled shoulder passenger configuration. */
class TwCompanionShoulderRideSettingsTest {
    @Test
    void defaultsAreDisabledWithAUsableSmallCompanionOffset() {
        TwCompanionShoulderRideSettings settings =
                new TwCompanionShoulderRideSettings();

        assertFalse(settings.isConfigured());
        assertEquals(0.32D, settings.getOffsetX());
        assertEquals(1.45D, settings.getOffsetY());
        assertEquals(0D, settings.getOffsetZ());
    }

    @Test
    void enabledFiniteOffsetsConfigureTheCapability() {
        TwCompanionShoulderRideSettings settings =
                new TwCompanionShoulderRideSettings();
        settings.setEnabled(true);

        assertTrue(settings.isConfigured());
        settings.setOffsetY(Double.NaN);
        assertFalse(settings.isConfigured());
    }
}
