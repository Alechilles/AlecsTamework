package com.alechilles.alecstamework;

import com.alechilles.alecstamework.commands.SpawnBeaconVisualizationService;
import com.alechilles.alecstamework.commands.TameworkCommandRoot;
import com.alechilles.alecstamework.companion.flow.ReleaseFlow;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.alechilles.alecstamework.persistence.runtime.PersistenceFailureSignal;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Consumer;

/** Builds the runtime command root. The old persistence diagnostics are absent after the index cut-over. */
final class TameworkCommandRootFactory {
    private TameworkCommandRootFactory() {
    }

    /** {@code releaseFlow} and {@code companions} are null when the companion index is not ready. */
    @Nonnull
    static TameworkCommandRoot create(
            @Nonnull SpawnBeaconVisualizationService spawnBeacons,
            @Nullable Consumer<PersistenceFailureSignal> failureSink,
            @Nullable ReleaseFlow releaseFlow,
            @Nullable CompanionQueries companions
    ) {
        return new TameworkCommandRoot(
                null, null, null, spawnBeacons, null, null, failureSink,
                releaseFlow, companions
        );
    }
}
