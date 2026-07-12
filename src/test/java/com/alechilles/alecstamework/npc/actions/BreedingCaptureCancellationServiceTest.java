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
import com.alechilles.alecstamework.npc.breeding.BreedingJobDiagnosticSnapshot;
import com.alechilles.alecstamework.npc.breeding.BreedingJobDiagnosticsService;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.CancellationReason.COOP_CAPTURE;
import static com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.CancellationStatus.CANCELLED;
import static com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.CancellationStatus.DURABILITY_FAILED;
import static com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.CancellationStatus.DURABILITY_PENDING;
import static com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.CancellationStatus.NOT_FOUND;
import static com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.CancellationStatus.ALREADY_TERMINAL;
import static com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.MatchKind.ENTITY_UUID;
import static com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.MatchKind.PROFILE_ID;
import static com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.ParentRollbackStatus.ERROR;
import static com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.ParentRollbackStatus.RESTORED;
import static com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.ParentRollbackStatus.SKIPPED_NEWER_STATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for registry-first cancellation before managed-coop snapshots. */
class BreedingCaptureCancellationServiceTest {
    /** Regression: capture-crate callers retain the fence until their mutation explicitly ends. */
    @Test
    void successfulRetainedSnapshotDoesNotReleaseButExceptionFailsClosed() {
        AtomicInteger releases = new AtomicInteger();
        BreedingCaptureCancellationService.CancellationResult cancellation =
                new BreedingCaptureCancellationService.CancellationResult(
                        NOT_FOUND, COOP_CAPTURE,
                        BreedingCaptureCancellationService.MatchKind.NONE,
                        Optional.empty(), Optional.empty(), Optional.empty()
        );

        BreedingCaptureCancellationService.SnapshotHandoff<String> retained =
                BreedingCaptureSnapshotFenceHandoff.capture(
                        () -> cancellation, () -> "snapshot", releases::incrementAndGet
                );

        assertEquals(NOT_FOUND, retained.cancellation().status());
        assertEquals("snapshot", retained.snapshot());
        assertEquals(0, releases.get());

        assertThrows(IllegalStateException.class, () ->
                BreedingCaptureSnapshotFenceHandoff.capture(
                        () -> cancellation,
                        () -> {
                            throw new IllegalStateException("snapshot failed");
                        },
                        releases::incrementAndGet
                ));
        assertEquals(1, releases.get());
    }

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
        BreedingJobDiagnosticsService diagnostics = new BreedingJobDiagnosticsService();
        assertTrue(diagnostics.register(scope, job));
        BreedingCaptureCancellationService service =
                new BreedingCaptureCancellationService(registry, gateway, diagnostics);

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
        BreedingJobDiagnosticSnapshot diagnostic = diagnostics.find(job.jobId()).orElseThrow();
        assertEquals(BreedingJobDiagnosticSnapshot.Outcome.CANCELLED, diagnostic.outcome());
        assertEquals("capture-cancelled:COOP_CAPTURE", diagnostic.reason());
        assertEquals(
                BreedingJobDiagnosticSnapshot.RollbackStatus.COMPLETED,
                diagnostic.rollbackStatus()
        );
        assertEquals("first=RESTORED,second=RESTORED", diagnostic.rollbackDetail());
    }

    @Test
    void cancellationRetryReusesDurabilityResultWithoutRollingBackTwice() {
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
        assertEquals(ALREADY_TERMINAL, replay.status());
        assertEquals(2, rollbacks.get());
        assertEquals(BreedingJobExecutionService.ExecutionStatus.TERMINAL, late.status());
        assertEquals(0, runtime.parentResolutions.get());
        assertEquals(0, runtime.spawnAttempts.get());
    }

    @Test
    void pendingDurabilityBlocksSnapshotAndRetryReusesTheSameBarrier() throws Exception {
        BreedingBirthJobRegistry registry = new BreedingBirthJobRegistry();
        Object scope = new Object();
        BreedingBirthJob job = job(250L);
        registry.register(scope, job);
        CompletableFuture<Boolean> durability = new CompletableFuture<>();
        AtomicInteger durabilityStarts = new AtomicInteger();
        AtomicInteger snapshots = new AtomicInteger();
        BreedingCaptureCancellationService service = new BreedingCaptureCancellationService(
                registry,
                (ignoredScope, ignoredJobId, ignoredReason) -> {
                    durabilityStarts.incrementAndGet();
                    return durability;
                },
                (ignoredScope, ignoredJob, ignoredFirst, ignoredLiveUuid) ->
                        new BreedingCaptureCancellationService.ParentRollbackOutcome(RESTORED, true),
                null
        );

        BreedingCaptureCancellationService.SnapshotHandoff<String> pending =
                service.cancelThenCaptureSnapshotInScope(
                        scope,
                        job.firstParent().entityUuid(),
                        job.firstParent().profileId(),
                        COOP_CAPTURE,
                        () -> {
                            snapshots.incrementAndGet();
                            return "snapshot";
                        }
                );
        CompletableFuture<BreedingCaptureCancellationService.CancellationResult> retry =
                service.cancelForCapturedParentDurablyInScope(
                        scope,
                        job.firstParent().entityUuid(),
                        job.firstParent().profileId(),
                        COOP_CAPTURE
                );

        assertEquals(DURABILITY_PENDING, pending.cancellation().status());
        assertNull(pending.snapshot());
        assertFalse(retry.isDone());
        assertEquals(0, snapshots.get());
        assertEquals(1, durabilityStarts.get());

        durability.complete(true);
        assertEquals(ALREADY_TERMINAL, retry.get(1, TimeUnit.SECONDS).status());
        BreedingCaptureCancellationService.SnapshotHandoff<String> completed =
                service.cancelThenCaptureSnapshotInScope(
                        scope,
                        job.firstParent().entityUuid(),
                        job.firstParent().profileId(),
                        COOP_CAPTURE,
                        () -> {
                            snapshots.incrementAndGet();
                            return "snapshot";
                        }
                );

        assertEquals(ALREADY_TERMINAL, completed.cancellation().status());
        assertEquals("snapshot", completed.snapshot());
        assertEquals(1, snapshots.get());
        assertEquals(1, durabilityStarts.get());
    }

    @Test
    void failedDurabilityNeverRunsSnapshotOnRetry() {
        BreedingBirthJobRegistry registry = new BreedingBirthJobRegistry();
        Object scope = new Object();
        BreedingBirthJob job = job(275L);
        registry.register(scope, job);
        AtomicInteger snapshots = new AtomicInteger();
        BreedingCaptureCancellationService service = new BreedingCaptureCancellationService(
                registry,
                (ignoredScope, ignoredJobId, ignoredReason) ->
                        CompletableFuture.completedFuture(false),
                (ignoredScope, ignoredJob, ignoredFirst, ignoredLiveUuid) ->
                        new BreedingCaptureCancellationService.ParentRollbackOutcome(RESTORED, true),
                null
        );

        BreedingCaptureCancellationService.SnapshotHandoff<String> first =
                service.cancelThenCaptureSnapshotInScope(
                        scope,
                        job.firstParent().entityUuid(),
                        job.firstParent().profileId(),
                        COOP_CAPTURE,
                        () -> {
                            snapshots.incrementAndGet();
                            return "unsafe";
                        }
                );
        BreedingCaptureCancellationService.SnapshotHandoff<String> retry =
                service.cancelThenCaptureSnapshotInScope(
                        scope,
                        job.firstParent().entityUuid(),
                        job.firstParent().profileId(),
                        COOP_CAPTURE,
                        () -> {
                            snapshots.incrementAndGet();
                            return "unsafe";
                        }
                );

        assertEquals(DURABILITY_FAILED, first.cancellation().status());
        assertEquals(DURABILITY_FAILED, retry.cancellation().status());
        assertNull(first.snapshot());
        assertNull(retry.snapshot());
        assertEquals(0, snapshots.get());
    }

    @Test
    void terminalJobStillFindsPendingParentGateAndRetainsItAcrossRetry() throws Exception {
        BreedingBirthJobRegistry registry = new BreedingBirthJobRegistry();
        Object scope = new Object();
        UUID parentUuid = uuid(290L);
        CompletableFuture<Boolean> retainedGate = new CompletableFuture<>();
        AtomicInteger parentLookups = new AtomicInteger();
        AtomicInteger snapshots = new AtomicInteger();
        BreedingCaptureCancellationService.PreparedCancellationGateway prepared =
                new BreedingCaptureCancellationService.PreparedCancellationGateway() {
                    @Override
                    public CompletableFuture<Boolean> cancel(
                            Object ignoredScope, UUID ignoredJobId, String ignoredReason) {
                        throw new AssertionError("terminal job must use its parent gate");
                    }

                    @Override
                    public CompletableFuture<Boolean> cancelByParent(
                            Object ignoredScope,
                            UUID ignoredParentUuid,
                            String ignoredProfileId,
                            String ignoredReason) {
                        parentLookups.incrementAndGet();
                        return retainedGate;
                    }
                };
        BreedingCaptureCancellationService service = new BreedingCaptureCancellationService(
                registry,
                prepared,
                (ignoredScope, ignoredJob, ignoredFirst, ignoredLiveUuid) ->
                        new BreedingCaptureCancellationService.ParentRollbackOutcome(RESTORED, true),
                null
        );

        BreedingCaptureCancellationService.SnapshotHandoff<String> pending =
                service.cancelThenCaptureSnapshotInScope(
                        scope, parentUuid, null, COOP_CAPTURE,
                        () -> {
                            snapshots.incrementAndGet();
                            return "unsafe";
                        }
                );
        assertEquals(DURABILITY_PENDING, pending.cancellation().status());
        assertNull(pending.snapshot());
        assertEquals(1, parentLookups.get());

        retainedGate.complete(true);
        BreedingCaptureCancellationService.SnapshotHandoff<String> retry =
                service.cancelThenCaptureSnapshotInScope(
                        scope, parentUuid, null, COOP_CAPTURE,
                        () -> {
                            snapshots.incrementAndGet();
                            return "snapshot";
                        }
                );

        assertEquals(ALREADY_TERMINAL, retry.cancellation().status());
        assertEquals("snapshot", retry.snapshot());
        assertEquals(1, snapshots.get());
        assertEquals(2, parentLookups.get());
    }

    @Test
    void terminalJobWithFailedParentGateRemainsUnsafeForever() {
        BreedingBirthJobRegistry registry = new BreedingBirthJobRegistry();
        Object scope = new Object();
        UUID parentUuid = uuid(295L);
        BreedingCaptureCancellationService.PreparedCancellationGateway prepared =
                new BreedingCaptureCancellationService.PreparedCancellationGateway() {
                    @Override
                    public CompletableFuture<Boolean> cancel(
                            Object ignoredScope, UUID ignoredJobId, String ignoredReason) {
                        throw new AssertionError("terminal job must use its parent gate");
                    }

                    @Override
                    public CompletableFuture<Boolean> cancelByParent(
                            Object ignoredScope,
                            UUID ignoredParentUuid,
                            String ignoredProfileId,
                            String ignoredReason) {
                        return CompletableFuture.completedFuture(false);
                    }
                };
        BreedingCaptureCancellationService service = new BreedingCaptureCancellationService(
                registry,
                prepared,
                (ignoredScope, ignoredJob, ignoredFirst, ignoredLiveUuid) ->
                        new BreedingCaptureCancellationService.ParentRollbackOutcome(RESTORED, true),
                null
        );

        BreedingCaptureCancellationService.SnapshotHandoff<String> first =
                service.cancelThenCaptureSnapshotInScope(
                        scope, parentUuid, null, COOP_CAPTURE, () -> "unsafe"
                );
        BreedingCaptureCancellationService.SnapshotHandoff<String> retry =
                service.cancelThenCaptureSnapshotInScope(
                        scope, parentUuid, null, COOP_CAPTURE, () -> "unsafe"
                );

        assertEquals(DURABILITY_FAILED, first.cancellation().status());
        assertEquals(DURABILITY_FAILED, retry.cancellation().status());
        assertNull(first.snapshot());
        assertNull(retry.snapshot());
    }

    @Test
    void newerActiveJobCannotHideAnOlderFailedCaptureAttempt() {
        BreedingBirthJobRegistry registry = new BreedingBirthJobRegistry();
        Object scope = new Object();
        AtomicReference<CompletableFuture<Boolean>> retained = new AtomicReference<>(
                CompletableFuture.completedFuture(false)
        );
        BreedingCaptureCancellationService.PreparedCancellationGateway prepared =
                new BreedingCaptureCancellationService.PreparedCancellationGateway() {
                    @Override
                    public CompletableFuture<Boolean> cancel(
                            Object ignoredScope, UUID ignoredJobId, String ignoredReason) {
                        return CompletableFuture.completedFuture(true);
                    }

                    @Override
                    public CompletableFuture<Boolean> cancelByParent(
                            Object ignoredScope,
                            UUID ignoredParentUuid,
                            String ignoredProfileId,
                            String ignoredReason) {
                        return retained.get();
                    }
                };
        BreedingCaptureCancellationService service = new BreedingCaptureCancellationService(
                registry,
                prepared,
                (ignoredScope, ignoredJob, ignoredFirst, ignoredLiveUuid) ->
                        new BreedingCaptureCancellationService.ParentRollbackOutcome(RESTORED, true),
                null
        );
        UUID parentUuid = uuid(1L);

        assertEquals(DURABILITY_FAILED, service.cancelForCapturedParentInScope(
                scope, parentUuid, "profile-a", COOP_CAPTURE
        ).status());
        retained.set(CompletableFuture.completedFuture(true));
        assertEquals(BreedingBirthJobRegistry.AdmissionStatus.ACCEPTED,
                registry.register(scope, job(296L)).status());

        BreedingCaptureCancellationService.CancellationResult newer =
                service.cancelForCapturedParentInScope(
                        scope, parentUuid, "profile-a", COOP_CAPTURE
                );

        assertEquals(DURABILITY_FAILED, newer.status());
        assertFalse(newer.safeToCapture());
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
