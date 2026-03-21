package com.alechilles.alecstamework.integration.tooltips;

import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.logging.Level;
import org.herolias.tooltips.api.DynamicTooltipsApi;
import org.herolias.tooltips.api.DynamicTooltipsApiProvider;

final class DynamicTooltipsSpawnerTooltipBridge implements SpawnerTooltipBridge {
    private final HytaleLogger logger;
    private final DynamicTooltipsApi api;
    private final String providerId;

    private DynamicTooltipsSpawnerTooltipBridge(HytaleLogger logger, DynamicTooltipsApi api, String providerId) {
        this.logger = logger;
        this.api = api;
        this.providerId = providerId;
    }

    static SpawnerTooltipBridge initialize(HytaleLogger logger,
                                           ItemFeatureRegistry itemFeatureRegistry,
                                           TranslationRegistry translationRegistry) {
        DynamicTooltipsApi api = DynamicTooltipsApiProvider.get();
        if (api == null) {
            return NoOpSpawnerTooltipBridge.INSTANCE;
        }
        TameworkSpawnerTooltipProvider provider = new TameworkSpawnerTooltipProvider(itemFeatureRegistry, translationRegistry);
        api.registerProvider(provider);
        if (logger != null) {
            logger.at(Level.INFO).log("DynamicTooltipsLib bridge enabled for Tamework spawner items.");
        }
        return new DynamicTooltipsSpawnerTooltipBridge(logger, api, provider.getProviderId());
    }

    @Override
    public void refreshFromItemConfigReload() {
        if (api == null) {
            return;
        }
        api.invalidateAll();
        api.refreshAllPlayers();
    }

    @Override
    public void shutdown() {
        if (api == null) {
            return;
        }
        try {
            api.unregisterProvider(providerId);
            api.invalidateAll();
        } catch (Exception ex) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(ex)
                        .log("Failed to cleanly unregister DynamicTooltipsLib provider.");
            }
        }
    }
}
