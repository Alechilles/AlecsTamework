package com.alechilles.alecstamework.persistence.sqlite;

import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Tracks persistence runtime health so mutating operations can fail closed when storage is unhealthy.
 */
public final class PersistenceHealthService {
    public enum Status {
        HEALTHY,
        DEGRADED
    }

    private final AtomicReference<HealthState> state =
            new AtomicReference<>(new HealthState(Status.HEALTHY, null, 0L));

    public boolean isHealthy() {
        return state.get().status() == Status.HEALTHY;
    }

    @Nonnull
    public HealthState getState() {
        return state.get();
    }

    public void markHealthy() {
        state.set(new HealthState(Status.HEALTHY, null, 0L));
    }

    public void markDegraded(@Nonnull String reason) {
        String normalizedReason = reason == null || reason.isBlank() ? "unknown" : reason.trim();
        state.set(new HealthState(Status.DEGRADED, normalizedReason, System.currentTimeMillis()));
    }

    public record HealthState(Status status, @Nullable String reason, long lastFailureAtMs) {
    }
}
