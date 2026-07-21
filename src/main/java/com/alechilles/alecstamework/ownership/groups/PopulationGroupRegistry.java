package com.alechilles.alecstamework.ownership.groups;

import com.alechilles.alecstamework.config.assets.TwPopulationGroupConfig;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;

/** Atomically retains the last valid population-group mapping. */
public final class PopulationGroupRegistry {
    private final AtomicReference<PopulationGroupIndex> current = new AtomicReference<>(PopulationGroupIndex.empty());

    public PopulationGroupIndex snapshot() { return current.get(); }

    public ReloadResult replace(@Nonnull Collection<TwPopulationGroupConfig> configs, long revision) {
        Objects.requireNonNull(configs, "configs");
        try {
            PopulationGroupIndex replacement = PopulationGroupIndex.compile(configs, revision);
            current.set(replacement);
            return new ReloadResult(true, replacement, null);
        } catch (RuntimeException invalid) {
            return new ReloadResult(false, current.get(), invalid.getMessage());
        }
    }

    public record ReloadResult(boolean applied, @Nonnull PopulationGroupIndex active, String error) {
        public ReloadResult {
            Objects.requireNonNull(active, "active");
            if (!applied && (error == null || error.isBlank())) error = "population-group-index-invalid";
        }
    }
}
