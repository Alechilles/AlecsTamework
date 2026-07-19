package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.vfx.projectile.HomingVisualProjectileAnchor;
import org.junit.jupiter.api.Test;

class CaptureHomingProjectileSettingsTest {
    @Test
    void appliesDragonStoneDefaultsToInvalidNumbers() {
        CaptureHomingProjectileSettings settings = new CaptureHomingProjectileSettings(
                true, " Capture_Mote ", -1.0D, 0.0D, -5.0D, 0.0D, Double.NaN, 0
        );

        assertTrue(settings.isEnabled());
        assertEquals("Capture_Mote", settings.getModelId());
        assertEquals(120L, settings.getSpawnIntervalMs());
        assertEquals(16, settings.getMaxConcurrent());
        assertEquals(8.0D, settings.toProjectileSpec().speed());
        assertEquals(0.0D, settings.toProjectileSpec().turnRateDegreesPerSecond());
        assertEquals(0.18D, settings.toProjectileSpec().arrivalRadius());
        assertEquals(2.0D, settings.toProjectileSpec().lifetimeSeconds());
        assertEquals(HomingVisualProjectileAnchor.HELD_ITEM, settings.toProjectileSpec().destinationAnchor());
    }

    @Test
    void blankModelOrDisabledFlagDisablesHoming() {
        assertFalse(new CaptureHomingProjectileSettings(
                true, " ", 0.12D, 8.0D, 0.0D, 0.18D, 2.0D, 16
        ).isEnabled());
        assertFalse(new CaptureHomingProjectileSettings(
                false, "Capture_Mote", 0.12D, 8.0D, 0.0D, 0.18D, 2.0D, 16
        ).isEnabled());
    }

    @Test
    void concurrencyIsHardCapped() {
        CaptureHomingProjectileSettings settings = new CaptureHomingProjectileSettings(
                true, "Capture_Mote", 0.12D, 8.0D, 0.0D, 0.18D, 2.0D, 10_000
        );

        assertEquals(64, settings.getMaxConcurrent());
    }
}
