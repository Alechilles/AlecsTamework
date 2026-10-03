package com.alechilles.alecstamework;

import com.alechilles.alecstamework.commands.SpawnBeaconVisualizationService;
import com.alechilles.alecstamework.commands.TameworkCommandRoot;
import com.alechilles.alecstamework.companion.flow.ReleaseFlow;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Builds the runtime command root. */
final class TameworkCommandRootFactory {
    private TameworkCommandRootFactory() {
    }

    /** {@code releaseFlow} and {@code companions} are null when the companion index is not ready. */
    @Nonnull
    static TameworkCommandRoot create(
            @Nonnull SpawnBeaconVisualizationService spawnBeacons,
            @Nullable ReleaseFlow releaseFlow,
            @Nullable CompanionQueries companions
    ) {
        return new TameworkCommandRoot(spawnBeacons, releaseFlow, companions);
    }
}
