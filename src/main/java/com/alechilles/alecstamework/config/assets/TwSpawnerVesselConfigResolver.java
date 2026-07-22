package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.api.SpawnerVesselConfigView;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.vessels.runtime.ProductionBondedVesselTransitionPlanner;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nonnull;

/** Revision-aware read adapter over the last-valid compiled spawner registry. */
public final class TwSpawnerVesselConfigResolver
        implements ProductionBondedVesselTransitionPlanner.ConfigResolver {
    private final ItemFeatureRegistry registry;

    public TwSpawnerVesselConfigResolver(@Nonnull ItemFeatureRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Nonnull
    @Override
    public Optional<SpawnerVesselConfigView> resolve(@Nonnull String configId, long configRevision) {
        Objects.requireNonNull(configId, "configId");
        if (configRevision < 0L || registry.revision() != configRevision) {
            return Optional.empty();
        }
        return registry.getVesselByConfigId(configId)
                .filter(config -> config.configRevision() == configRevision);
    }

    @Nonnull
    public Optional<SpawnerVesselConfigView> getById(@Nonnull String configId) {
        long revision = registry.revision();
        return resolve(configId, revision);
    }

    @Nonnull
    public Optional<SpawnerVesselConfigView> resolveForItemId(@Nonnull String itemId) {
        Objects.requireNonNull(itemId, "itemId");
        if (itemId.isBlank()) return Optional.empty();
        long revision = registry.revision();
        return registry.resolveVesselForItemId(itemId)
                .filter(config -> config.configRevision() == revision);
    }
}
