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
    private final Supplier<UUID> jobIdSource;
    private final BreedingJobScheduler scheduler;
    private final long approachDelayMs;

    public BreedingPairingCoordinator(@Nonnull TameworkBreedingServices services,
                                      @Nonnull BreedingJobScheduler scheduler,
                                      long approachDelayMs) {
        this(services, UUID::randomUUID, scheduler, approachDelayMs);
    }

    BreedingPairingCoordinator(@Nonnull TameworkBreedingServices services,
                               @Nonnull Supplier<UUID> jobIdSource,
                               @Nonnull BreedingJobScheduler scheduler,
                               long approachDelayMs) {
        this.services = Objects.requireNonNull(services, "services");
        this.jobIdSource = Objects.requireNonNull(jobIdSource, "jobIdSource");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.approachDelayMs = Math.max(0L, approachDelayMs);
    }

    /** Attempts one complete admission transaction. */
    @Nonnull
    public PairingResult admit(@Nonnull PairingRequest request) {
        Objects.requireNonNull(request, "request");
        UUID jobId = Objects.requireNonNull(jobIdSource.get(), "jobIdSource result");
        PreparedAdmission prepared;
        try {
            prepared = prepare(request, jobId);
        } catch (RuntimeException exception) {
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
                services.populationAdmissionService().admit(prepared.capacityDecision().request());
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
        return applyEffectsAndSchedule(request, job);
    }

    private PreparedAdmission prepare(PairingRequest request, UUID jobId) {
        BreedingBirthPlan plan = services.birthPlanService().createPlan(
                request.parentAFertilityMultiplier(),
                request.parentBFertilityMultiplier(),
                request.childResolver()
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
        }
        return new PreparedAdmission(plan, capacityDecision);
    }

    private PairingResult applyEffectsAndSchedule(PairingRequest request, BreedingBirthJob job) {
        try {
            if (!request.registeredEffects().apply(job)) {
                rollbackSafely(request, job);
                services.jobRegistry().fail(request.storeScope(), job.jobId());
                return new PairingResult(PairingStatus.EFFECTS_FAILED, Optional.of(job), "effects-rejected");
            }
            BreedingBirthJobRegistry.TransitionResult advanced = services.jobRegistry().advance(
                    request.storeScope(),
                    job.jobId(),
                    BreedingBirthJobState.RESERVED,
                    BreedingBirthJobState.APPROACHING
            );
            if (advanced.status() != BreedingBirthJobRegistry.TransitionStatus.APPLIED) {
                rollbackSafely(request, job);
                services.jobRegistry().fail(request.storeScope(), job.jobId());
                return new PairingResult(PairingStatus.EFFECTS_FAILED, advanced.job(), advanced.status().name());
            }
            scheduler.schedule(job.jobId(), approachDelayMs);
            return new PairingResult(PairingStatus.ACCEPTED, advanced.job(), null);
        } catch (RuntimeException exception) {
            rollbackSafely(request, job);
            services.jobRegistry().fail(request.storeScope(), job.jobId());
            return new PairingResult(PairingStatus.EFFECTS_FAILED, Optional.of(job), exception.getClass().getSimpleName());
        }
    }

    private void rollbackSafely(PairingRequest request, BreedingBirthJob job) {
        try {
            request.rollbackEffects().rollback(job);
        } catch (RuntimeException ignored) {
            // Rollback is fingerprint guarded and best effort; the registry still terminates the job.
        }
    }

    public enum PairingStatus {
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
    }

    public record CapacityDecision(boolean allowed,
                                   BreedingPopulationAdmissionService.AdmissionRequest request,
                                   String reason) {
        @Nonnull
        public static CapacityDecision allow(
                @Nonnull BreedingPopulationAdmissionService.AdmissionRequest request) {
            return new CapacityDecision(true, Objects.requireNonNull(request, "request"), null);
        }

        @Nonnull
        public static CapacityDecision reject(String reason) {
            return new CapacityDecision(false, null, reason);
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
            @Nonnull RollbackEffects rollbackEffects) {
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

    private record PreparedAdmission(BreedingBirthPlan plan, CapacityDecision capacityDecision) {
    }
}
