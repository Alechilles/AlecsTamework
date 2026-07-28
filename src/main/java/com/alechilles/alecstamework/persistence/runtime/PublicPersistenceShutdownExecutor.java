package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.persistence.adapter.sqlite
        .SqliteKernelShutdownReport;
import com.alechilles.alecstamework.persistence.adapter.sqlite
        .SqlitePersistenceKernel;
import com.alechilles.alecstamework.persistence.control.PersistenceEngineLease;
import com.alechilles.alecstamework.persistence.control
        .PersistenceStartupCoordinator;
import java.time.Duration;

/**
 * Executes public shutdown phases in dependency order.
 *
 * <p>A quiesce failure cannot skip independent storage teardown. Nonterminal
 * accepted workflows intentionally defer kernel and lease teardown because
 * their remaining publication steps may still require both storage lanes.</p>
 */
final class PublicPersistenceShutdownExecutor {
    private PublicPersistenceShutdownExecutor() {
    }

    static Outcome execute(
            PersistenceStartupCoordinator startup,
            PublicPersistenceWorldReconciliation world,
            boolean worldQuiesced,
            PublicPersistenceWorkflowTracker workflows,
            SqlitePersistenceKernel kernel,
            PersistenceEngineLease lease,
            Duration timeout
    ) {
        ShutdownEvidence evidence = new ShutdownEvidence();
        long deadline = System.nanoTime() + timeout.toNanos();
        evidence.worldQuiesced = worldQuiesced;
        evidence.status = quiesce(startup, world, worldQuiesced, evidence);
        boolean workflowsTerminal = drainWorkflows(
                workflows,
                remaining(deadline),
                evidence
        );
        if (workflowsTerminal) {
            closeKernel(kernel, remaining(deadline), evidence);
        }
        closeLease(lease, kernel, evidence);
        return evidence.outcome();
    }

    private static PublicPersistenceShutdownReport.Status quiesce(
            PersistenceStartupCoordinator startup,
            PublicPersistenceWorldReconciliation world,
            boolean alreadyQuiesced,
            ShutdownEvidence evidence
    ) {
        boolean failed = false;
        try {
            startup.close();
        } catch (Throwable failure) {
            failed = true;
            evidence.addFailure(failure);
        }
        if (!alreadyQuiesced && world != null) {
            try {
                world.quiesce();
                evidence.worldQuiesced = true;
            } catch (Throwable failure) {
                failed = true;
                evidence.addFailure(failure);
            }
        }
        return failed
                ? PublicPersistenceShutdownReport.Status.QUIESCE_FAILED
                : null;
    }

    private static boolean drainWorkflows(
            PublicPersistenceWorkflowTracker workflows,
            Duration timeout,
            ShutdownEvidence evidence
    ) {
        try {
            PublicPersistenceWorkflowTracker.DrainResult drained =
                    workflows.drain(timeout);
            evidence.outstandingWorkflows = drained.outstanding();
            if (!drained.drained()) {
                evidence.status = PublicPersistenceShutdownReport.Status
                        .FEATURE_DRAIN_TIMED_OUT;
            }
            return drained.drained();
        } catch (Throwable failure) {
            evidence.outstandingWorkflows = workflows.outstanding();
            evidence.status = PublicPersistenceShutdownReport.Status
                    .FEATURE_DRAIN_FAILED;
            evidence.addFailure(failure);
            return false;
        }
    }

    private static Duration remaining(long deadline) {
        return Duration.ofNanos(
                Math.max(0, deadline - System.nanoTime())
        );
    }

    private static void closeKernel(
            SqlitePersistenceKernel kernel,
            Duration timeout,
            ShutdownEvidence evidence
    ) {
        if (kernel == null) {
            evidence.kernelTerminal = true;
            return;
        }
        try {
            evidence.kernel = kernel.shutdown(timeout);
            evidence.kernelTerminal =
                    kernel.state() == SqlitePersistenceKernel.State.CLOSED;
            if (!evidence.kernelTerminal) {
                evidence.status = PublicPersistenceShutdownReport.Status
                        .KERNEL_DRAIN_TIMED_OUT;
            }
        } catch (Throwable failure) {
            evidence.kernelTerminal =
                    kernel.state() == SqlitePersistenceKernel.State.CLOSED;
            evidence.status = PublicPersistenceShutdownReport.Status
                    .KERNEL_CLOSE_FAILED;
            evidence.addFailure(failure);
        }
    }

    private static void closeLease(
            PersistenceEngineLease lease,
            SqlitePersistenceKernel kernel,
            ShutdownEvidence evidence
    ) {
        if (lease == null) {
            evidence.leaseTerminal = true;
            return;
        }
        if (!evidence.kernelTerminal) {
            return;
        }
        try {
            if (clean(evidence, kernel)) {
                lease.close();
            } else {
                lease.closeUnclean();
            }
            evidence.leaseTerminal = true;
        } catch (Throwable failure) {
            evidence.leaseTerminal = true;
            evidence.status = PublicPersistenceShutdownReport.Status
                    .CONTROL_CLOSE_FAILED;
            evidence.addFailure(failure);
        }
    }

    private static boolean clean(
            ShutdownEvidence evidence,
            SqlitePersistenceKernel kernel
    ) {
        return evidence.status == null
                && (kernel == null || evidence.kernel.clean());
    }

    record Outcome(
            PublicPersistenceShutdownReport report,
            boolean worldQuiesced,
            SqliteKernelShutdownReport kernel,
            boolean terminal
    ) {
    }

    private static final class ShutdownEvidence {
        private PublicPersistenceShutdownReport.Status status;
        private int outstandingWorkflows;
        private SqliteKernelShutdownReport kernel;
        private Throwable failure;
        private boolean worldQuiesced;
        private boolean kernelTerminal;
        private boolean leaseTerminal;

        private void addFailure(Throwable next) {
            if (failure == null) {
                failure = next;
            } else {
                failure.addSuppressed(next);
            }
        }

        private Outcome outcome() {
            boolean terminal = kernelTerminal && leaseTerminal;
            PublicPersistenceShutdownReport.Status reported =
                    finalStatus(terminal);
            return new Outcome(
                    new PublicPersistenceShutdownReport(
                            reported,
                            outstandingWorkflows,
                            kernel,
                            failure,
                            terminal
                    ),
                    worldQuiesced,
                    kernel,
                    terminal
            );
        }

        private PublicPersistenceShutdownReport.Status finalStatus(
                boolean terminal
        ) {
            if (status != null) {
                return status;
            }
            if (!terminal) {
                return PublicPersistenceShutdownReport.Status
                        .KERNEL_CLOSE_FAILED;
            }
            return kernel == null || kernel.clean()
                    ? PublicPersistenceShutdownReport.Status.COMPLETE
                    : PublicPersistenceShutdownReport.Status.COMPLETE_UNCLEAN;
        }
    }
}
