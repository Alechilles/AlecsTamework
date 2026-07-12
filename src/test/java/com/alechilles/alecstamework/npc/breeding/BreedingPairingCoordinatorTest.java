package com.alechilles.alecstamework.npc.breeding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static com.alechilles.alecstamework.npc.breeding.BreedingPairingCoordinator.PairingStatus.ACCEPTED;
import static com.alechilles.alecstamework.npc.breeding.BreedingPairingCoordinator.PairingStatus.CAPACITY_REJECTED;
import static com.alechilles.alecstamework.npc.breeding.BreedingPairingCoordinator.PairingStatus.EFFECTS_FAILED;
import static com.alechilles.alecstamework.npc.breeding.BreedingPairingCoordinator.PairingStatus.REGISTRY_REJECTED;
import static com.alechilles.alecstamework.npc.breeding.BreedingPopulationAdmissionService.BreedingMode.PASSIVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for reserve-before-effects pairing admission. */
class BreedingPairingCoordinatorTest {
    private static final String WORLD = "world-a";
    private static final UUID JOB_ID = uuid(100L);

    @Test
    void resolvesOnceRegistersBeforeEffectsThenSchedulesOnlyJobId() {
        TameworkBreedingServices services = new TameworkBreedingServices(() -> 0.25);
        Object scope = new Object();
        ArrayList<String> events = new ArrayList<>();
        AtomicInteger childResolutions = new AtomicInteger();
        BreedingPairingCoordinator coordinator = new BreedingPairingCoordinator(
                services,
                () -> JOB_ID,
                (jobId, delayMs) -> {
                    events.add("schedule:" + jobId);
                    assertEquals(
                            BreedingBirthJobState.APPROACHING,
                            services.jobRegistry().find(scope, jobId).orElseThrow().state()
                    );
                },
                5L
        );

        BreedingPairingCoordinator.PairingResult result = coordinator.admit(request(
                scope,
                parent(1L, "profile-a"),
                parent(2L, "profile-b"),
                index -> {
                    childResolutions.incrementAndGet();
                    return child(index);
                },
                (jobId, plan) -> allow(jobId, plan, scopeValue(), 8, Map.of()),
                job -> {
                    events.add("effects:" + job.jobId());
                    assertEquals(
                            BreedingBirthJobState.RESERVED,
                            services.jobRegistry().find(scope, job.jobId()).orElseThrow().state()
                    );
                    return true;
                },
                job -> events.add("rollback:" + job.jobId())
        ));

        assertEquals(ACCEPTED, result.status());
        assertEquals(1, childResolutions.get());
        assertEquals(List.of("effects:" + JOB_ID, "schedule:" + JOB_ID), events);
    }

    @Test
    void activeParentAndStableProfileDuplicatesNeverApplyEffects() {
        TameworkBreedingServices services = new TameworkBreedingServices(() -> 0.25);
        Object scope = new Object();
        AtomicInteger effects = new AtomicInteger();
        BreedingPairingCoordinator coordinator = coordinator(services, effects, new AtomicInteger());
        BreedingParentIdentity parentA = parent(1L, "profile-a");

        assertEquals(ACCEPTED, coordinator.admit(defaultRequest(
                scope, parentA, parent(2L, "profile-b"), effects, new AtomicInteger()
        )).status());
        BreedingPairingCoordinator.PairingResult parentConflict = coordinator.admit(defaultRequest(
                scope, parentA, parent(3L, "profile-c"), effects, new AtomicInteger()
        ));
        BreedingPairingCoordinator.PairingResult profileConflict = coordinator.admit(defaultRequest(
                scope,
                parent(4L, "profile-a"),
                parent(5L, "profile-d"),
                effects,
                new AtomicInteger()
        ));

        assertEquals(REGISTRY_REJECTED, parentConflict.status());
        assertEquals(REGISTRY_REJECTED, profileConflict.status());
        assertEquals(1, effects.get());
    }

    @Test
    void initialCapacityZeroMutatesNoParentAndRegistersNoJob() {
        TameworkBreedingServices services = new TameworkBreedingServices(() -> 0.25);
        Object scope = new Object();
        AtomicInteger effects = new AtomicInteger();
        AtomicInteger rollbacks = new AtomicInteger();
        BreedingPairingCoordinator coordinator = coordinator(services, effects, rollbacks);

        BreedingPairingCoordinator.PairingResult result = coordinator.admit(request(
                scope,
                parent(1L, "profile-a"),
                parent(2L, "profile-b"),
                BreedingPairingCoordinatorTest::child,
                (jobId, plan) -> allow(jobId, plan, scopeValue(), 1, Map.of("cattle", 1)),
                job -> {
                    effects.incrementAndGet();
                    return true;
                },
                job -> rollbacks.incrementAndGet()
        ));

        assertEquals(CAPACITY_REJECTED, result.status());
        assertEquals(0, effects.get());
        assertEquals(0, rollbacks.get());
        assertEquals(0, services.jobRegistry().activeJobCount(scope));
    }

    @Test
    void effectsOrSchedulerFailureRollsBackAndTerminatesJob() {
        Object effectsScope = new Object();
        AtomicInteger effectsRollback = new AtomicInteger();
        TameworkBreedingServices effectsServices = new TameworkBreedingServices(() -> 0.25);
        BreedingPairingCoordinator effectsCoordinator = new BreedingPairingCoordinator(
                effectsServices,
                () -> uuid(200L),
                (jobId, delay) -> { },
                0L
        );
        BreedingPairingCoordinator.PairingResult effectsFailure = effectsCoordinator.admit(request(
                effectsScope,
                parent(1L, "profile-a"),
                parent(2L, "profile-b"),
                BreedingPairingCoordinatorTest::child,
                (jobId, plan) -> allow(jobId, plan, scopeValue(), 8, Map.of()),
                job -> false,
                job -> effectsRollback.incrementAndGet()
        ));

        Object schedulerScope = new Object();
        AtomicInteger schedulerRollback = new AtomicInteger();
        TameworkBreedingServices schedulerServices = new TameworkBreedingServices(() -> 0.25);
        BreedingPairingCoordinator schedulerCoordinator = new BreedingPairingCoordinator(
                schedulerServices,
                () -> uuid(300L),
                (jobId, delay) -> { throw new IllegalStateException("scheduler down"); },
                0L
        );
        BreedingPairingCoordinator.PairingResult schedulerFailure = schedulerCoordinator.admit(request(
                schedulerScope,
                parent(3L, "profile-c"),
                parent(4L, "profile-d"),
                BreedingPairingCoordinatorTest::child,
                (jobId, plan) -> allow(jobId, plan, scopeValue(), 8, Map.of()),
                job -> true,
                job -> schedulerRollback.incrementAndGet()
        ));

        assertEquals(EFFECTS_FAILED, effectsFailure.status());
        assertEquals(1, effectsRollback.get());
        assertTrue(effectsServices.jobRegistry().find(effectsScope, uuid(200L)).orElseThrow().state().isTerminal());
        BreedingJobDiagnosticSnapshot effectsDiagnostics = effectsServices.jobDiagnostics()
                .find(uuid(200L))
                .orElseThrow();
        assertEquals(BreedingJobDiagnosticSnapshot.Outcome.EFFECTS_FAILED, effectsDiagnostics.outcome());
        assertEquals("effects-rejected", effectsDiagnostics.reason());
        assertEquals(
                BreedingJobDiagnosticSnapshot.RollbackStatus.ATTEMPTED,
                effectsDiagnostics.rollbackStatus()
        );
        assertEquals(1, effectsDiagnostics.initialCapacity().admittedChildren());
        assertEquals(EFFECTS_FAILED, schedulerFailure.status());
        assertEquals(1, schedulerRollback.get());
        assertTrue(schedulerServices.jobRegistry().find(schedulerScope, uuid(300L)).orElseThrow().state().isTerminal());
        BreedingJobDiagnosticSnapshot schedulerDiagnostics = schedulerServices.jobDiagnostics()
                .find(uuid(300L))
                .orElseThrow();
        assertEquals(
                "effects-or-schedule-error:IllegalStateException",
                schedulerDiagnostics.reason()
        );
    }

    private static BreedingPairingCoordinator coordinator(
            TameworkBreedingServices services,
            AtomicInteger effects,
            AtomicInteger rollbacks) {
        AtomicInteger ids = new AtomicInteger(1000);
        return new BreedingPairingCoordinator(
                services,
                () -> uuid(ids.incrementAndGet()),
                (jobId, delay) -> { },
                0L
        );
    }

    private static BreedingPairingCoordinator.PairingRequest defaultRequest(
            Object scope,
            BreedingParentIdentity parentA,
            BreedingParentIdentity parentB,
            AtomicInteger effects,
            AtomicInteger rollbacks) {
        return request(
                scope,
                parentA,
                parentB,
                BreedingPairingCoordinatorTest::child,
                (jobId, plan) -> allow(jobId, plan, scopeValue(), 8, Map.of()),
                job -> {
                    effects.incrementAndGet();
                    return true;
                },
                job -> rollbacks.incrementAndGet()
        );
    }

    private static BreedingPairingCoordinator.PairingRequest request(
            Object scope,
            BreedingParentIdentity parentA,
            BreedingParentIdentity parentB,
            BreedingBirthPlanService.PlannedChildResolver childResolver,
            BreedingPairingCoordinator.CapacityResolver capacityResolver,
            BreedingPairingCoordinator.RegisteredEffects effects,
            BreedingPairingCoordinator.RollbackEffects rollback) {
        return new BreedingPairingCoordinator.PairingRequest(
                scope,
                WORLD,
                PASSIVE,
                parentA,
                parentB,
                1.0,
                1.0,
                childResolver,
                capacityResolver,
                ParentBreedingSnapshot.empty(),
                ParentBreedingSnapshot.empty(),
                AppliedCooldownFingerprint.none(),
                AppliedCooldownFingerprint.none(),
                new BreedingBirthAnchor(0.0, 64.0, 0.0),
                effects,
                rollback
        );
    }

    private static BreedingPairingCoordinator.CapacityDecision allow(
            UUID jobId,
            BreedingBirthPlan plan,
            BreedingReservationScope scope,
            int maxNearby,
            Map<String, Integer> live) {
        return BreedingPairingCoordinator.CapacityDecision.allow(
                new BreedingPopulationAdmissionService.AdmissionRequest(
                        jobId,
                        WORLD,
                        PASSIVE,
                        plan,
                        new BreedingBirthAnchor(0.0, 64.0, 0.0),
                        scope,
                        maxNearby,
                        live,
                        BreedingCapacityHeadroom.unlimited()
                )
        );
    }

    private static BreedingReservationScope scopeValue() {
        return new BreedingReservationScope(10.0, null, List.of());
    }

    private static PlannedChild child(int index) {
        return new PlannedChild("baby-" + index, "adult", "Female", "family", "cattle");
    }

    private static BreedingParentIdentity parent(long uuid, String profile) {
        return new BreedingParentIdentity(uuid(uuid), profile);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
