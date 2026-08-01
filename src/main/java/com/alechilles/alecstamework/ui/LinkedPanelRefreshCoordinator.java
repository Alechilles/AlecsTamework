package com.alechilles.alecstamework.ui;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Coalesces linked-panel refresh requests and schedules their next safe wake.
 */
public final class LinkedPanelRefreshCoordinator implements AutoCloseable {

    private static final long PROGRESSION_INTERVAL_MS = 5_000L;
    private static final long COUNTDOWN_COARSE_INTERVAL_MS = 10_000L;
    private static final long COUNTDOWN_FINE_INTERVAL_MS = 1_000L;
    private static final long SAFETY_INTERVAL_MS = 30_000L;

    private final LongSupplier clock;
    private final LinkedPanelRefreshSignalSource scheduler;
    private final Runnable refreshCallback;

    private boolean closed;
    private boolean progressionRendered;
    private long lastProgressionRenderMs;
    private boolean immediatePending;
    private boolean progressionPending;
    private boolean countdownPending;
    private boolean safetyPending;
    private long immediateVersion;
    private long progressionVersion;
    private long countdownVersion;
    private long safetyVersion;

    /**
     * Creates a coordinator using the supplied clock, scheduler, and UI refresh callback.
     *
     * @param clock millisecond clock
     * @param scheduler delayed callback scheduler
     * @param refreshCallback callback that refreshes the linked panel
     */
    public LinkedPanelRefreshCoordinator(
            LongSupplier clock,
            LinkedPanelRefreshSignalSource scheduler,
            Runnable refreshCallback
    ) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.refreshCallback = Objects.requireNonNull(refreshCallback, "refreshCallback");
    }

    /**
     * Starts the recurring safety wake.
     */
    public synchronized void start() {
        if (!closed) {
            scheduleSafety();
        }
    }

    /**
     * Requests a coalesced refresh for the supplied signal kind.
     *
     * @param kind requested refresh kind
     */
    public synchronized void request(LinkedPanelRefreshSignal.Kind kind) {
        if (closed) {
            return;
        }
        if (kind == LinkedPanelRefreshSignal.Kind.IMMEDIATE) {
            scheduleImmediate();
            return;
        }
        scheduleProgression();
    }

    /**
     * Records the completed render so subsequent progression and countdown wakes can be timed.
     *
     * @param progressionIncluded whether the render included progression data
     * @param shortestCountdownRemainingMs shortest rendered countdown, or zero when absent
     */
    public synchronized void recordRendered(boolean progressionIncluded, long shortestCountdownRemainingMs) {
        if (closed) {
            return;
        }
        if (progressionIncluded) {
            progressionRendered = true;
            lastProgressionRenderMs = clock.getAsLong();
            invalidateProgression();
        }
        scheduleCountdown(shortestCountdownRemainingMs);
    }

    /**
     * Invalidates all queued callbacks permanently.
     */
    @Override
    public synchronized void close() {
        closed = true;
        invalidateImmediate();
        invalidateProgression();
        invalidateCountdown();
        invalidateSafety();
    }

    private void scheduleImmediate() {
        if (immediatePending) {
            return;
        }
        immediatePending = true;
        long version = ++immediateVersion;
        scheduler.schedule(0L, () -> runImmediate(version));
    }

    private void scheduleProgression() {
        if (progressionPending) {
            return;
        }
        long delayMs = progressionRendered
                ? Math.max(0L, lastProgressionRenderMs + PROGRESSION_INTERVAL_MS - clock.getAsLong())
                : 0L;
        progressionPending = true;
        long version = ++progressionVersion;
        scheduler.schedule(delayMs, () -> runProgression(version));
    }

    private void scheduleCountdown(long remainingMs) {
        invalidateCountdown();
        if (remainingMs <= 0L) {
            return;
        }
        long delayMs = countdownDelay(remainingMs);
        countdownPending = true;
        long version = ++countdownVersion;
        scheduler.schedule(delayMs, () -> runCountdown(version));
    }

    private void scheduleSafety() {
        if (safetyPending) {
            return;
        }
        safetyPending = true;
        long version = ++safetyVersion;
        scheduler.schedule(SAFETY_INTERVAL_MS, () -> runSafety(version));
    }

    private static long countdownDelay(long remainingMs) {
        if (remainingMs > COUNTDOWN_COARSE_INTERVAL_MS) {
            return COUNTDOWN_COARSE_INTERVAL_MS;
        }
        if (remainingMs >= COUNTDOWN_FINE_INTERVAL_MS) {
            return COUNTDOWN_FINE_INTERVAL_MS;
        }
        return remainingMs;
    }

    private void runImmediate(long version) {
        if (consumeImmediate(version)) {
            refreshCallback.run();
        }
    }

    private void runProgression(long version) {
        if (consumeProgression(version)) {
            refreshCallback.run();
        }
    }

    private void runCountdown(long version) {
        if (consumeCountdown(version)) {
            refreshCallback.run();
        }
    }

    private void runSafety(long version) {
        if (consumeSafety(version)) {
            refreshCallback.run();
        }
    }

    private synchronized boolean consumeImmediate(long version) {
        if (closed || version != immediateVersion || !immediatePending) {
            return false;
        }
        immediatePending = false;
        return true;
    }

    private synchronized boolean consumeProgression(long version) {
        if (closed || version != progressionVersion || !progressionPending) {
            return false;
        }
        progressionPending = false;
        return true;
    }

    private synchronized boolean consumeCountdown(long version) {
        if (closed || version != countdownVersion || !countdownPending) {
            return false;
        }
        countdownPending = false;
        return true;
    }

    private synchronized boolean consumeSafety(long version) {
        if (closed || version != safetyVersion || !safetyPending) {
            return false;
        }
        safetyPending = false;
        scheduleSafety();
        return true;
    }

    private void invalidateImmediate() {
        immediatePending = false;
        immediateVersion++;
    }

    private void invalidateProgression() {
        progressionPending = false;
        progressionVersion++;
    }

    private void invalidateCountdown() {
        countdownPending = false;
        countdownVersion++;
    }

    private void invalidateSafety() {
        safetyPending = false;
        safetyVersion++;
    }
}
