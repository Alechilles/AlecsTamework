package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.util.StoreScopedState;
import java.util.Objects;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/**
 * Limits a shared tick system to one store scan per interval and keeps world schedules independent.
 */
final class StoreSweepCadence {
    private final long intervalMs;
    private final LongSupplier clock;
    private final StoreScopedState<State> statesByStore = new StoreScopedState<>(State::new);

    StoreSweepCadence(long intervalMs, @Nonnull LongSupplier clock) {
        if (intervalMs <= 0L) {
            throw new IllegalArgumentException("intervalMs must be positive");
        }
        this.intervalMs = intervalMs;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    boolean claim(@Nonnull Object store) {
        State state = statesByStore.get(store);
        long nowMs = clock.getAsLong();
        synchronized (state) {
            if (state.initialized && nowMs < state.nextSweepAtMs) {
                return false;
            }
            state.initialized = true;
            state.nextSweepAtMs = safeAdd(nowMs, intervalMs);
            return true;
        }
    }

    private static long safeAdd(long value, long increment) {
        if (value > Long.MAX_VALUE - increment) {
            return Long.MAX_VALUE;
        }
        return value + increment;
    }

    private static final class State {
        private boolean initialized;
        private long nextSweepAtMs;
    }
}
