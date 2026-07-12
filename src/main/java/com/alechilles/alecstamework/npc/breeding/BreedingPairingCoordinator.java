package com.alechilles.alecstamework.npc.breeding;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Orders immutable plan resolution, hard-cap admission, guarded registration, parent effects, and
 * delayed execution for both manual and passive breeding.
 *
 * <p>No success effect or cooldown runs until the exact job reservation is registered. Planning
 * failures, capacity rejection, and duplicate parent/profile jobs therefore leave parent state
 * untouched.
 */
public final class BreedingPairingCoordinator {
    private final TameworkBreedingServices services;
    private final JobIdFactory jobIdFactory;
    private final BreedingJobScheduler scheduler;
    private final long approachDelayMs;

    public BreedingPairingCoordinator(@Nonnull TameworkBreedingServices services,
                                      @Nonnull BreedingJobScheduler scheduler,
                                      long approachDelayMs) {
        this(services, BreedingPairingCoordinator::defaultJobId, scheduler, approachDelayMs);
    }

    BreedingPairingCoordinator(@Nonnull TameworkBreedingServices services,
                               @Nonnull Supplier<UUID> jobIdSource,
                               @Nonnull BreedingJobScheduler scheduler,
                               long approachDelayMs) {
        this(services, request -> jobIdSource.get(), scheduler, approachDelayMs);
    }

    private BreedingPairingCoordinator(@Nonnull TameworkBreedingServices services,
                                       @Nonnull JobIdFactory jobIdFactory,
                                       @Nonnull BreedingJobScheduler scheduler,
                                       long approachDelayMs) {
        this.services = Objects.requireNonNull(services, "services");
        this.jobIdFactory = Objects.requireNonNull(jobIdFactory, "jobIdFactory");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.approachDelayMs = Math.max(0L, approachDelayMs);
    }

    /** Attempts one complete admission transaction. */
    @Nonnull
    public PairingResult admit(@Nonnull PairingRequest request) {
        PairingResult reserved = reserve(request);
        return reserved.reserved()
                ? activate(request, reserved.job().orElseThrow().jobId())
                : reserved;
    }

    /** Reserves exact nearby capacity and both parents without applying cooldown/effects yet. */
    @Nonnull
    public PairingResult reserve(@Nonnull PairingRequest request) {
        Objects.requireNonNull(request, "request");
        UUID jobId = Objects.requireNonNull(
                request.requestedJobId() != null
                        ? request.requestedJobId()
                        : jobIdFactory.create(request),
                "jobId result"
        );
        PreparedAdmission prepared;
        try {
            prepared = prepare(request, jobId);
        } catch (RuntimeException | LinkageError exception) {
            return new PairingResult(PairingStatus.INVALID_PREPARATION, Optional.empty(), null);
        }
        if (!prepared.capacityDecision().allowed()) {
            return new PairingResult(
                    PairingStatus.CAPACITY_REJECTED,
                    Optional.empty(),
                    prepared.capacityDecision().reason()
            );
        }
        BreedingPopulationAdmissionService.AdmissionResult admission =
                services.populationAdmissionService().admit(
                        prepared.capacityDecision().request(),
                        prepared.capacityDecision().candidateChildren()
                );
        if (!prepared.plan().isNaturallyEmpty() && !admission.admittedAny()) {
            return new PairingResult(PairingStatus.CAPACITY_REJECTED, Optional.empty(), "zero-headroom");
        }
        BreedingBirthJob job = request.createJob(jobId, prepared.plan(), admission.admission());
        BreedingBirthJobRegistry.AdmissionResult registered =
                services.jobRegistry().register(request.storeScope(), job);
        if (registered.status() != BreedingBirthJobRegistry.AdmissionStatus.ACCEPTED) {
            PairingStatus status = registered.status()
                    == BreedingBirthJobRegistry.AdmissionStatus.ALREADY_REGISTERED
                    ? PairingStatus.ALREADY_REGISTERED
                    : PairingStatus.REGISTRY_REJECTED;
            return new PairingResult(status, registered.job(), registered.status().name());
        }
        services.jobDiagnostics().register(request.storeScope(), job);
        services.jobDiagnostics().recordInitialAdmission(
                job.jobId(),
                prepared.capacityDecision().request(),
                admission
        );
        return new PairingResult(PairingStatus.RESERVED, Optional.of(job), null);
    }

    /** Applies provisional parent state and schedules one already-reserved job. */
    @Nonnull
    public PairingResult activate(@Nonnull PairingRequest request, @Nonnull UUID jobId) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(jobId, "jobId");
        Optional<BreedingBirthJob> located = services.jobRegistry().find(request.storeScope(), jobId);
        if (located.isEmpty()) {
            return new PairingResult(PairingStatus.REGISTRY_REJECTED, Optional.empty(), "reserved-job-missing");
        }
        BreedingBirthJob job = located.orElseThrow();
        if (job.state() != BreedingBirthJobState.RESERVED) {
            return new PairingResult(PairingStatus.ALREADY_REGISTERED, Optional.of(job), job.state().name());
        }
        return applyEffectsAndSchedule(request, job);
    }

    private PreparedAdmission prepare(PairingRequest request, UUID jobId) {
        BreedingBirthPlan plan = Objects.requireNonNull(
                request.planResolver().resolve(
                        jobId,
                        () -> services.birthPlanService().createPlan(
                                request.parentAFertilityMultiplier(),
                                request.parentBFertilityMultiplier(),
                                request.childResolver()
                        )
                ),
                "planResolver result"
        );
        CapacityDecision capacityDecision = Objects.requireNonNull(
                request.capacityResolver().resolve(jobId, plan),
                "capacityResolver result"
        );
        if (capacityDecision.allowed()) {
            BreedingPopulationAdmissionService.AdmissionRequest capacityRequest =
                    Objects.requireNonNull(capacityDecision.request(), "capacity request");
            if (!jobId.equals(capacityRequest.jobId())
                    || capacityRequest.mode() != request.mode()
                    || !plan.equals(capacityRequest.plan())) {
                throw new IllegalArgumentException("Capacity request must describe the prepared job and plan");
            }
            if (!BreedingJobAdmission.isOrderedSubsequence(
                    plan.children(), capacityDecision.candidateChildren()
            )) {
                throw new IllegalArgumentException("Capacity candidates must preserve birth-plan order");
            }
        }
        return new PreparedAdmission(plan, capacityDecision);
    }

    private PairingResult applyEffectsAndSchedule(PairingRequest request, BreedingBirthJob job) {
        try {
            if (!request.registeredEffects().apply(job)) {
                services.preparedPopulationRegistry().cancelRemaining(
                        job.jobId(), "breeding-registered-effects-rejected"
                );
                BreedingJobDiagnosticSnapshot.RollbackStatus rollback = rollbackSafely(request, job);
                services.jobRegistry().fail(request.storeScope(), job.jobId());
                recordEffectsFailure(job, "effects-rejected", rollback);
                return new PairingResult(PairingStatus.EFFECTS_FAILED, Optional.of(job), "effects-rejected");
            }
            BreedingBirthJobRegistry.TransitionResult advanced = services.jobRegistry().advance(
                    request.storeScope(),
                    job.jobId(),
                    BreedingBirthJobState.RESERVED,
                    BreedingBirthJobState.APPROACHING
            );
            if (advanced.status() != BreedingBirthJobRegistry.TransitionStatus.APPLIED) {
                services.preparedPopulationRegistry().cancelRemaining(
                        job.jobId(), "breeding-job-advance-rejected"
                );
                BreedingJobDiagnosticSnapshot.RollbackStatus rollback = rollbackSafely(request, job);
                services.jobRegistry().fail(request.storeScope(), job.jobId());
                recordEffectsFailure(job, advanced.status().name(), rollback);
                return new PairingResult(PairingStatus.EFFECTS_FAILED, advanced.job(), advanced.status().name());
            }
            scheduler.schedule(job.jobId(), approachDelayMs);
            return new PairingResult(PairingStatus.ACCEPTED, advanced.job(), null);
        } catch (RuntimeException | LinkageError exception) {
            services.preparedPopulationRegistry().cancelRemaining(
                    job.jobId(), "breeding-effects-or-schedule-error"
            );
            BreedingJobDiagnosticSnapshot.RollbackStatus rollback = rollbackSafely(request, job);
            services.jobRegistry().fail(request.storeScope(), job.jobId());
            String reason = "effects-or-schedule-error:" + exception.getClass().getSimpleName();
            recordEffectsFailure(job, reason, rollback);
            return new PairingResult(PairingStatus.EFFECTS_FAILED, Optional.of(job), reason);
        }
    }

    private BreedingJobDiagnosticSnapshot.RollbackStatus rollbackSafely(
            PairingRequest request,
            BreedingBirthJob job) {
        try {
            request.rollbackEffects().rollback(job);
            return BreedingJobDiagnosticSnapshot.RollbackStatus.ATTEMPTED;
        } catch (RuntimeException | LinkageError ignored) {
            // Rollback is fingerprint guarded and best effort; the registry still terminates the job.
            return BreedingJobDiagnosticSnapshot.RollbackStatus.FAILED;
        }
    }

    private void recordEffectsFailure(BreedingBirthJob job,
                                      String reason,
                                      BreedingJobDiagnosticSnapshot.RollbackStatus rollback) {
        services.jobDiagnostics().recordOutcome(
                job.jobId(),
                BreedingJobDiagnosticSnapshot.Outcome.EFFECTS_FAILED,
                0,
                reason,
                rollback,
                null
        );
    }

    public enum PairingStatus {
        RESERVED,
        ACCEPTED,
        CAPACITY_REJECTED,
        ALREADY_REGISTERED,
        REGISTRY_REJECTED,
        INVALID_PREPARATION,
        EFFECTS_FAILED
    }

    public record PairingResult(@Nonnull PairingStatus status,
                                @Nonnull Optional<BreedingBirthJob> job,
                                String reason) {
        public PairingResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(job, "job");
        }

        public boolean accepted() {
            return status == PairingStatus.ACCEPTED;
        }

        public boolean reserved() {
            return status == PairingStatus.RESERVED;
        }
    }

    public record CapacityDecision(boolean allowed,
                                   BreedingPopulationAdmissionService.AdmissionRequest request,
                                   @Nonnull java.util.List<PlannedChild> candidateChildren,
                                   String reason) {
        public CapacityDecision {
            candidateChildren = java.util.List.copyOf(
                    Objects.requireNonNull(candidateChildren, "candidateChildren")
            );
        }

        @Nonnull
        public static CapacityDecision allow(
                @Nonnull BreedingPopulationAdmissionService.AdmissionRequest request) {
            BreedingPopulationAdmissionService.AdmissionRequest resolved =
                    Objects.requireNonNull(request, "request");
            return new CapacityDecision(true, resolved, resolved.plan().children(), null);
        }

        @Nonnull
        public static CapacityDecision allow(
                @Nonnull BreedingPopulationAdmissionService.AdmissionRequest request,
                @Nonnull java.util.List<PlannedChild> candidateChildren) {
            return new CapacityDecision(
                    true,
                    Objects.requireNonNull(request, "request"),
                    candidateChildren,
                    null
            );
        }

        @Nonnull
        public static CapacityDecision reject(String reason) {
            return new CapacityDecision(false, null, java.util.List.of(), reason);
        }
    }

    public record PairingRequest(
            @Nonnull Object storeScope,
            @Nonnull String worldId,
            @Nonnull BreedingPopulationAdmissionService.BreedingMode mode,
            @Nonnull BreedingParentIdentity parentA,
            @Nonnull BreedingParentIdentity parentB,
            double parentAFertilityMultiplier,
            double parentBFertilityMultiplier,
            @Nonnull BreedingBirthPlanService.PlannedChildResolver childResolver,
            @Nonnull CapacityResolver capacityResolver,
            @Nonnull ParentBreedingSnapshot parentASnapshot,
            @Nonnull ParentBreedingSnapshot parentBSnapshot,
            @Nonnull AppliedCooldownFingerprint parentAFingerprint,
            @Nonnull AppliedCooldownFingerprint parentBFingerprint,
            @Nonnull BreedingBirthAnchor anchor,
            @Nonnull RegisteredEffects registeredEffects,
            @Nonnull RollbackEffects rollbackEffects,
            @Nonnull PlanResolver planResolver,
            UUID requestedJobId) {
        /** Backward-compatible request constructor for callers without replay or an explicit ID. */
        public PairingRequest(
                @Nonnull Object storeScope,
                @Nonnull String worldId,
                @Nonnull BreedingPopulationAdmissionService.BreedingMode mode,
                @Nonnull BreedingParentIdentity parentA,
                @Nonnull BreedingParentIdentity parentB,
                double parentAFertilityMultiplier,
                double parentBFertilityMultiplier,
                @Nonnull BreedingBirthPlanService.PlannedChildResolver childResolver,
                @Nonnull CapacityResolver capacityResolver,
                @Nonnull ParentBreedingSnapshot parentASnapshot,
                @Nonnull ParentBreedingSnapshot parentBSnapshot,
                @Nonnull AppliedCooldownFingerprint parentAFingerprint,
                @Nonnull AppliedCooldownFingerprint parentBFingerprint,
                @Nonnull BreedingBirthAnchor anchor,
                @Nonnull RegisteredEffects registeredEffects,
                @Nonnull RollbackEffects rollbackEffects) {
            this(
                    storeScope, worldId, mode, parentA, parentB,
                    parentAFertilityMultiplier, parentBFertilityMultiplier,
                    childResolver, capacityResolver,
                    parentASnapshot, parentBSnapshot,
                    parentAFingerprint, parentBFingerprint,
                    anchor, registeredEffects, rollbackEffects,
                    (jobId, freshPlan) -> freshPlan.get(),
                    null
            );
        }

        public PairingRequest {
            Objects.requireNonNull(storeScope, "storeScope");
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(parentA, "parentA");
            Objects.requireNonNull(parentB, "parentB");
            Objects.requireNonNull(childResolver, "childResolver");
            Objects.requireNonNull(capacityResolver, "capacityResolver");
            Objects.requireNonNull(parentASnapshot, "parentASnapshot");
            Objects.requireNonNull(parentBSnapshot, "parentBSnapshot");
            Objects.requireNonNull(parentAFingerprint, "parentAFingerprint");
            Objects.requireNonNull(parentBFingerprint, "parentBFingerprint");
            Objects.requireNonNull(anchor, "anchor");
            Objects.requireNonNull(registeredEffects, "registeredEffects");
            Objects.requireNonNull(rollbackEffects, "rollbackEffects");
            Objects.requireNonNull(planResolver, "planResolver");
        }

        private BreedingBirthJob createJob(UUID jobId,
                                            BreedingBirthPlan plan,
                                            BreedingJobAdmission admission) {
            return BreedingBirthJob.reserved(
                    jobId,
                    worldId,
                    parentA,
                    parentB,
                    mode,
                    plan,
                    admission,
                    parentASnapshot,
                    parentBSnapshot,
                    parentAFingerprint,
                    parentBFingerprint,
                    anchor
            );
        }
    }

    @FunctionalInterface
    public interface CapacityResolver {
        @Nonnull
        CapacityDecision resolve(@Nonnull UUID jobId, @Nonnull BreedingBirthPlan plan);
    }

    @FunctionalInterface
    public interface RegisteredEffects {
        boolean apply(@Nonnull BreedingBirthJob registeredJob);
    }

    @FunctionalInterface
    public interface RollbackEffects {
        void rollback(@Nonnull BreedingBirthJob registeredJob);
    }

    @FunctionalInterface
    public interface PlanResolver {
        @Nonnull
        BreedingBirthPlan resolve(@Nonnull UUID jobId, @Nonnull Supplier<BreedingBirthPlan> freshPlan);
    }

    @FunctionalInterface
    private interface JobIdFactory {
        UUID create(PairingRequest request);
    }

    private static UUID defaultJobId(PairingRequest request) {
        return BreedingAttemptIdentity.forAppliedCooldowns(
                request.parentA(), request.parentAFingerprint(),
                request.parentB(), request.parentBFingerprint()
        );
    }

    private record PreparedAdmission(BreedingBirthPlan plan, CapacityDecision capacityDecision) {
    }
}
