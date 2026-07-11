package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.breeding.AppliedCooldownFingerprint;
import com.alechilles.alecstamework.npc.breeding.BreedingBirthAnchor;
import com.alechilles.alecstamework.npc.breeding.BreedingBirthJob;
import com.alechilles.alecstamework.npc.breeding.BreedingBirthJobRegistry;
import com.alechilles.alecstamework.npc.breeding.BreedingBirthJobState;
import com.alechilles.alecstamework.npc.breeding.BreedingBirthPlan;
import com.alechilles.alecstamework.npc.breeding.BreedingFertilitySnapshot;
import com.alechilles.alecstamework.npc.breeding.BreedingJobAdmission;
import com.alechilles.alecstamework.npc.breeding.BreedingJobExecutionService;
import com.alechilles.alecstamework.npc.breeding.BreedingJobScheduler;
import com.alechilles.alecstamework.npc.breeding.BreedingParentIdentity;
import com.alechilles.alecstamework.npc.breeding.BreedingPlayerCapacityScope;
import com.alechilles.alecstamework.npc.breeding.BreedingPopulationAdmissionService;
import com.alechilles.alecstamework.npc.breeding.BreedingReservationScope;
import com.alechilles.alecstamework.npc.breeding.ParentBreedingSnapshot;
import com.alechilles.alecstamework.npc.breeding.PlannedChild;
import com.alechilles.alecstamework.npc.breeding.TameworkBreedingServices;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.CancellationReason.COOP_CAPTURE;
import static com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.CancellationStatus.CANCELLED;
import static com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.CancellationStatus.NOT_FOUND;
import static com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.MatchKind.ENTITY_UUID;
import static com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.MatchKind.PROFILE_ID;
import static com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.ParentRollbackStatus.ERROR;
import static com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.ParentRollbackStatus.RESTORED;
import static com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.ParentRollbackStatus.SKIPPED_NEWER_STATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for registry-first cancellation before managed-coop snapshots. */
class BreedingCaptureCancellationServiceTest {
    @Test
    void cancellationAndBothRollbacksFinishBeforeSnapshotReadsCapturedState() {
        BreedingBirthJobRegistry registry = new BreedingBirthJobRegistry();
        Object scope = new Object();
        BreedingBirthJob job = job(100L);
        assertEquals(BreedingBirthJobRegistry.AdmissionStatus.ACCEPTED,
                registry.register(scope, job).status());
        List<String> events = new ArrayList<>();
        Map<UUID, ParentBreedingSnapshot> liveState = new HashMap<>();
        RecordingRollbackGateway gateway = new RecordingRollbackGateway(
                registry,
                scope,
                events,
                liveState
        );
        BreedingCaptureCancellationService service =
                new BreedingCaptureCancellationService(registry, gateway);

        BreedingCaptureCancellationService.SnapshotHandoff<ParentBreedingSnapshot> handoff =
                service.cancelThenCaptureSnapshotInScope(
                        scope,
                        job.firstParent().entityUuid(),
                        job.firstParent().profileId(),
                        COOP_CAPTURE,
                        () -> {
                            events.add("snapshot");
                            assertEquals(BreedingBirthJobState.CANCELLED,
                                    registry.find(scope, job.jobId()).orElseThrow().state());
                            assertTrue(registry.activeReservations(scope).isEmpty());
                            return liveState.get(job.firstParent().entityUuid());
                        }
                );

        assertEquals(List.of("rollback:first", "rollback:second", "snapshot"), events);
        assertEquals(CANCELLED, handoff.cancellation().status());
        assertEquals(ENTITY_UUID, handoff.cancellation().matchKind());
        assertEquals(job.firstParentSnapshot(), handoff.snapshot());
        assertTrue(handoff.cancellation().capturedParent().orElseThrow().restored());
        assertTrue(handoff.cancellation().partner().orElseThrow().restored());
        assertEquals(0, registry.activeJobCount(scope));
    }

    @Test
    void cancellationReplayDoesNotRollbackTwiceAndLateExecutionIsTerminal() {
        TameworkBreedingServices services = new TameworkBreedingServices(() -> 0.5);
        Object scope = new Object();
        BreedingBirthJob job = job(200L);
        services.jobRegistry().register(scope, job);
        AtomicInteger rollbacks = new AtomicInteger();
        BreedingCaptureCancellationService service = new BreedingCaptureCancellationService(
                services.jobRegistry(),
                (ignoredScope, ignoredJob, ignoredFirst, ignoredLiveUuid) -> {
                    rollbacks.incrementAndGet();
                    return new BreedingCaptureCancellationService.ParentRollbackOutcome(RESTORED, true);
                }
        );

        BreedingCaptureCancellationService.CancellationResult first =
                service.cancelForCapturedParentInScope(
                        scope,
                        job.firstParent().entityUuid(),
                        null,
                        COOP_CAPTURE
                );
        BreedingCaptureCancellationService.CancellationResult replay =
                service.cancelForCapturedParentInScope(
                        scope,
                        job.firstParent().entityUuid(),
                        null,
                        COOP_CAPTURE
                );
        CountingRuntime runtime = new CountingRuntime();
        BreedingJobScheduler scheduler = (jobId, delayMs) -> {
            throw new AssertionError("A terminal callback must not reschedule");
        };
        BreedingJobExecutionService<String> execution =
                new BreedingJobExecutionService<>(services, runtime, scheduler, 100L);

        BreedingJobExecutionService.ExecutionResult late = execution.execute(job.jobId());

        assertEquals(CANCELLED, first.status());
        assertEquals(NOT_FOUND, replay.status());
        assertEquals(2, rollbacks.get());
        assertEquals(BreedingJobExecutionService.ExecutionStatus.TERMINAL, late.status());
        assertEquals(0, runtime.parentResolutions.get());
        assertEquals(0, runtime.spawnAttempts.get());
    }

    @Test
    void stableProfileFallbackCancelsRemappedParentAndLabelsCapturedReport() {
        BreedingBirthJobRegistry registry = new BreedingBirthJobRegistry();
        Object scope = new Object();
        BreedingBirthJob job = job(300L);
        registry.register(scope, job);
        AtomicReference<UUID> capturedLiveUuid = new AtomicReference<>();
        BreedingCaptureCancellationService service = new BreedingCaptureCancellationService(
                registry,
                (ignoredScope, ignoredJob, firstParent, liveUuid) -> {
                    if (!firstParent) {
                        capturedLiveUuid.set(liveUuid);
                    }
                    return new BreedingCaptureCancellationService.ParentRollbackOutcome(RESTORED, false);
                }
        );
        UUID remappedUuid = uuid(9_999L);

        BreedingCaptureCancellationService.CancellationResult result =
                service.cancelForCapturedParentInScope(
                        scope,
                        remappedUuid,
                        "  " + job.secondParent().profileId() + "  ",
                        COOP_CAPTURE
                );

        assertEquals(CANCELLED, result.status());
        assertEquals(PROFILE_ID, result.matchKind());
        assertEquals(remappedUuid, capturedLiveUuid.get());
        assertEquals(
                new BreedingParentIdentity(remappedUuid, job.secondParent().profileId()),
                result.capturedParent().orElseThrow().identity()
        );
        assertEquals(job.firstParent(), result.partner().orElseThrow().identity());
    }

    @Test
    void fingerprintMismatchReportPreservesNewerCapturedStateForSnapshot() {
        BreedingBirthJobRegistry registry = new BreedingBirthJobRegistry();
        Object scope = new Object();
        BreedingBirthJob job = job(400L);
        registry.register(scope, job);
        ParentBreedingSnapshot newerState = snapshot("newer-config", 0.95, true);
        Map<UUID, ParentBreedingSnapshot> liveState = new HashMap<>();
        liveState.put(job.firstParent().entityUuid(), newerState);
        BreedingCaptureCancellationService service = new BreedingCaptureCancellationService(
                registry,
                (ignoredScope, cancelledJob, firstParent, ignoredLiveUuid) -> {
                    if (firstParent) {
                        return new BreedingCaptureCancellationService.ParentRollbackOutcome(
                                SKIPPED_NEWER_STATE,
                                false
                        );
                    }
                    liveState.put(
                            cancelledJob.secondParent().entityUuid(),
                            cancelledJob.secondParentSnapshot()
                    );
                    return new BreedingCaptureCancellationService.ParentRollbackOutcome(RESTORED, true);
                }
        );

        BreedingCaptureCancellationService.SnapshotHandoff<ParentBreedingSnapshot> handoff =
                service.cancelThenCaptureSnapshotInScope(
                        scope,
                        job.firstParent().entityUuid(),
                        null,
                        COOP_CAPTURE,
                        () -> liveState.get(job.firstParent().entityUuid())
                );

        assertEquals(SKIPPED_NEWER_STATE,
                handoff.cancellation().capturedParent().orElseThrow().status());
        assertEquals(newerState, handoff.snapshot());
        assertEquals(job.secondParentSnapshot(),
                liveState.get(job.secondParent().entityUuid()));
    }

    @Test
    void rollbackFailureIsReportedButDoesNotSkipPartnerOrSnapshot() {
        BreedingBirthJobRegistry registry = new BreedingBirthJobRegistry();
        Object scope = new Object();
        BreedingBirthJob job = job(500L);
        registry.register(scope, job);
        AtomicInteger attempts = new AtomicInteger();
        BreedingCaptureCancellationService service = new BreedingCaptureCancellationService(
                registry,
                (ignoredScope, ignoredJob, firstParent, ignoredLiveUuid) -> {
                    attempts.incrementAndGet();
                    if (firstParent) {
                        throw new IllegalStateException("simulated rollback failure");
                    }
                    return new BreedingCaptureCancellationService.ParentRollbackOutcome(RESTORED, true);
                }
        );

        BreedingCaptureCancellationService.SnapshotHandoff<String> handoff =
                service.cancelThenCaptureSnapshotInScope(
                        scope,
                        job.firstParent().entityUuid(),
                        null,
                        COOP_CAPTURE,
                        () -> "captured"
                );

        assertEquals(2, attempts.get());
        assertEquals(ERROR, handoff.cancellation().capturedParent().orElseThrow().status());
        assertEquals(RESTORED, handoff.cancellation().partner().orElseThrow().status());
        assertEquals("captured", handoff.snapshot());
    }

    @Test
    void notFoundIsSafeAndStillHandsOffSnapshot() {
        BreedingBirthJobRegistry registry = new BreedingBirthJobRegistry();
        Object scope = new Object();
        AtomicInteger rollbackCalls = new AtomicInteger();
        BreedingCaptureCancellationService service = new BreedingCaptureCancellationService(
                registry,
                (ignoredScope, ignoredJob, ignoredFirst, ignoredLiveUuid) -> {
                    rollbackCalls.incrementAndGet();
                    return new BreedingCaptureCancellationService.ParentRollbackOutcome(RESTORED, true);
                }
        );

        BreedingCaptureCancellationService.SnapshotHandoff<String> handoff =
                service.cancelThenCaptureSnapshotInScope(
                        scope,
                        uuid(404L),
                        null,
                        COOP_CAPTURE,
                        () -> "snapshot"
                );

        assertEquals(NOT_FOUND, handoff.cancellation().status());
        assertFalse(handoff.cancellation().cancelled());
        assertTrue(handoff.cancellation().capturedParent().isEmpty());
        assertEquals(0, rollbackCalls.get());
        assertEquals("snapshot", handoff.snapshot());
    }

    private static BreedingBirthJob job(long jobId) {
        BreedingParentIdentity first = new BreedingParentIdentity(uuid(1L), "profile-a");
        BreedingParentIdentity second = new BreedingParentIdentity(uuid(2L), "profile-b");
        PlannedChild child = new PlannedChild("baby", "adult", "Female", "family", "livestock");
        BreedingBirthPlan plan = new BreedingBirthPlan(
                new BreedingFertilitySnapshot(1.0, 1.0, 1, 0.25, 1),
                List.of(child)
        );
        BreedingReservationScope scope = new BreedingReservationScope(
                10.0,
                null,
                List.of(BreedingPlayerCapacityScope.global(uuid(900L)))
        );
        ParentBreedingSnapshot firstSnapshot = snapshot("first-config", 0.71, true);
        ParentBreedingSnapshot secondSnapshot = snapshot("second-config", 0.83, false);
        return BreedingBirthJob.reserved(
                uuid(jobId),
                "test-world",
                first,
                second,
                BreedingPopulationAdmissionService.BreedingMode.PASSIVE,
                plan,
                BreedingJobAdmission.of(List.of(child), scope),
                firstSnapshot,
                secondSnapshot,
                fingerprint(second.entityUuid(), 1_000L),
                fingerprint(first.entityUuid(), 1_100L),
                new BreedingBirthAnchor(10.0, 64.0, 10.0)
        );
    }

    private static ParentBreedingSnapshot snapshot(String configId,
                                                    double happiness,
                                                    boolean ready) {
        return new ParentBreedingSnapshot(
                configId,
                happiness,
                50L,
                ready,
                true,
                -200L,
                -400L,
                200L,
                null,
                null,
                0L,
                ParentBreedingSnapshot.AlarmSnapshot.missing()
        );
    }

    private static AppliedCooldownFingerprint fingerprint(UUID partnerUuid, long untilMs) {
        return new AppliedCooldownFingerprint(
                true,
                false,
                untilMs,
                untilMs - 500L,
                500L,
                partnerUuid,
                75L,
                null,
                0L,
                ParentBreedingSnapshot.AlarmSnapshot.missing()
        );
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }

    private static final class RecordingRollbackGateway
            implements BreedingCaptureCancellationService.ParentRollbackGateway {
        private final BreedingBirthJobRegistry registry;
        private final Object scope;
        private final List<String> events;
        private final Map<UUID, ParentBreedingSnapshot> liveState;

        private RecordingRollbackGateway(BreedingBirthJobRegistry registry,
                                         Object scope,
                                         List<String> events,
                                         Map<UUID, ParentBreedingSnapshot> liveState) {
            this.registry = registry;
            this.scope = scope;
            this.events = events;
            this.liveState = liveState;
        }

        @Override
        public BreedingCaptureCancellationService.ParentRollbackOutcome rollback(
                Object ignoredScope,
                BreedingBirthJob job,
                boolean firstParent,
                UUID ignoredLiveUuid) {
            assertEquals(BreedingBirthJobState.CANCELLED,
                    registry.find(scope, job.jobId()).orElseThrow().state());
            assertTrue(registry.activeReservations(scope).isEmpty());
            BreedingParentIdentity identity = firstParent ? job.firstParent() : job.secondParent();
            ParentBreedingSnapshot snapshot = firstParent
                    ? job.firstParentSnapshot()
                    : job.secondParentSnapshot();
            liveState.put(identity.entityUuid(), snapshot);
            events.add(firstParent ? "rollback:first" : "rollback:second");
            return new BreedingCaptureCancellationService.ParentRollbackOutcome(RESTORED, true);
        }
    }

    private static final class CountingRuntime
            implements BreedingJobExecutionService.Runtime<String> {
        private final AtomicInteger parentResolutions = new AtomicInteger();
        private final AtomicInteger spawnAttempts = new AtomicInteger();

        @Override
        public BreedingJobExecutionService.ParentResolution<String> resolveParents(
                BreedingBirthJob job) {
            parentResolutions.incrementAndGet();
            return BreedingJobExecutionService.ParentResolution.invalid("unexpected");
        }

        @Override
        public void showHearts(BreedingBirthJob job, String context) {
        }

        @Override
        public BreedingPopulationAdmissionService.AdmissionRequest buildSpawnAdmissionRequest(
                BreedingBirthJob job,
                String context) {
            return null;
        }

        @Override
        public boolean spawnChild(BreedingBirthJob job,
                                  PlannedChild child,
                                  int childIndex,
                                  String context) {
            spawnAttempts.incrementAndGet();
            return true;
        }

        @Override
        public void onCompleted(BreedingBirthJob job, int spawnedChildren, String context) {
        }

        @Override
        public void rollbackProvisionalCooldown(BreedingBirthJob job) {
        }
    }
}
