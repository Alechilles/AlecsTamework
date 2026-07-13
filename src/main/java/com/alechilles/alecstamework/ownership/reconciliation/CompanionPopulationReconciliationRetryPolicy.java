package com.alechilles.alecstamework.ownership.reconciliation;

import javax.annotation.Nullable;

/** Classifies transient startup evidence races and bounds their automatic retry cadence. */
final class CompanionPopulationReconciliationRetryPolicy {
    private static final long INITIAL_DELAY_MS = 25L;
    private static final long MAX_DELAY_MS = 1_000L;

    private CompanionPopulationReconciliationRetryPolicy() {
    }

    static boolean shouldRetry(@Nullable String reason) {
        return reason != null
                && (reason.startsWith("reconciliation-loaded-identity-incomplete")
                || reason.startsWith("reconciliation-loaded-identity-mutated-")
                || reason.startsWith("reconciliation-live-evidence-mutated-"));
    }

    static long delayMs(int priorRetries) {
        int shift = Math.max(0, Math.min(priorRetries, 6));
        return Math.min(MAX_DELAY_MS, INITIAL_DELAY_MS << shift);
    }
}
