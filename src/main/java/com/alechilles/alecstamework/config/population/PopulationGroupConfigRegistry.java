package com.alechilles.alecstamework.config.population;

import com.alechilles.alecstamework.config.assets.TwPopulationGroupConfig;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Atomically retains the last valid population-group config index. */
public final class PopulationGroupConfigRegistry {
    private final AtomicReference<PopulationGroupConfigIndex> current =
            new AtomicReference<>(PopulationGroupConfigIndex.empty());

    @Nonnull
    public PopulationGroupConfigIndex snapshot() {
        return current.get();
    }

    @Nonnull
    public ReloadResult replace(
            @Nonnull Collection<TwPopulationGroupConfig> configs,
            long revision
    ) {
        Objects.requireNonNull(configs, "configs");
        try {
            PopulationGroupConfigIndex replacement =
                    PopulationGroupConfigIndex.compile(configs, revision);
            current.set(replacement);
            return new ReloadResult(true, replacement, null);
        } catch (RuntimeException invalid) {
            return new ReloadResult(
                    false,
                    current.get(),
                    invalid.getMessage()
            );
        }
    }

    /** Result of one atomic config replacement attempt. */
    public record ReloadResult(
            boolean applied,
            @Nonnull PopulationGroupConfigIndex active,
            @Nullable String error
    ) {
        public ReloadResult {
            Objects.requireNonNull(active, "active");
            if (!applied && (error == null || error.isBlank())) {
                error = "population-group-index-invalid";
            }
        }
    }
}
