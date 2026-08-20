package com.alechilles.alecstamework.items.persistence.maintenance;

import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Handler contract used when maintenance may return a typed deferral. */
@FunctionalInterface
public interface MaintenanceWorkHandler<K, V> {
    @Nonnull
    CompletionStage<? extends MaintenanceWorkOutcome<V>> apply(
            @Nonnull K key,
            @Nonnull V value
    );
}
