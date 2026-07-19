package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.persistence.health.PersistenceStorageHealthService;
import com.alechilles.alecstamework.persistence.health.PersistenceStorageState;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Compatibility facade for callers that still consume the historical healthy/degraded view.
 * New persistence authority code must use {@link PersistenceStorageHealthService} directly.
 */
public final class PersistenceHealthService {
    public enum Status {
        HEALTHY,
        DEGRADED
    }

    private final PersistenceStorageHealthService storageHealth;
    private final Consumer<HealthState> degradationListener;

    public PersistenceHealthService() {
        this(new PersistenceStorageHealthService(), null);
    }

    public PersistenceHealthService(@Nullable Consumer<HealthState> degradationListener) {
        this(new PersistenceStorageHealthService(), degradationListener);
    }

    public PersistenceHealthService(@Nonnull PersistenceStorageHealthService storageHealth) {
        this(storageHealth, null);
    }

    public PersistenceHealthService(@Nonnull PersistenceStorageHealthService storageHealth,
                                    @Nullable Consumer<HealthState> degradationListener) {
        this.storageHealth = storageHealth;
        this.degradationListener = degradationListener == null ? ignored -> { } : degradationListener;
    }

    public boolean isHealthy() {
        return storageHealth.acceptsWrites();
    }

    @Nonnull
    public HealthState getState() {
        PersistenceStorageHealthService.State state = storageHealth.getState();
        Status status = state.status() == PersistenceStorageState.HEALTHY
                || state.status() == PersistenceStorageState.RETRYING
                ? Status.HEALTHY : Status.DEGRADED;
        return new HealthState(status, state.reason(), state.changedAtMs());
    }

    /** Retained for compatibility; verified recovery should use the storage recovery coordinator. */
    public void markHealthy() {
        PersistenceStorageState state = storageHealth.getState().status();
        if (state == PersistenceStorageState.RETRYING) {
            storageHealth.finishRetry();
        } else if (state == PersistenceStorageState.READ_ONLY) {
            storageHealth.beginRecovery();
            storageHealth.completeRecovery();
        } else if (state == PersistenceStorageState.RECOVERING) {
            storageHealth.completeRecovery();
        }
    }

    /**
     * Retains the initiating failure until verified recovery marks storage healthy.
     *
     * @return {@code true} only when this call performed the transition to read-only
     */
    public boolean markDegraded(@Nonnull String reason) {
        boolean transitioned = storageHealth.enterReadOnly(reason, null);
        if (transitioned) {
            try {
                degradationListener.accept(getState());
            } catch (RuntimeException ignored) {
                // Storage quarantine must remain effective even if diagnostics fail.
            }
        }
        return transitioned;
    }

    @Nonnull
    public PersistenceStorageHealthService getStorageHealthService() {
        return storageHealth;
    }

    public record HealthState(@Nonnull Status status, @Nullable String reason, long lastFailureAtMs) {
    }
}
