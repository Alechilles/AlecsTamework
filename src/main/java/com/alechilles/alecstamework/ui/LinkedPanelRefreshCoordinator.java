package com.alechilles.alecstamework.ui;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Coalesces linked-panel refresh requests and schedules their next safe wake.
 */
public final class LinkedPanelRefreshCoordinator implements AutoCloseable {

    /** Value used by {@link #recordRendered(RenderPermit, boolean, long)} when no countdown is visible. */
    public static final long NO_COUNTDOWN_REMAINING_MS = -1L;

    private static final long PROGRESSION_INTERVAL_MS = 5_000L;
    private static final long INTERACTION_FEEDBACK_DELAY_MS = 500L;
    private static final long COUNTDOWN_COARSE_INTERVAL_MS = 10_000L;
    private static final long COUNTDOWN_FINE_INTERVAL_MS = 1_000L;
    private static final long SAFETY_INTERVAL_MS = 30_000L;

    private final LongSupplier clock;
    private final DelayedScheduler scheduler;
    private final Consumer<RenderPermit> refreshCallback;

    private boolean closed;
    private boolean progressionRendered;
    private long lastProgressionRenderMs;
    private long outstandingProgressionPermitId;
    private long nextPermitId;
    private boolean progressionDirty;
    private boolean countdownExpirationWakeAttempted;
    private boolean immediatePending;
    private boolean interactionFeedbackPending;
    private boolean progressionPending;
    private boolean countdownPending;
    private boolean safetyPending;
    private long immediateVersion;
    private long interactionFeedbackVersion;
    private long progressionVersion;
    private long countdownVersion;
    private long safetyVersion;

    /**
     * Creates a coordinator using the supplied clock, scheduler, and UI refresh callback.
     *
     * @param clock millisecond clock
     * @param scheduler delayed callback scheduler
     * @param refreshCallback callback that refreshes the linked panel with its progression permit
     */
    public LinkedPanelRefreshCoordinator(
            LongSupplier clock,
            DelayedScheduler scheduler,
            Consumer<RenderPermit> refreshCallback
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
     * Seeds the scheduling boundary from the page's initial, non-permit render.
     */
    public synchronized void seedInitialRender(
            boolean progressionIncluded, long shortestCountdownRemainingMs
    ) {
        if (closed) return;
        if (progressionIncluded) {
            progressionRendered = true;
            lastProgressionRenderMs = clock.getAsLong();
        }
        scheduleCountdown(shortestCountdownRemainingMs);
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
        if (Objects.requireNonNull(kind, "kind") == LinkedPanelRefreshSignal.Kind.IMMEDIATE) {
            scheduleImmediate();
            return;
        }
        if (outstandingProgressionPermitId != 0L) {
            progressionDirty = true;
        } else {
            scheduleProgression();
        }
    }

    /**
     * Restarts a short trailing wake used to reconcile repeatable controls
     * after their current click burst has ended.
     */
    synchronized void requestInteractionFeedback() {
        if (closed) return;
        interactionFeedbackPending = true;
        long version = ++interactionFeedbackVersion;
        scheduler.schedule(INTERACTION_FEEDBACK_DELAY_MS,
                () -> runInteractionFeedback(version));
    }

    /**
     * Records the completed render so subsequent progression and countdown wakes can be timed.
     * A visible countdown at zero requests an immediate expiration refresh; use
     * {@link #NO_COUNTDOWN_REMAINING_MS} only when no countdown was rendered.
     *
     * @param progressionIncluded whether the render included progression data
     * @param shortestCountdownRemainingMs shortest rendered countdown, zero when visibly expired,
     *                                     or {@link #NO_COUNTDOWN_REMAINING_MS} when absent
     */
    public synchronized void recordRendered(
            RenderPermit permit,
            boolean progressionIncluded,
            long shortestCountdownRemainingMs
    ) {
        if (closed) {
            return;
        }
        Objects.requireNonNull(permit, "permit");
        boolean ownsProgressionPermit = permit.progressionEligible()
                && permit.id() == outstandingProgressionPermitId;
        if (permit.progressionEligible() && !ownsProgressionPermit) {
            return;
        }
        if (progressionIncluded && !permit.progressionEligible()) {
            throw new IllegalArgumentException("A non-progression permit cannot complete a progression render.");
        }
        if (ownsProgressionPermit) {
            outstandingProgressionPermitId = 0L;
        }
        if (progressionIncluded) {
            progressionRendered = true;
            lastProgressionRenderMs = clock.getAsLong();
            invalidateProgression();
        }
        scheduleCountdown(shortestCountdownRemainingMs);
        if (ownsProgressionPermit && progressionDirty) {
            progressionDirty = false;
            scheduleProgression();
        }
    }

    /**
     * Invalidates all queued callbacks permanently and waits for an admitted callback to finish.
     */
    @Override
    public synchronized void close() {
        closed = true;
        outstandingProgressionPermitId = 0L;
        progressionDirty = false;
        invalidateImmediate();
        invalidateInteractionFeedback();
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
        if (remainingMs == NO_COUNTDOWN_REMAINING_MS) {
            countdownExpirationWakeAttempted = false;
            return;
        }
        if (remainingMs > 0L) {
            countdownExpirationWakeAttempted = false;
        } else if (countdownExpirationWakeAttempted) {
            return;
        } else {
            countdownExpirationWakeAttempted = true;
        }
        long delayMs = countdownDelay(Math.max(0L, remainingMs));
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

    private synchronized void runImmediate(long version) {
        if (closed || version != immediateVersion || !immediatePending) {
            return;
        }
        immediatePending = false;
        admitRefresh();
    }

    private synchronized void runInteractionFeedback(long version) {
        if (closed || version != interactionFeedbackVersion
                || !interactionFeedbackPending) {
            return;
        }
        interactionFeedbackPending = false;
        admitRefresh();
    }

    private synchronized void runProgression(long version) {
        if (closed || version != progressionVersion || !progressionPending) {
            return;
        }
        progressionPending = false;
        admitRefresh();
    }

    private synchronized void runCountdown(long version) {
        if (closed || version != countdownVersion || !countdownPending) {
            return;
        }
        countdownPending = false;
        admitRefresh();
    }

    private synchronized void runSafety(long version) {
        if (closed || version != safetyVersion || !safetyPending) {
            return;
        }
        safetyPending = false;
        scheduleSafety();
        admitRefresh();
    }

    private void admitRefresh() {
        boolean progressionEligible = outstandingProgressionPermitId == 0L && (!progressionRendered
                || clock.getAsLong() - lastProgressionRenderMs >= PROGRESSION_INTERVAL_MS);
        long permitId = ++nextPermitId;
        if (progressionEligible) {
            outstandingProgressionPermitId = permitId;
        }
        refreshCallback.accept(new RenderPermit(permitId, progressionEligible));
    }

    private void invalidateImmediate() {
        immediatePending = false;
        immediateVersion++;
    }

    private void invalidateInteractionFeedback() {
        interactionFeedbackPending = false;
        interactionFeedbackVersion++;
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

    /**
     * Refresh admission state consumed by the page renderer.
     *
     * @param id coordinator-assigned admission identity
     * @param progressionEligible whether this render may include fresh progression values
     */
    public record RenderPermit(long id, boolean progressionEligible) {
    }

    /**
     * Allows a later visible zero countdown to request one fresh expiration wake.
     * Call this only after an authoritative countdown state-change signal.
     */
    public synchronized void rearmCountdownExpirationWake() {
        if (!closed) {
            countdownExpirationWakeAttempted = false;
        }
    }

    /**
     * Schedules delayed coordinator callbacks without coupling page signals to executor mechanics.
     */
    @FunctionalInterface
    public interface DelayedScheduler {

        /**
         * Creates the production scheduler backed by CompletableFuture's delayed executor.
         *
         * @return production delayed scheduler
         */
        static DelayedScheduler production() {
            return (delayMs, callback) -> CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS)
                    .execute(callback);
        }

        /**
         * Schedules a callback after the supplied delay.
         *
         * @param delayMs delay in milliseconds
         * @param callback work to run after the delay
         */
        void schedule(long delayMs, Runnable callback);
    }
}
