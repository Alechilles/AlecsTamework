package com.alechilles.alecstamework;

import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupReport;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;

/**
 * Resumes replacement startup until transient world-evidence churn settles.
 *
 * <p>Only world-evidence deferrals are retried. Failures and other incomplete
 * startup states remain terminal for the current resume cycle.</p>
 */
final class PersistenceStartupWorldEvidenceResumer implements AutoCloseable {
    private static final int MAX_BACKOFF_SHIFTS = 4;
    private static final long INITIAL_RETRY_DELAY_MILLIS = 250L;

    private final Object stateLock = new Object();
    private final StartupAttempt startup;
    private final RetryScheduler retries;
    private final TerminalSink terminalSink;
    private final int maxBackoffShifts;
    private final long initialRetryDelayMillis;
    private int attempts;
    private boolean active;
    private boolean ready;
    private boolean closed;

    PersistenceStartupWorldEvidenceResumer(
            @Nonnull PersistenceBootstrap bootstrap,
            @Nonnull TerminalSink terminalSink
    ) {
        this(
                Objects.requireNonNull(bootstrap, "bootstrap")::start,
                delayedRetryScheduler(),
                terminalSink,
                MAX_BACKOFF_SHIFTS,
                INITIAL_RETRY_DELAY_MILLIS
        );
    }

    PersistenceStartupWorldEvidenceResumer(
            @Nonnull StartupAttempt startup,
            @Nonnull RetryScheduler retries,
            @Nonnull TerminalSink terminalSink,
            int maxBackoffShifts,
            long initialRetryDelayMillis
    ) {
        this.startup = Objects.requireNonNull(startup, "startup");
        this.retries = Objects.requireNonNull(retries, "retries");
        this.terminalSink = Objects.requireNonNull(terminalSink, "terminalSink");
        if (maxBackoffShifts < 0
                || maxBackoffShifts > 30
                || initialRetryDelayMillis < 0L
                || initialRetryDelayMillis
                > (Long.MAX_VALUE >> maxBackoffShifts)) {
            throw new IllegalArgumentException("Valid retry bounds are required");
        }
        this.maxBackoffShifts = maxBackoffShifts;
        this.initialRetryDelayMillis = initialRetryDelayMillis;
    }

    /** Starts one deduplicated resume cycle. */
    void resume() {
        synchronized (stateLock) {
            if (closed || ready || active) {
                return;
            }
            active = true;
            attempts = 0;
        }
        runAttempt();
    }

    private void runAttempt() {
        CompletionStage<PersistenceStartupReport> stage;
        synchronized (stateLock) {
            if (closed || !active) {
                return;
            }
            attempts++;
        }
        try {
            stage = Objects.requireNonNull(
                    startup.start(),
                    "Persistence startup attempt"
            );
        } catch (Throwable failure) {
            finish(null, failure);
            return;
        }
        stage.whenComplete(this::finish);
    }

    private void finish(PersistenceStartupReport report, Throwable failure) {
        boolean retry;
        long delayMillis = 0L;
        synchronized (stateLock) {
            if (closed || !active) {
                return;
            }
            retry = failure == null
                    && isWorldEvidenceDeferred(report);
            if (retry) {
                int backoffShift = Math.min(
                        Math.max(0, attempts - 1),
                        maxBackoffShifts
                );
                delayMillis = initialRetryDelayMillis << backoffShift;
            }
            if (!retry) {
                active = false;
                ready = failure == null && report != null && report.complete();
            }
        }
        if (retry) {
            try {
                retries.schedule(delayMillis, this::runAttempt);
            } catch (Throwable schedulingFailure) {
                finish(null, schedulingFailure);
            }
            return;
        }
        terminalSink.accept(report, failure);
    }

    private boolean isWorldEvidenceDeferred(PersistenceStartupReport report) {
        return report != null
                && (report.deferredNode()
                == PersistenceStartupNode.WAIT_WORLD_EVIDENCE
                || report.deferredNode()
                == PersistenceStartupNode.RECONCILE_WORLD);
    }

    @Override
    public void close() {
        synchronized (stateLock) {
            closed = true;
            active = false;
        }
    }

    private static RetryScheduler delayedRetryScheduler() {
        return (delayMillis, retry) -> CompletableFuture.delayedExecutor(
                delayMillis,
                TimeUnit.MILLISECONDS
        ).execute(retry);
    }

    @FunctionalInterface
    interface StartupAttempt {
        CompletionStage<PersistenceStartupReport> start();
    }

    @FunctionalInterface
    interface RetryScheduler {
        void schedule(long delayMillis, Runnable retry);
    }

    @FunctionalInterface
    interface TerminalSink {
        void accept(PersistenceStartupReport report, Throwable failure);
    }
}
