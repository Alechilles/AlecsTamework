package com.alechilles.alecstamework.persistence.health;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Owns only global storage authority state and preserves the first read-only cause. */
public final class PersistenceStorageHealthService {
    private final AtomicReference<State> state = new AtomicReference<>(State.healthy());
    private final Consumer<State> transitionListener;

    public PersistenceStorageHealthService() {
        this(null);
    }

    public PersistenceStorageHealthService(@Nullable Consumer<State> transitionListener) {
        this.transitionListener = transitionListener == null ? ignored -> { } : transitionListener;
    }

    public boolean acceptsWrites() {
        PersistenceStorageState current = state.get().status();
        return current == PersistenceStorageState.HEALTHY || current == PersistenceStorageState.RETRYING;
    }

    @Nonnull
    public State getState() {
        return state.get();
    }

    public boolean enterRetrying(@Nonnull String reason) {
        return transitionFrom(PersistenceStorageState.HEALTHY,
                new State(PersistenceStorageState.RETRYING, normalize(reason), null, System.currentTimeMillis()));
    }

    public boolean finishRetry() {
        return transitionFrom(PersistenceStorageState.RETRYING, State.healthy());
    }

    public boolean enterReadOnly(@Nonnull String reason, @Nullable String incidentId) {
        State next = new State(PersistenceStorageState.READ_ONLY, normalize(reason), normalizeNullable(incidentId),
                System.currentTimeMillis());
        while (true) {
            State current = state.get();
            if (current.status() == PersistenceStorageState.READ_ONLY
                    || current.status() == PersistenceStorageState.RECOVERING
                    || current.status() == PersistenceStorageState.CLOSED) {
                return false;
            }
            if (state.compareAndSet(current, next)) {
                notifyListener(next);
                return true;
            }
        }
    }

    public boolean beginRecovery() {
        State current = state.get();
        if (current.status() != PersistenceStorageState.READ_ONLY) {
            return false;
        }
        State next = new State(PersistenceStorageState.RECOVERING, current.reason(), current.incidentId(),
                current.changedAtMs());
        return compareAndNotify(current, next);
    }

    public boolean completeRecovery() {
        return transitionFrom(PersistenceStorageState.RECOVERING, State.healthy());
    }

    public boolean failRecovery(@Nonnull String reason) {
        State current = state.get();
        if (current.status() != PersistenceStorageState.RECOVERING) return false;
        State readOnly = new State(PersistenceStorageState.READ_ONLY, normalize(reason),
                current.incidentId(), System.currentTimeMillis());
        return compareAndNotify(current, readOnly);
    }

    public void close() {
        State current = state.get();
        State closed = new State(PersistenceStorageState.CLOSED, "intentional_shutdown", current.incidentId(),
                System.currentTimeMillis());
        state.set(closed);
        notifyListener(closed);
    }

    private boolean transitionFrom(PersistenceStorageState expected, State next) {
        State current = state.get();
        return current.status() == expected && compareAndNotify(current, next);
    }

    private boolean compareAndNotify(State current, State next) {
        if (!state.compareAndSet(current, next)) {
            return false;
        }
        notifyListener(next);
        return true;
    }

    private void notifyListener(State next) {
        try {
            transitionListener.accept(next);
        } catch (RuntimeException ignored) {
            // Diagnostics cannot alter storage authority.
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    @Nullable
    private static String normalizeNullable(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record State(@Nonnull PersistenceStorageState status,
                        @Nullable String reason,
                        @Nullable String incidentId,
                        long changedAtMs) {
        private static State healthy() {
            return new State(PersistenceStorageState.HEALTHY, null, null, 0L);
        }
    }
}
