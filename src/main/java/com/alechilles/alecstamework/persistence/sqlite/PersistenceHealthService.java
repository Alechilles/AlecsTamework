package com.alechilles.alecstamework.persistence.sqlite;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
    private final Consumer<HealthState> degradationListener;

    public PersistenceHealthService() {
        this(null);
    }

    public PersistenceHealthService(@Nullable Consumer<HealthState> degradationListener) {
        this.degradationListener = degradationListener == null ? ignored -> { } : degradationListener;
    }

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

    /**
     * Retains the initiating failure until an explicit recovery marks the service healthy.
     *
     * @return {@code true} only when this call performed the healthy-to-degraded transition
     */
    public boolean markDegraded(@Nonnull String reason) {
        String normalizedReason = reason == null || reason.isBlank() ? "unknown" : reason.trim();
        HealthState degraded = new HealthState(
                Status.DEGRADED, normalizedReason, System.currentTimeMillis());
        while (true) {
            HealthState current = state.get();
            if (current.status() == Status.DEGRADED) {
                return false;
            }
            if (state.compareAndSet(current, degraded)) {
                break;
            }
        }
        try {
            degradationListener.accept(degraded);
        } catch (RuntimeException ignored) {
            // Health quarantine must remain effective even if its diagnostic sink fails.
        }
        return true;
    }

    public record HealthState(Status status, @Nullable String reason, long lastFailureAtMs) {
    }
}
