package com.alechilles.alecstamework.api;

import java.util.Set;
import javax.annotation.Nonnull;

public record ConfigReloadedEvent(@Nonnull TameworkConfigFamily family,
                                  @Nonnull Set<String> changedIds,
                                  long emittedAtMs) implements TameworkEvent {
    public ConfigReloadedEvent {
        changedIds = Set.copyOf(changedIds);
    }
}

