package com.alechilles.alecstamework.persistence.diagnostics;

import javax.annotation.Nonnull;

/** Passive diagnostics boundary; implementations may never decide or mutate canonical state. */
@FunctionalInterface
public interface PersistenceIncidentSink {
    PersistenceIncidentSink NO_OP = event -> { };

    void record(@Nonnull PersistenceIncidentEvent event);
}
