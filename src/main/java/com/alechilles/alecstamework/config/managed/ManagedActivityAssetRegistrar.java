package com.alechilles.alecstamework.config.managed;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.TameworkConfigFamily;
import com.alechilles.alecstamework.config.assets.TwManagedActivityConfig;
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
 * Owns managed-activity asset registration and atomic resolver reloads.
 *
 * <p>Asset events are subscribed only after the owning runtime module is
 * active. A failed replacement keeps the previous immutable resolver.</p>
 */
public final class ManagedActivityAssetRegistrar {
    private final Tamework plugin;
    private final ManagedActivityConfigRegistry registry;
    private final ReloadPublisher reloadPublisher;
    private boolean registered;
    private long assetRevision;

    public ManagedActivityAssetRegistrar(
            @Nonnull Tamework plugin,
            @Nonnull ManagedActivityConfigRegistry registry,
            @Nonnull ReloadPublisher reloadPublisher
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.reloadPublisher = Objects.requireNonNull(
                reloadPublisher,
                "reloadPublisher"
        );
    }

    /** Registers the passive Hytale asset store during plugin setup. */
    public void registerAssetStore() {
        if (registered) {
            return;
        }
        plugin.getAssetRegistry().register(
                HytaleAssetStore.builder(
                                TwManagedActivityConfig.class,
                                new DefaultAssetMap<>()
                        )
                        .setPath("Tamework/ManagedActivities")
                        .setCodec(TwManagedActivityConfig.CODEC)
                        .setKeyFunction(TwManagedActivityConfig::getId)
                        .build()
        );
        registered = true;
    }

    /** Starts load/remove listeners and performs the first resolver build. */
    public void activate() {
        if (!registered) {
            throw new IllegalStateException(
                    "Managed-activity asset store is not registered"
            );
        }
        plugin.getEventRegistry().register(
                LoadedAssetsEvent.class,
                TwManagedActivityConfig.class,
                this::onLoaded
        );
        plugin.getEventRegistry().register(
                RemovedAssetsEvent.class,
                TwManagedActivityConfig.class,
                this::onRemoved
        );
        initialize();
    }

    /** Rebuilds the resolver without installing another listener. */
    public void initialize() {
        rebuild(false, List.of());
    }

    /** Rebuilds after a population-group replacement changed role membership. */
    public void onPopulationGroupsChanged() {
        rebuild(true, List.of());
    }

    private void onLoaded(
            LoadedAssetsEvent<
                    String,
                    TwManagedActivityConfig,
                    DefaultAssetMap<String, TwManagedActivityConfig>
                    > event
    ) {
        rebuild(
                !event.isInitial(),
                event.getLoadedAssets().keySet()
        );
    }

    private void onRemoved(
            RemovedAssetsEvent<
                    String,
                    TwManagedActivityConfig,
                    DefaultAssetMap<String, TwManagedActivityConfig>
                    > event
    ) {
        rebuild(true, event.getRemovedAssets());
    }

    private void rebuild(
            boolean publish,
            Iterable<String> changedIds
    ) {
        TwManagedActivityConfig.clearInheritanceFallbackCache();
        DefaultAssetMap<String, TwManagedActivityConfig> assetMap =
                TwManagedActivityConfig.getAssetMap();
        Collection<TwManagedActivityConfig> configs =
                assetMap == null || assetMap.getAssetMap() == null
                        ? List.of()
                        : assetMap.getAssetMap().values();
        ManagedActivityConfigRegistry.ReloadResult result =
                registry.replace(configs, ++assetRevision);
        if (!result.applied()) {
            plugin.getLogger().at(Level.WARNING).log(
                    "Managed-activity reload rejected; retaining revision "
                            + result.active().revision()
                            + ": " + result.error()
            );
            return;
        }
        if (publish) {
            reloadPublisher.publish(
                    TameworkConfigFamily.MANAGED_ACTIVITY,
                    changedIds
            );
        }
    }

    /** Publishes one successful managed-activity family reload. */
    @FunctionalInterface
    public interface ReloadPublisher {
        void publish(
                @Nonnull TameworkConfigFamily family,
                @Nonnull Iterable<String> changedIds
        );
    }
}
