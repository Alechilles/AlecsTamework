package com.alechilles.alecstamework.integration.tooltips;

import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.logging.Level;

public final class SpawnerTooltipBridgeLoader {
    private SpawnerTooltipBridgeLoader() {
    }

    public static SpawnerTooltipBridge initialize(HytaleLogger logger,
                                                  ItemFeatureRegistry itemFeatureRegistry,
                                                  TranslationRegistry translationRegistry) {
        if (!isDynamicTooltipsApiPresent()) {
            return NoOpSpawnerTooltipBridge.INSTANCE;
        }
        try {
            return DynamicTooltipsSpawnerTooltipBridge.initialize(logger, itemFeatureRegistry, translationRegistry);
        } catch (Throwable ex) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(ex)
                        .log("DynamicTooltipsLib was detected but the Tamework tooltip bridge failed to initialize.");
            }
            return NoOpSpawnerTooltipBridge.INSTANCE;
        }
    }

    private static boolean isDynamicTooltipsApiPresent() {
        try {
            Class.forName("org.herolias.tooltips.api.DynamicTooltipsApiProvider");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
