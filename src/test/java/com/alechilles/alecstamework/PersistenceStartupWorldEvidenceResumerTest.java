package com.alechilles.alecstamework;

import com.alechilles.alecstamework.persistence.control.PersistenceReadinessLevel;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupReport;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for startup reconciliation deferred by live NPC identity churn. */
class PersistenceStartupWorldEvidenceResumerTest {

    @Test
    void retriesDeferredWorldReconciliationUntilMutationReadinessPublishes() {
        // Regression: the 2026-07-31 tester import stopped after one
        // startup_evidence_deferred result while NPCs were still loading.
        ArrayDeque<PersistenceStartupReport> reports = new ArrayDeque<>(List.of(
                deferredWorldReconciliation(),
                deferredWorldEvidence(),
                mutationReady()
        ));
        AtomicInteger attempts = new AtomicInteger();
        ManualRetryScheduler retries = new ManualRetryScheduler();
        List<PersistenceStartupReport> terminalReports = new ArrayList<>();
        PersistenceStartupWorldEvidenceResumer resumer =
                new PersistenceStartupWorldEvidenceResumer(
                        () -> {
                            attempts.incrementAndGet();
                            return CompletableFuture.completedFuture(reports.removeFirst());
                        },
                        retries,
                        (report, failure) -> {
                            assertNull(failure);
                            terminalReports.add(report);
                        },
                        4,
                        100L
                );

        resumer.resume();

        assertEquals(1, attempts.get());
        assertEquals(1, retries.pendingCount());
        assertTrue(terminalReports.isEmpty());

        retries.runNext();
        assertEquals(2, attempts.get());
        assertEquals(1, retries.pendingCount());
        assertTrue(terminalReports.isEmpty());

        retries.runNext();
        assertEquals(3, attempts.get());
        assertEquals(List.of(mutationReady()), terminalReports);
        assertEquals(0, retries.pendingCount());
    }

    @Test
    void keepsRetryingAtCappedBackoffUntilWorldEvidenceStabilizes() {
        ArrayDeque<PersistenceStartupReport> reports = new ArrayDeque<>(List.of(
                deferredWorldReconciliation(),
                deferredWorldReconciliation(),
                deferredWorldReconciliation(),
                deferredWorldReconciliation(),
                deferredWorldReconciliation(),
                mutationReady()
        ));
        AtomicInteger attempts = new AtomicInteger();
        ManualRetryScheduler retries = new ManualRetryScheduler();
        List<PersistenceStartupReport> terminalReports = new ArrayList<>();
        PersistenceStartupWorldEvidenceResumer resumer =
                new PersistenceStartupWorldEvidenceResumer(
                        () -> {
                            attempts.incrementAndGet();
                            return CompletableFuture.completedFuture(reports.removeFirst());
                        },
                        retries,
                        (report, failure) -> {
                            assertNull(failure);
                            terminalReports.add(report);
                        },
                        2,
                        100L
                );

        resumer.resume();
        while (retries.pendingCount() > 0) {
            retries.runNext();
        }

        assertEquals(6, attempts.get());
        assertEquals(List.of(100L, 200L, 400L, 400L, 400L), retries.delays());
        assertEquals(List.of(mutationReady()), terminalReports);
    }

    private static PersistenceStartupReport deferredWorldReconciliation() {
        EnumSet<PersistenceStartupNode> completed = EnumSet.allOf(
                PersistenceStartupNode.class
        );
        completed.remove(PersistenceStartupNode.RECONCILE_WORLD);
        completed.remove(PersistenceStartupNode.PUBLISH_READ_READINESS);
        completed.remove(PersistenceStartupNode.PUBLISH_MUTATION_READINESS);
        return new PersistenceStartupReport(
                Set.copyOf(completed),
                null,
                PersistenceStartupNode.RECONCILE_WORLD,
                null,
                "startup_evidence_deferred",
                PersistenceReadinessLevel.WORLD_EVIDENCE_PENDING
        );
    }

    private static PersistenceStartupReport deferredWorldEvidence() {
        EnumSet<PersistenceStartupNode> completed = EnumSet.allOf(
                PersistenceStartupNode.class
        );
        completed.remove(PersistenceStartupNode.WAIT_WORLD_EVIDENCE);
        completed.remove(PersistenceStartupNode.RECONCILE_WORLD);
        completed.remove(PersistenceStartupNode.PUBLISH_READ_READINESS);
        completed.remove(PersistenceStartupNode.PUBLISH_MUTATION_READINESS);
        return new PersistenceStartupReport(
                Set.copyOf(completed),
                null,
                PersistenceStartupNode.WAIT_WORLD_EVIDENCE,
                null,
                "startup_evidence_deferred",
                PersistenceReadinessLevel.WORLD_EVIDENCE_PENDING
        );
    }

    private static PersistenceStartupReport mutationReady() {
        return new PersistenceStartupReport(
                Set.of(PersistenceStartupNode.values()),
                null,
                null,
                null,
                null,
                PersistenceReadinessLevel.MUTATION_READY
        );
    }

    private static final class ManualRetryScheduler
            implements PersistenceStartupWorldEvidenceResumer.RetryScheduler {
        private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        private final List<Long> delays = new ArrayList<>();

        @Override
        public void schedule(long delayMillis, Runnable retry) {
            delays.add(delayMillis);
            pending.addLast(retry);
        }

        int pendingCount() {
            return pending.size();
        }

        void runNext() {
            pending.removeFirst().run();
        }

        List<Long> delays() {
            return List.copyOf(delays);
        }
    }
}
