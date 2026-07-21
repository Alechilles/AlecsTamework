package com.alechilles.alecstamework.ownership.groups;

import com.alechilles.alecstamework.api.TameworkEvent;
import javax.annotation.Nonnull;

/** Isolated post-commit delivery seam for immutable population-group events. */
@FunctionalInterface
public interface PopulationGroupEventSink {
    void emit(@Nonnull TameworkEvent event);

    static PopulationGroupEventSink noop() {
        return event -> { };
    }
}
