package com.alechilles.alecstamework.npc.progression;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Collects optional aggregate diagnostics for needs-resource hot paths.
 *
 * <p>The disabled path is one volatile branch. The explicit needs-seek debug
 * channel enables collection. Enabled diagnostics emit one summary at most
 * every 30 seconds and never log per NPC.</p>
 */
public final class NeedsResourceHotPathDiagnostics {
    private static final Logger LOGGER =
            Logger.getLogger(NeedsResourceHotPathDiagnostics.class.getName());
    private static final long SUMMARY_INTERVAL_MS = 30_000L;

    private static final AtomicLong PREFLIGHT_REQUESTS = new AtomicLong();
    private static final AtomicLong PREFLIGHT_LEASE_HITS = new AtomicLong();
    private static final AtomicLong PREFLIGHT_LEASE_MISSES = new AtomicLong();
    private static final AtomicLong PREFLIGHT_COMPUTATIONS = new AtomicLong();
    private static final AtomicLong PREFLIGHT_INVALIDATIONS = new AtomicLong();
    private static final AtomicLong PREFLIGHT_NO_PATH_RESULTS = new AtomicLong();
    private static final AtomicLong PREFLIGHT_BUDGET_DEFERRALS = new AtomicLong();
    private static final AtomicLong COORDINATOR_LOOKUPS = new AtomicLong();
    private static final AtomicLong COORDINATOR_RETRIES_SUPPRESSED = new AtomicLong();
    private static final AtomicLong VALIDATED_TARGET_BYPASSES = new AtomicLong();
    private static final AtomicLong VALIDATED_TARGET_EXPIRATIONS = new AtomicLong();
    private static final AtomicLong VALIDATED_TARGET_OUT_OF_BOUNDS = new AtomicLong();
    private static final AtomicLong VALIDATED_RESERVATION_LOSSES = new AtomicLong();
    private static final AtomicLong VALIDATED_TARGET_RELEASES = new AtomicLong();
    private static final AtomicLong VALIDATED_TARGET_INVALIDATIONS = new AtomicLong();
    private static final AtomicLong VALIDATED_TARGET_REPLACEMENTS = new AtomicLong();
    private static final AtomicLong NEXT_SUMMARY_AT_MS = new AtomicLong(Long.MAX_VALUE);

    private static volatile boolean enabled;

    private NeedsResourceHotPathDiagnostics() {
    }

    /** Enables or disables aggregate collection with a fresh measurement window. */
    public static synchronized void setEnabled(boolean nextEnabled) {
        if (enabled == nextEnabled) {
            return;
        }
        if (nextEnabled) {
            clearCounters();
            enabled = true;
            NEXT_SUMMARY_AT_MS.set(System.currentTimeMillis() + SUMMARY_INTERVAL_MS);
            return;
        }
        logSnapshot("disabled");
        enabled = false;
        NEXT_SUMMARY_AT_MS.set(Long.MAX_VALUE);
    }

    public static void recordPreflightRequest() {
        if (enabled) {
            PREFLIGHT_REQUESTS.incrementAndGet();
            maybeLogSummary();
        }
    }

    public static void recordPreflightLeaseHit() {
        if (enabled) {
            PREFLIGHT_LEASE_HITS.incrementAndGet();
        }
    }

    public static void recordPreflightLeaseMiss() {
        if (enabled) {
            PREFLIGHT_LEASE_MISSES.incrementAndGet();
        }
    }

    public static void recordPreflightComputation() {
        if (enabled) {
            PREFLIGHT_COMPUTATIONS.incrementAndGet();
        }
    }

    public static void recordPreflightInvalidation() {
        if (enabled) {
            PREFLIGHT_INVALIDATIONS.incrementAndGet();
        }
    }

    public static void recordPreflightNoPathResult() {
        if (enabled) {
            PREFLIGHT_NO_PATH_RESULTS.incrementAndGet();
        }
    }

    public static void recordPreflightBudgetDeferral() {
        if (enabled) {
            PREFLIGHT_BUDGET_DEFERRALS.incrementAndGet();
        }
    }

    public static void recordCoordinatorLookup() {
        if (enabled) {
            COORDINATOR_LOOKUPS.incrementAndGet();
            maybeLogSummary();
        }
    }

    public static void recordCoordinatorRetrySuppressed() {
        if (enabled) {
            COORDINATOR_RETRIES_SUPPRESSED.incrementAndGet();
        }
    }

    public static void recordValidatedTargetBypass() {
        if (enabled) {
            VALIDATED_TARGET_BYPASSES.incrementAndGet();
        }
    }

    public static void recordValidatedTargetExpiration() {
        if (enabled) {
            VALIDATED_TARGET_EXPIRATIONS.incrementAndGet();
        }
    }

    public static void recordValidatedTargetOutOfBounds() {
        if (enabled) {
            VALIDATED_TARGET_OUT_OF_BOUNDS.incrementAndGet();
        }
    }

    public static void recordValidatedReservationLoss() {
        if (enabled) {
            VALIDATED_RESERVATION_LOSSES.incrementAndGet();
        }
    }

    public static void recordValidatedTargetRelease() {
        if (enabled) {
            VALIDATED_TARGET_RELEASES.incrementAndGet();
        }
    }

    public static void recordValidatedTargetInvalidation() {
        if (enabled) {
            VALIDATED_TARGET_INVALIDATIONS.incrementAndGet();
        }
    }

    public static void recordValidatedTargetReplacement() {
        if (enabled) {
            VALIDATED_TARGET_REPLACEMENTS.incrementAndGet();
        }
    }

    /** Returns the current low-cardinality measurement totals. */
    public static Snapshot snapshot() {
        return new Snapshot(
                PREFLIGHT_REQUESTS.get(),
                PREFLIGHT_LEASE_HITS.get(),
                PREFLIGHT_LEASE_MISSES.get(),
                PREFLIGHT_COMPUTATIONS.get(),
                PREFLIGHT_INVALIDATIONS.get(),
                PREFLIGHT_NO_PATH_RESULTS.get(),
                PREFLIGHT_BUDGET_DEFERRALS.get(),
                COORDINATOR_LOOKUPS.get(),
                COORDINATOR_RETRIES_SUPPRESSED.get(),
                VALIDATED_TARGET_BYPASSES.get(),
                VALIDATED_TARGET_EXPIRATIONS.get(),
                VALIDATED_TARGET_OUT_OF_BOUNDS.get(),
                VALIDATED_RESERVATION_LOSSES.get(),
                VALIDATED_TARGET_RELEASES.get(),
                VALIDATED_TARGET_INVALIDATIONS.get(),
                VALIDATED_TARGET_REPLACEMENTS.get()
        );
    }

    /** Resets and enables collection without requiring a live plugin fixture. */
    public static synchronized void setEnabledForTests(boolean nextEnabled) {
        clearCounters();
        enabled = nextEnabled;
        NEXT_SUMMARY_AT_MS.set(Long.MAX_VALUE);
    }

    /** Restores the disabled default after a focused test. */
    public static synchronized void resetForTests() {
        enabled = false;
        clearCounters();
        NEXT_SUMMARY_AT_MS.set(Long.MAX_VALUE);
    }

    private static void maybeLogSummary() {
        long nowMs = System.currentTimeMillis();
        long nextMs = NEXT_SUMMARY_AT_MS.get();
        if (nowMs < nextMs
                || !NEXT_SUMMARY_AT_MS.compareAndSet(nextMs, nowMs + SUMMARY_INTERVAL_MS)) {
            return;
        }
        logSnapshot("periodic");
    }

    private static void logSnapshot(String stage) {
        if (!LOGGER.isLoggable(Level.INFO)) {
            return;
        }
        Snapshot value = snapshot();
        LOGGER.log(Level.INFO, () -> String.format(
                Locale.ROOT,
                "Needs resource hot path: stage=%s preflightRequests=%d leaseHits=%d leaseMisses=%d computations=%d invalidations=%d noPathResults=%d budgetDeferrals=%d coordinatorLookups=%d coordinatorRetriesSuppressed=%d validatedTargetBypasses=%d validatedTargetExpirations=%d validatedTargetOutOfBounds=%d validatedReservationLosses=%d validatedTargetReleases=%d validatedTargetInvalidations=%d validatedTargetReplacements=%d",
                stage,
                value.preflightRequests(),
                value.preflightLeaseHits(),
                value.preflightLeaseMisses(),
                value.preflightComputations(),
                value.preflightInvalidations(),
                value.preflightNoPathResults(),
                value.preflightBudgetDeferrals(),
                value.coordinatorLookups(),
                value.coordinatorRetriesSuppressed(),
                value.validatedTargetBypasses(),
                value.validatedTargetExpirations(),
                value.validatedTargetOutOfBounds(),
                value.validatedReservationLosses(),
                value.validatedTargetReleases(),
                value.validatedTargetInvalidations(),
                value.validatedTargetReplacements()
        ));
    }

    private static void clearCounters() {
        PREFLIGHT_REQUESTS.set(0L);
        PREFLIGHT_LEASE_HITS.set(0L);
        PREFLIGHT_LEASE_MISSES.set(0L);
        PREFLIGHT_COMPUTATIONS.set(0L);
        PREFLIGHT_INVALIDATIONS.set(0L);
        PREFLIGHT_NO_PATH_RESULTS.set(0L);
        PREFLIGHT_BUDGET_DEFERRALS.set(0L);
        COORDINATOR_LOOKUPS.set(0L);
        COORDINATOR_RETRIES_SUPPRESSED.set(0L);
        VALIDATED_TARGET_BYPASSES.set(0L);
        VALIDATED_TARGET_EXPIRATIONS.set(0L);
        VALIDATED_TARGET_OUT_OF_BOUNDS.set(0L);
        VALIDATED_RESERVATION_LOSSES.set(0L);
        VALIDATED_TARGET_RELEASES.set(0L);
        VALIDATED_TARGET_INVALIDATIONS.set(0L);
        VALIDATED_TARGET_REPLACEMENTS.set(0L);
    }

    /** Immutable aggregate values for logging and diagnostics tests. */
    public record Snapshot(long preflightRequests,
                           long preflightLeaseHits,
                           long preflightLeaseMisses,
                           long preflightComputations,
                           long preflightInvalidations,
                           long preflightNoPathResults,
                           long preflightBudgetDeferrals,
                           long coordinatorLookups,
                           long coordinatorRetriesSuppressed,
                           long validatedTargetBypasses,
                           long validatedTargetExpirations,
                           long validatedTargetOutOfBounds,
                           long validatedReservationLosses,
                           long validatedTargetReleases,
                           long validatedTargetInvalidations,
                           long validatedTargetReplacements) {
    }
}
