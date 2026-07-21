package com.alechilles.alecstamework.items.capturepolicy;

import com.alechilles.alecstamework.config.assets.TwCapturePolicyConfig;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;

/** Atomically retains the last valid compiled capture-policy index. */
public final class CapturePolicyRegistry {
    private final AtomicReference<CapturePolicyIndex> current = new AtomicReference<>(CapturePolicyIndex.empty());

    @Nonnull
    public CapturePolicyIndex snapshot() { return current.get(); }

    @Nonnull
    public ReloadResult replace(@Nonnull Collection<TwCapturePolicyConfig> configs, long revision) {
        Objects.requireNonNull(configs, "configs");
        try {
            CapturePolicyIndex replacement = CapturePolicyIndex.compile(configs, revision);
            current.set(replacement);
            return new ReloadResult(true, replacement, null);
        } catch (RuntimeException invalid) {
            return new ReloadResult(false, current.get(), invalid.getMessage());
        }
    }

    public record ReloadResult(boolean applied, @Nonnull CapturePolicyIndex active, String error) {
        public ReloadResult {
            Objects.requireNonNull(active, "active");
            if (!applied && (error == null || error.isBlank())) error = "capture-policy-index-invalid";
        }
    }
}
