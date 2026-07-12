package com.alechilles.alecstamework.npc.breeding;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Executes delayed breeding stages by job ID after resolving fresh world and parent state.
 *
 * <p>The same callback handles both delayed stages by inspecting the guarded job state. Only the
 * registry can claim {@code SPAWNING}; duplicate callbacks therefore cannot spawn a second litter.
 */
public final class BreedingJobExecutionService<C> {
    private final TameworkBreedingServices services;
    private final Runtime<C> runtime;
    private final BreedingJobScheduler scheduler;
    private final long offspringDelayMs;

    public BreedingJobExecutionService(@Nonnull TameworkBreedingServices services,
                                       @Nonnull Runtime<C> runtime,
                                       @Nonnull BreedingJobScheduler scheduler,
                                       long offspringDelayMs) {
        this.services = Objects.requireNonNull(services, "services");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.offspringDelayMs = Math.max(0L, offspringDelayMs);
    }

    /** Resolves and executes the current stage for one delayed job callback. */
    @Nonnull
    public ExecutionResult execute(@Nonnull UUID jobId) {
        Objects.requireNonNull(jobId, "jobId");
        Optional<BreedingBirthJobRegistry.LocatedJob> located = services.jobRegistry().locate(jobId);
        if (located.isEmpty()) {
            return result(ExecutionStatus.NOT_FOUND, null, 0);
        }
        BreedingBirthJob job = located.orElseThrow().job();
        if (job.state().isTerminal()) {
            return result(ExecutionStatus.TERMINAL, job, 0);
        }
        ParentResolution<C> parents;
        try {
            parents = Objects.requireNonNull(
                    runtime.resolveParents(job),
                    "runtime parent resolution"
            );
        } catch (RuntimeException exception) {
            return failJob(job, "parent-resolution-error:" + exception.getClass().getSimpleName());
        }
        if (!parents.valid()) {
            BreedingJobDiagnosticSnapshot.RollbackStatus rollback = rollbackSafely(job);
            BreedingBirthJobRegistry.TerminalResult cancelled = services.jobRegistry().cancel(jobId);
            recordOutcome(
                    job,
                    BreedingJobDiagnosticSnapshot.Outcome.PARENTS_INVALID,
                    0,
                    normalizeReason(parents.reason(), "parents-invalid"),
                    rollback,
                    null
            );
            return result(ExecutionStatus.PARENTS_INVALID, cancelled.job().orElse(job), 0);
        }
        return switch (job.state()) {
            case APPROACHING -> showHearts(job, parents);
            case HEARTS_SHOWN -> spawn(job, parents);
            case SPAWNING -> result(ExecutionStatus.ALREADY_CLAIMED, job, 0);
            case RESERVED -> result(ExecutionStatus.NOT_READY, job, 0);
            case COMPLETED, CANCELLED, FAILED -> result(ExecutionStatus.TERMINAL, job, 0);
        };
    }

    /**
     * Terminates a registered job after an asynchronous scheduling or dispatch failure.
     *
     * <p>The scheduler invokes this callback on the current world thread whenever one is still
     * available. Rollback remains fingerprint guarded, so a newer breeding state is never erased.
     */
    @Nonnull
    public ExecutionResult failScheduledJob(@Nonnull UUID jobId) {
        Objects.requireNonNull(jobId, "jobId");
        Optional<BreedingBirthJobRegistry.LocatedJob> located = services.jobRegistry().locate(jobId);
        if (located.isEmpty()) {
            return result(ExecutionStatus.NOT_FOUND, null, 0);
        }
        BreedingBirthJob job = located.orElseThrow().job();
        if (job.state().isTerminal()) {
            return result(ExecutionStatus.TERMINAL, job, 0);
        }
        return failJob(job, "scheduled-dispatch-failed");
    }

    private ExecutionResult showHearts(BreedingBirthJob job, ParentResolution<C> parents) {
        BreedingBirthJobRegistry.TransitionResult transition = services.jobRegistry().advance(
                parents.storeScope(),
                job.jobId(),
                BreedingBirthJobState.APPROACHING,
                BreedingBirthJobState.HEARTS_SHOWN
        );
        if (transition.status() != BreedingBirthJobRegistry.TransitionStatus.APPLIED) {
            return result(ExecutionStatus.NOT_READY, transition.job().orElse(job), 0);
        }
        try {
            runtime.showHearts(transition.job().orElseThrow(), parents.context());
            scheduler.schedule(job.jobId(), offspringDelayMs);
            return result(ExecutionStatus.HEARTS_SHOWN, transition.job().orElseThrow(), 0);
        } catch (RuntimeException exception) {
            BreedingJobDiagnosticSnapshot.RollbackStatus rollback = rollbackSafely(job);
            BreedingBirthJobRegistry.TerminalResult failed =
                    services.jobRegistry().fail(parents.storeScope(), job.jobId());
            recordOutcome(
                    job,
                    BreedingJobDiagnosticSnapshot.Outcome.FAILED,
                    0,
                    "heart-stage-error:" + exception.getClass().getSimpleName(),
                    rollback,
                    null
            );
            return result(ExecutionStatus.FAILED, failed.job().orElse(job), 0);
        }
    }

    private ExecutionResult spawn(BreedingBirthJob job, ParentResolution<C> parents) {
        BreedingPopulationAdmissionService.AdmissionRequest request;
        try {
            request = runtime.buildSpawnAdmissionRequest(job, parents.context());
        } catch (RuntimeException exception) {
            return cancelForCapacity(
                    job,
                    "spawn-capacity-request-error:" + exception.getClass().getSimpleName()
            );
        }
        if (request == null) {
            return cancelForCapacity(job, "spawn-capacity-request-missing");
        }
        BreedingPopulationAdmissionService.AdmissionResult rechecked;
        try {
            rechecked = services.populationAdmissionService().recheckAtSpawn(
                    request,
                    job.activeAdmission()
            );
        } catch (RuntimeException exception) {
            return cancelForCapacity(
                    job,
                    "spawn-capacity-recheck-error:" + exception.getClass().getSimpleName()
            );
        }
        services.jobDiagnostics().recordSpawnRecheck(job.jobId(), request, rechecked);
        BreedingBirthJobRegistry.AdmissionUpdateResult shrunk = services.jobRegistry().shrinkAdmission(
                parents.storeScope(),
                job.jobId(),
                rechecked.admittedChildren()
        );
        if (shrunk.status() != BreedingBirthJobRegistry.AdmissionUpdateStatus.APPLIED
                && shrunk.status() != BreedingBirthJobRegistry.AdmissionUpdateStatus.UNCHANGED) {
            return result(ExecutionStatus.NOT_READY, shrunk.job().orElse(job), 0);
        }
        BreedingBirthJob recheckedJob = shrunk.job().orElseThrow();
        if (!recheckedJob.plan().isNaturallyEmpty() && recheckedJob.admittedChildren().isEmpty()) {
            return cancelForCapacity(recheckedJob, "zero-spawn-headroom");
        }
        BreedingBirthJobRegistry.SpawnClaimResult claim = services.jobRegistry().claimSpawn(
                parents.storeScope(),
                job.jobId()
        );
        if (claim.status() != BreedingBirthJobRegistry.SpawnClaimStatus.CLAIMED) {
            ExecutionStatus status = claim.status() == BreedingBirthJobRegistry.SpawnClaimStatus.ALREADY_CLAIMED
                    ? ExecutionStatus.ALREADY_CLAIMED
                    : ExecutionStatus.NOT_READY;
            return result(status, claim.job().orElse(recheckedJob), 0);
        }
        BreedingBirthJob claimed = claim.job().orElseThrow();
        SpawnReport spawnReport = spawnChildren(claimed, parents);
        return finishSpawn(claimed, parents, spawnReport);
    }

    private SpawnReport spawnChildren(BreedingBirthJob job, ParentResolution<C> parents) {
        List<PlannedChild> children = job.admittedChildren();
        int spawned = 0;
        int failed = 0;
        for (int index = 0; index < children.size(); index++) {
            PlannedChild child = children.get(index);
            boolean succeeded = false;
            try {
                succeeded = runtime.spawnChild(job, child, index, parents.context());
            } catch (RuntimeException ignored) {
                succeeded = false;
            } finally {
                services.jobRegistry().releaseChildReservation(
                        parents.storeScope(),
                        job.jobId(),
                        child
                );
            }
            if (succeeded) {
                spawned++;
            } else {
                failed++;
            }
        }
        return new SpawnReport(children.size(), spawned, failed);
    }

    private ExecutionResult finishSpawn(BreedingBirthJob job,
                                        ParentResolution<C> parents,
                                        SpawnReport spawnReport) {
        if (spawnReport.spawned() > 0 || job.plan().isNaturallyEmpty()) {
            BreedingBirthJobRegistry.TerminalResult completed = services.jobRegistry().complete(
                    parents.storeScope(),
                    job.jobId()
            );
            BreedingBirthJob completedJob = completed.job().orElse(job);
            String reason = spawnReport.failed() > 0
                    ? "child-spawn-failures=" + spawnReport.failed()
                    : null;
            recordOutcome(
                    job,
                    BreedingJobDiagnosticSnapshot.Outcome.COMPLETED,
                    spawnReport.spawned(),
                    reason,
                    BreedingJobDiagnosticSnapshot.RollbackStatus.NOT_ATTEMPTED,
                    null
            );
            try {
                runtime.onCompleted(completedJob, spawnReport.spawned(), parents.context());
            } catch (RuntimeException exception) {
                // Completion is already authoritative; presentation follow-up must not reopen it.
                recordOutcome(
                        job,
                        BreedingJobDiagnosticSnapshot.Outcome.COMPLETED,
                        spawnReport.spawned(),
                        appendReason(
                                reason,
                                "post-completion-follow-up-error:"
                                        + exception.getClass().getSimpleName()
                        ),
                        BreedingJobDiagnosticSnapshot.RollbackStatus.NOT_ATTEMPTED,
                        null
                );
            }
            return result(ExecutionStatus.COMPLETED, completedJob, spawnReport.spawned());
        }
        BreedingBirthJobRegistry.TerminalResult failed =
                services.jobRegistry().fail(parents.storeScope(), job.jobId());
        BreedingJobDiagnosticSnapshot.RollbackStatus rollback = rollbackSafely(job);
        recordOutcome(
                job,
                BreedingJobDiagnosticSnapshot.Outcome.FAILED,
                0,
                "all-child-spawns-failed=" + spawnReport.failed(),
                rollback,
                null
        );
        return result(ExecutionStatus.FAILED, failed.job().orElse(job), 0);
    }

    private ExecutionResult cancelForCapacity(BreedingBirthJob job, String reason) {
        BreedingJobDiagnosticSnapshot.RollbackStatus rollback = rollbackSafely(job);
        BreedingBirthJobRegistry.TerminalResult cancelled = services.jobRegistry().cancel(job.jobId());
        recordOutcome(
                job,
                BreedingJobDiagnosticSnapshot.Outcome.CAPACITY_REJECTED,
                0,
                reason,
                rollback,
                null
        );
        return result(ExecutionStatus.CAPACITY_REJECTED, cancelled.job().orElse(job), 0);
    }

    private BreedingJobDiagnosticSnapshot.RollbackStatus rollbackSafely(BreedingBirthJob job) {
        try {
            runtime.rollbackProvisionalCooldown(job);
            return BreedingJobDiagnosticSnapshot.RollbackStatus.ATTEMPTED;
        } catch (RuntimeException ignored) {
            // Fingerprint-guarded rollback is best effort; terminal registry state still wins.
            return BreedingJobDiagnosticSnapshot.RollbackStatus.FAILED;
        }
    }

    private ExecutionResult failJob(BreedingBirthJob job, String reason) {
        BreedingJobDiagnosticSnapshot.RollbackStatus rollback = rollbackSafely(job);
        BreedingBirthJobRegistry.TerminalResult failed = services.jobRegistry().fail(job.jobId());
        recordOutcome(
                job,
                BreedingJobDiagnosticSnapshot.Outcome.FAILED,
                0,
                reason,
                rollback,
                null
        );
        return result(ExecutionStatus.FAILED, failed.job().orElse(job), 0);
    }

    private void recordOutcome(BreedingBirthJob job,
                               BreedingJobDiagnosticSnapshot.Outcome outcome,
                               int spawnedChildren,
                               String reason,
                               BreedingJobDiagnosticSnapshot.RollbackStatus rollbackStatus,
                               String rollbackDetail) {
        services.jobDiagnostics().recordOutcome(
                job.jobId(),
                outcome,
                spawnedChildren,
                reason,
                rollbackStatus,
                rollbackDetail
        );
    }

    private static String normalizeReason(String reason, String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason.trim();
    }

    private static String appendReason(String current, String addition) {
        return current == null || current.isBlank() ? addition : current + ";" + addition;
    }

    private static ExecutionResult result(ExecutionStatus status,
                                          BreedingBirthJob job,
                                          int spawnedChildren) {
        return new ExecutionResult(status, Optional.ofNullable(job), spawnedChildren);
    }

    public enum ExecutionStatus {
        HEARTS_SHOWN,
        COMPLETED,
        FAILED,
        CAPACITY_REJECTED,
        PARENTS_INVALID,
        ALREADY_CLAIMED,
        NOT_READY,
        NOT_FOUND,
        TERMINAL
    }

    public record ExecutionResult(@Nonnull ExecutionStatus status,
                                  @Nonnull Optional<BreedingBirthJob> job,
                                  int spawnedChildren) {
        public ExecutionResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(job, "job");
            if (spawnedChildren < 0) {
                throw new IllegalArgumentException("spawnedChildren must be nonnegative");
            }
        }
    }

    private record SpawnReport(int attempted, int spawned, int failed) {
        private SpawnReport {
            if (attempted < 0 || spawned < 0 || failed < 0 || attempted != spawned + failed) {
                throw new IllegalArgumentException("Spawn report counts must be exact");
            }
        }
    }

    public record ParentResolution<C>(boolean valid,
                                      Object storeScope,
                                      C context,
                                      String reason) {
        public ParentResolution {
            if (valid) {
                Objects.requireNonNull(storeScope, "storeScope");
                Objects.requireNonNull(context, "context");
            }
        }

        @Nonnull
        public static <C> ParentResolution<C> valid(@Nonnull Object storeScope, @Nonnull C context) {
            return new ParentResolution<>(true, storeScope, context, null);
        }

        @Nonnull
        public static <C> ParentResolution<C> invalid(String reason) {
            return new ParentResolution<>(false, null, null, reason);
        }
    }

    /** Game-specific fresh-state adapter invoked only from the current delayed execution. */
    public interface Runtime<C> {
        @Nonnull
        ParentResolution<C> resolveParents(@Nonnull BreedingBirthJob job);

        void showHearts(@Nonnull BreedingBirthJob job, @Nonnull C context);

        BreedingPopulationAdmissionService.AdmissionRequest buildSpawnAdmissionRequest(
                @Nonnull BreedingBirthJob job,
                @Nonnull C context);

        boolean spawnChild(@Nonnull BreedingBirthJob job,
                           @Nonnull PlannedChild child,
                           int childIndex,
                           @Nonnull C context);

        void onCompleted(@Nonnull BreedingBirthJob job, int spawnedChildren, @Nonnull C context);

        void rollbackProvisionalCooldown(@Nonnull BreedingBirthJob job);
    }
}
