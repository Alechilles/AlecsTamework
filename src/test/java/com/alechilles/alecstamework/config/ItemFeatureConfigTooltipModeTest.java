package com.alechilles.alecstamework.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemFeatureConfigTooltipModeTest {

    @Test
    void tooltipModeDefaultsToAdditive() {
        ItemFeatureConfig config = ItemFeatureConfig.builder().build();

        assertEquals(ItemFeatureConfig.SpawnerTooltipMode.ADDITIVE, config.getSpawnerTooltipMode());
    }

    @Test
    void tooltipModeParsesReplaceCaseInsensitively() {
        assertEquals(
                ItemFeatureConfig.SpawnerTooltipMode.REPLACE,
                ItemFeatureConfig.SpawnerTooltipMode.fromString(" RePlAcE ")
        );
    }

    @Test
    void tooltipModeFallsBackToAdditiveForUnknownValues() {
        assertEquals(
                ItemFeatureConfig.SpawnerTooltipMode.ADDITIVE,
                ItemFeatureConfig.SpawnerTooltipMode.fromString("something-else")
        );
    }
}
