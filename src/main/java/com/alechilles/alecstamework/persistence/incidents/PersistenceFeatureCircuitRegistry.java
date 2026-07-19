package com.alechilles.alecstamework.persistence.incidents;

import java.util.EnumMap;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Process-local projection of persisted operator feature circuit states; all domains default enabled. */
public final class PersistenceFeatureCircuitRegistry {
    private final EnumMap<PersistenceDomain, CircuitState> states = new EnumMap<>(PersistenceDomain.class);

    public PersistenceFeatureCircuitRegistry() {
        for (PersistenceDomain domain : PersistenceDomain.values()) {
            states.put(domain, new CircuitState(true, null, 0L, null));
        }
    }

    public synchronized void publish(@Nonnull PersistenceDomain domain, boolean enabled,
                                     @Nullable String reasonCode, long updatedAtMs,
                                     @Nullable String updatedBy) {
        states.put(domain, new CircuitState(enabled, normalize(reasonCode), updatedAtMs, normalize(updatedBy)));
    }

    public synchronized boolean isEnabled(@Nonnull PersistenceDomain domain) {
        CircuitState global = states.get(PersistenceDomain.ALL_PERSISTENCE);
        CircuitState specific = states.get(domain);
        return (global == null || global.enabled()) && (specific == null || specific.enabled());
    }

    @Nonnull
    public synchronized Map<PersistenceDomain, CircuitState> snapshot() {
        return Map.copyOf(states);
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record CircuitState(boolean enabled, @Nullable String reasonCode,
                               long updatedAtMs, @Nullable String updatedBy) {
    }
}
