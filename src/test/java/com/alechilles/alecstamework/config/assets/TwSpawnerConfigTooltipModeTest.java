package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import java.lang.reflect.Field;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TwSpawnerConfigTooltipModeTest {

    @Test
    void toItemFeatureConfigDefaultsTooltipModeToAdditive() {
        TwSpawnerConfig config = new TwSpawnerConfig();

        assertEquals(
                ItemFeatureConfig.SpawnerTooltipMode.ADDITIVE,
                config.toItemFeatureConfig().getSpawnerTooltipMode()
        );
    }

    @Test
    void inheritCopiesTooltipModeWhenOmitted() throws Exception {
        TwSpawnerConfig parent = new TwSpawnerConfig();
        TwSpawnerConfig child = new TwSpawnerConfig();
        setTooltipMode(parent, ItemFeatureConfig.SpawnerTooltipMode.REPLACE);

        child.inheritMissingTopLevelFrom(parent, Set.of());

        assertEquals(ItemFeatureConfig.SpawnerTooltipMode.REPLACE, getTooltipMode(child));
    }

    @Test
    void inheritKeepsTooltipModeWhenExplicit() throws Exception {
        TwSpawnerConfig parent = new TwSpawnerConfig();
        TwSpawnerConfig child = new TwSpawnerConfig();
        setTooltipMode(parent, ItemFeatureConfig.SpawnerTooltipMode.REPLACE);
        setTooltipMode(child, ItemFeatureConfig.SpawnerTooltipMode.ADDITIVE);

        child.inheritMissingTopLevelFrom(parent, Set.of("TooltipMode"));

        assertEquals(ItemFeatureConfig.SpawnerTooltipMode.ADDITIVE, getTooltipMode(child));
    }

    private static void setTooltipMode(TwSpawnerConfig config, ItemFeatureConfig.SpawnerTooltipMode mode)
            throws Exception {
        Field field = TwSpawnerConfig.class.getDeclaredField("tooltipMode");
        field.setAccessible(true);
        field.set(config, mode);
    }

    private static ItemFeatureConfig.SpawnerTooltipMode getTooltipMode(TwSpawnerConfig config) throws Exception {
        Field field = TwSpawnerConfig.class.getDeclaredField("tooltipMode");
        field.setAccessible(true);
        return (ItemFeatureConfig.SpawnerTooltipMode) field.get(config);
    }
}
