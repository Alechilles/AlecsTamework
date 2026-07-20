package com.alechilles.alecstamework.persistence.incidents;

import java.util.EnumMap;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Process-local projection of persisted operator feature circuit states; all domains default enabled. */
public final class PersistenceFeatureCircuitRegistry {
    private final EnumMap<PersistenceDomain, Boolean> defaults = new EnumMap<>(PersistenceDomain.class);
    private final EnumMap<PersistenceDomain, CircuitState> overrides = new EnumMap<>(PersistenceDomain.class);
    private final EnumMap<PersistenceDomain, CircuitState> states = new EnumMap<>(PersistenceDomain.class);

    public PersistenceFeatureCircuitRegistry() {
        for (PersistenceDomain domain : PersistenceDomain.values()) {
            defaults.put(domain, true);
        }
        rebuild();
    }

    public synchronized void publish(@Nonnull PersistenceDomain domain, boolean enabled,
                                     @Nullable String reasonCode, long updatedAtMs,
                                     @Nullable String updatedBy) {
        CircuitState override = new CircuitState(
                enabled, normalize(reasonCode), updatedAtMs, normalize(updatedBy));
        overrides.put(domain, override);
        states.put(domain, override);
    }

    public synchronized boolean isEnabled(@Nonnull PersistenceDomain domain) {
        CircuitState global = states.get(PersistenceDomain.ALL_PERSISTENCE);
        CircuitState specific = states.get(domain);
        return (global == null || global.enabled()) && (specific == null || specific.enabled());
    }

    public synchronized void reload(
            @Nonnull Map<PersistenceDomain, CircuitState> persistedStates) {
        overrides.clear();
        overrides.putAll(persistedStates);
        rebuild();
    }

    /** Applies asset-backed defaults without replacing durable administrator overrides. */
    public synchronized void applyDefaults(
            @Nonnull Map<PersistenceDomain, Boolean> configuredDefaults) {
        for (PersistenceDomain domain : PersistenceDomain.values()) {
            defaults.put(domain, configuredDefaults.getOrDefault(domain, true));
        }
        rebuild();
    }

    @Nonnull
    public synchronized Map<PersistenceDomain, CircuitState> snapshot() {
        return Map.copyOf(states);
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void rebuild() {
        states.clear();
        for (PersistenceDomain domain : PersistenceDomain.values()) {
            CircuitState effective = overrides.get(domain);
            if (effective == null) {
                effective = new CircuitState(defaults.getOrDefault(domain, true), null, 0L, null);
            }
            states.put(domain, effective);
        }
    }

    public record CircuitState(boolean enabled, @Nullable String reasonCode,
                               long updatedAtMs, @Nullable String updatedBy) {
    }
}
