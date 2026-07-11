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
            return failScheduledJob(jobId);
        }
        if (!parents.valid()) {
            rollbackSafely(job);
            BreedingBirthJobRegistry.TerminalResult cancelled = services.jobRegistry().cancel(jobId);
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
        rollbackSafely(job);
        BreedingBirthJobRegistry.TerminalResult failed = services.jobRegistry().fail(jobId);
        return result(ExecutionStatus.FAILED, failed.job().orElse(job), 0);
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
            rollbackSafely(job);
            BreedingBirthJobRegistry.TerminalResult failed =
                    services.jobRegistry().fail(parents.storeScope(), job.jobId());
            return result(ExecutionStatus.FAILED, failed.job().orElse(job), 0);
        }
    }

    private ExecutionResult spawn(BreedingBirthJob job, ParentResolution<C> parents) {
        BreedingPopulationAdmissionService.AdmissionRequest request;
        try {
            request = runtime.buildSpawnAdmissionRequest(job, parents.context());
        } catch (RuntimeException exception) {
            return cancel(job, ExecutionStatus.CAPACITY_REJECTED);
        }
        if (request == null) {
            return cancel(job, ExecutionStatus.CAPACITY_REJECTED);
        }
        BreedingPopulationAdmissionService.AdmissionResult rechecked;
        try {
            rechecked = services.populationAdmissionService().recheckAtSpawn(
                    request,
                    job.activeAdmission()
            );
        } catch (RuntimeException exception) {
            return cancel(job, ExecutionStatus.CAPACITY_REJECTED);
        }
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
            return cancel(recheckedJob, ExecutionStatus.CAPACITY_REJECTED);
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
        int spawned = spawnChildren(claimed, parents);
        return finishSpawn(claimed, parents, spawned);
    }

    private int spawnChildren(BreedingBirthJob job, ParentResolution<C> parents) {
        List<PlannedChild> children = job.admittedChildren();
        int spawned = 0;
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
            }
        }
        return spawned;
    }

    private ExecutionResult finishSpawn(BreedingBirthJob job,
                                        ParentResolution<C> parents,
                                        int spawned) {
        if (spawned > 0 || job.plan().isNaturallyEmpty()) {
            BreedingBirthJobRegistry.TerminalResult completed = services.jobRegistry().complete(
                    parents.storeScope(),
                    job.jobId()
            );
            BreedingBirthJob completedJob = completed.job().orElse(job);
            try {
                runtime.onCompleted(completedJob, spawned, parents.context());
            } catch (RuntimeException ignored) {
                // Completion is already authoritative; presentation follow-up must not reopen it.
            }
            return result(ExecutionStatus.COMPLETED, completedJob, spawned);
        }
        BreedingBirthJobRegistry.TerminalResult failed =
                services.jobRegistry().fail(parents.storeScope(), job.jobId());
        rollbackSafely(job);
        return result(ExecutionStatus.FAILED, failed.job().orElse(job), 0);
    }

    private ExecutionResult cancel(BreedingBirthJob job, ExecutionStatus status) {
        rollbackSafely(job);
        BreedingBirthJobRegistry.TerminalResult cancelled = services.jobRegistry().cancel(job.jobId());
        return result(status, cancelled.job().orElse(job), 0);
    }

    private void rollbackSafely(BreedingBirthJob job) {
        try {
            runtime.rollbackProvisionalCooldown(job);
        } catch (RuntimeException ignored) {
            // Fingerprint-guarded rollback is best effort; terminal registry state still wins.
        }
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
