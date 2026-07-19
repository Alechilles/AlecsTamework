package com.alechilles.alecstamework.persistence.diagnostics;

import java.util.List;
import javax.annotation.Nonnull;

/** Fans out immutable diagnostics while isolating every optional sink. */
public final class CompositePersistenceIncidentSink implements PersistenceIncidentSink {
    private final List<PersistenceIncidentSink> sinks;

    public CompositePersistenceIncidentSink(@Nonnull List<PersistenceIncidentSink> sinks) {
        this.sinks = List.copyOf(sinks);
    }

    @Override
    public void record(@Nonnull PersistenceIncidentEvent event) {
        for (PersistenceIncidentSink sink : sinks) {
            try {
                sink.record(event);
            } catch (Throwable ignored) {
                // Diagnostics are never persistence authority.
            }
        }
    }
}
