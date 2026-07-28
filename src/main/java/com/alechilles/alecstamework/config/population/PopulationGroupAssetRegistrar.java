package com.alechilles.alecstamework.config.population;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.TameworkConfigFamily;
import com.alechilles.alecstamework.config.assets.TwPopulationGroupConfig;
import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.event.RemovedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.asset.HytaleAssetStore;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * Owns population-group asset registration, cache invalidation, and atomic
 * config-index replacement.
 */
public final class PopulationGroupAssetRegistrar {
    private final Tamework plugin;
    private final PopulationGroupConfigRegistry registry;
    private final ReloadPublisher reloadPublisher;
    private boolean registered;
    private long assetRevision;

    public PopulationGroupAssetRegistrar(
            @Nonnull Tamework plugin,
            @Nonnull PopulationGroupConfigRegistry registry,
            @Nonnull ReloadPublisher reloadPublisher
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.reloadPublisher = Objects.requireNonNull(
                reloadPublisher,
                "reloadPublisher"
        );
    }

    public void register() {
        if (registered) {
            return;
        }
        plugin.getAssetRegistry().register(
                HytaleAssetStore.builder(
                                TwPopulationGroupConfig.class,
                                new DefaultAssetMap<>()
                        )
                        .setPath("Tamework/PopulationGroups")
                        .setCodec(TwPopulationGroupConfig.CODEC)
                        .setKeyFunction(TwPopulationGroupConfig::getId)
                        .build()
        );
        plugin.getEventRegistry().register(
                LoadedAssetsEvent.class,
                TwPopulationGroupConfig.class,
                this::onLoaded
        );
        plugin.getEventRegistry().register(
                RemovedAssetsEvent.class,
                TwPopulationGroupConfig.class,
                this::onRemoved
        );
        registered = true;
    }

    private void onLoaded(
            LoadedAssetsEvent<
                    String,
                    TwPopulationGroupConfig,
                    DefaultAssetMap<String, TwPopulationGroupConfig>
                    > event
    ) {
        if (rebuild() && !event.isInitial()) {
            reloadPublisher.publish(
                    TameworkConfigFamily.POPULATION_GROUP,
                    event.getLoadedAssets().keySet()
            );
        }
    }

    private void onRemoved(
            RemovedAssetsEvent<
                    String,
                    TwPopulationGroupConfig,
                    DefaultAssetMap<String, TwPopulationGroupConfig>
                    > event
    ) {
        if (rebuild()) {
            reloadPublisher.publish(
                    TameworkConfigFamily.POPULATION_GROUP,
                    event.getRemovedAssets()
            );
        }
    }

    private boolean rebuild() {
        TwPopulationGroupConfig.clearInheritanceFallbackCache();
        DefaultAssetMap<String, TwPopulationGroupConfig> assetMap =
                TwPopulationGroupConfig.getAssetMap();
        Collection<TwPopulationGroupConfig> configs =
                assetMap == null || assetMap.getAssetMap() == null
                        ? List.of()
                        : assetMap.getAssetMap().values();
        PopulationGroupConfigRegistry.ReloadResult result =
                registry.replace(configs, ++assetRevision);
        if (!result.applied()) {
            plugin.getLogger().at(Level.WARNING).log(
                    "Population-group reload rejected; retaining revision "
                            + result.active().revision()
                            + ": " + result.error()
            );
        }
        return result.applied();
    }

    /** Publishes one successful non-initial family reload. */
    @FunctionalInterface
    public interface ReloadPublisher {
        void publish(
                @Nonnull TameworkConfigFamily family,
                @Nonnull Iterable<String> changedIds
        );
    }
}
