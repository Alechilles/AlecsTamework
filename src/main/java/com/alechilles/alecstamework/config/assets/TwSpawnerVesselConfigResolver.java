package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.api.SpawnerVesselConfigView;
import com.alechilles.alecstamework.vessels.runtime.ProductionBondedVesselTransitionPlanner;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** Revision-aware read adapter over the inherited TwSpawner asset map. */
public final class TwSpawnerVesselConfigResolver
        implements ProductionBondedVesselTransitionPlanner.ConfigResolver {
    private final LongSupplier currentRevision;

    public TwSpawnerVesselConfigResolver(@Nonnull LongSupplier currentRevision) {
        this.currentRevision = Objects.requireNonNull(currentRevision, "currentRevision");
    }

    @Nonnull
    @Override
    public Optional<SpawnerVesselConfigView> resolve(@Nonnull String configId, long configRevision) {
        Objects.requireNonNull(configId, "configId");
        if (configRevision < 0L || currentRevision.getAsLong() != configRevision) {
            return Optional.empty();
        }
        DefaultAssetMap<String, TwSpawnerConfig> assetMap = TwSpawnerConfig.getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) return Optional.empty();
        TwSpawnerConfig config = assetMap.getAssetMap().get(configId);
        return config == null ? Optional.empty() : Optional.of(config.toVesselConfigView(configRevision));
    }

    @Nonnull
    public Optional<SpawnerVesselConfigView> getById(@Nonnull String configId) {
        long revision = currentRevision.getAsLong();
        return resolve(configId, revision);
    }

    @Nonnull
    public Optional<SpawnerVesselConfigView> resolveForItemId(@Nonnull String itemId) {
        Objects.requireNonNull(itemId, "itemId");
        if (itemId.isBlank()) return Optional.empty();
        long revision = currentRevision.getAsLong();
        DefaultAssetMap<String, TwSpawnerConfig> assetMap = TwSpawnerConfig.getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) return Optional.empty();
        for (TwSpawnerConfig config : assetMap.getAssetMap().values()) {
            if (config != null && config.matchesVesselItemId(itemId)) {
                return Optional.of(config.toVesselConfigView(revision));
            }
        }
        return Optional.empty();
    }
}
