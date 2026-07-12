package com.alechilles.alecstamework.npc.breeding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

import static com.alechilles.alecstamework.npc.breeding.BreedingJobExecutionService.ExecutionStatus.CAPACITY_REJECTED;
import static com.alechilles.alecstamework.npc.breeding.BreedingJobExecutionService.ExecutionStatus.COMPLETED;
import static com.alechilles.alecstamework.npc.breeding.BreedingJobExecutionService.ExecutionStatus.FAILED;
import static com.alechilles.alecstamework.npc.breeding.BreedingJobExecutionService.ExecutionStatus.HEARTS_SHOWN;
import static com.alechilles.alecstamework.npc.breeding.BreedingJobExecutionService.ExecutionStatus.PARENTS_INVALID;
import static com.alechilles.alecstamework.npc.breeding.BreedingPopulationAdmissionService.BreedingMode.PASSIVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for job-ID-only delayed execution and cooldown outcome policy. */
class BreedingJobExecutionServiceTest {
    private static final String WORLD = "world-a";
    private static final BreedingBirthAnchor ANCHOR = new BreedingBirthAnchor(0.0, 64.0, 0.0);

    @Test
    void resolvesFreshStatePerStageShrinksAdmissionAndDuplicateCallbackCannotRespawn() {
        Fixture fixture = fixture(plan(2), scope(), 1, Map.of(), index -> true, true);

        BreedingJobExecutionService.ExecutionResult hearts = fixture.execution.execute(fixture.job.jobId());
        BreedingJobExecutionService.ExecutionResult completed = fixture.execution.execute(fixture.job.jobId());
        BreedingJobExecutionService.ExecutionResult replay = fixture.execution.execute(fixture.job.jobId());

        assertEquals(HEARTS_SHOWN, hearts.status());
        assertEquals(List.of(fixture.job.jobId()), fixture.scheduledJobIds);
        assertEquals(COMPLETED, completed.status());
        assertEquals(1, completed.spawnedChildren());
        assertEquals(2, fixture.runtime.resolveCalls.get());
        assertEquals(1, fixture.runtime.spawnCalls.get());
        assertEquals(0, fixture.runtime.rollbackCalls.get());
        assertEquals(BreedingJobExecutionService.ExecutionStatus.TERMINAL, replay.status());
        assertEquals(1, fixture.runtime.spawnCalls.get());
        BreedingJobDiagnosticSnapshot diagnostics = fixture.services.jobDiagnostics()
                .find(fixture.job.jobId())
                .orElseThrow();
        assertEquals(BreedingJobDiagnosticSnapshot.Outcome.COMPLETED, diagnostics.outcome());
        assertEquals(1, diagnostics.spawnedChildren());
        assertTrue(diagnostics.spawnedCountFinal());
        assertEquals(1, diagnostics.spawnCapacity().admittedChildren());
        assertTrue(diagnostics.spawnCapacity().liveNearbyByPopulationType().isEmpty());
    }

    @Test
    void missingOrMismatchedParentsCancelAndRollbackRestorableState() {
        Fixture fixture = fixture(plan(1), scope(), 8, Map.of(), index -> true, false);

        BreedingJobExecutionService.ExecutionResult result = fixture.execution.execute(fixture.job.jobId());

        assertEquals(PARENTS_INVALID, result.status());
        assertEquals(BreedingBirthJobState.CANCELLED, result.job().orElseThrow().state());
        assertEquals(1, fixture.runtime.rollbackCalls.get());
        assertEquals(0, fixture.runtime.spawnCalls.get());
        BreedingJobDiagnosticSnapshot diagnostics = fixture.services.jobDiagnostics()
                .find(fixture.job.jobId())
                .orElseThrow();
        assertEquals(BreedingJobDiagnosticSnapshot.Outcome.PARENTS_INVALID, diagnostics.outcome());
        assertEquals("missing", diagnostics.reason());
        assertEquals(
                BreedingJobDiagnosticSnapshot.RollbackStatus.ATTEMPTED,
                diagnostics.rollbackStatus()
        );
    }

    @Test
    void spawnTimeCapacityZeroRollsBackAndCancels() {
        Fixture fixture = fixture(plan(1), scope(), 1, Map.of("cattle", 1), index -> true, true);
        fixture.execution.execute(fixture.job.jobId());

        BreedingJobExecutionService.ExecutionResult result = fixture.execution.execute(fixture.job.jobId());

        assertEquals(CAPACITY_REJECTED, result.status());
        assertEquals(BreedingBirthJobState.CANCELLED, result.job().orElseThrow().state());
        assertEquals(1, fixture.runtime.rollbackCalls.get());
        assertEquals(0, fixture.runtime.spawnCalls.get());
    }

    @Test
    void allSpawnFailuresRollbackButPartialSuccessRetainsCooldown() {
        Fixture failed = fixture(plan(2), scope(), 8, Map.of(), index -> false, true);
        failed.execution.execute(failed.job.jobId());
        BreedingJobExecutionService.ExecutionResult failedResult =
                failed.execution.execute(failed.job.jobId());

        Fixture partial = fixture(plan(2), scope(), 8, Map.of(), index -> index == 0, true);
        partial.execution.execute(partial.job.jobId());
        BreedingJobExecutionService.ExecutionResult partialResult =
                partial.execution.execute(partial.job.jobId());

        assertEquals(FAILED, failedResult.status());
        assertEquals(1, failed.runtime.rollbackCalls.get());
        BreedingJobDiagnosticSnapshot failedDiagnostics = failed.services.jobDiagnostics()
                .find(failed.job.jobId())
                .orElseThrow();
        assertEquals(0, failedDiagnostics.spawnedChildren());
        assertEquals("all-child-spawns-failed=2", failedDiagnostics.reason());
        assertEquals(COMPLETED, partialResult.status());
        assertEquals(1, partialResult.spawnedChildren());
        assertEquals(0, partial.runtime.rollbackCalls.get());
        BreedingJobDiagnosticSnapshot partialDiagnostics = partial.services.jobDiagnostics()
                .find(partial.job.jobId())
                .orElseThrow();
        assertEquals(1, partialDiagnostics.spawnedChildren());
        assertEquals("child-spawn-failures=1", partialDiagnostics.reason());
    }

    @Test
    void naturalZeroCompletesAndRetainsCooldown() {
        Fixture fixture = fixture(BreedingBirthPlan.of(List.of()), BreedingReservationScope.unscoped(),
                0, Map.of(), index -> false, true);
        fixture.execution.execute(fixture.job.jobId());

        BreedingJobExecutionService.ExecutionResult result = fixture.execution.execute(fixture.job.jobId());

        assertEquals(COMPLETED, result.status());
        assertEquals(0, result.spawnedChildren());
        assertEquals(0, fixture.runtime.spawnCalls.get());
        assertEquals(0, fixture.runtime.rollbackCalls.get());
    }

    @Test
    void postCompletionFailureKeepsExactSuccessfulBirthDiagnostic() {
        Fixture fixture = fixture(plan(1), scope(), 8, Map.of(), index -> true, true);
        fixture.runtime.throwOnCompleted = true;
        fixture.execution.execute(fixture.job.jobId());

        BreedingJobExecutionService.ExecutionResult result =
                fixture.execution.execute(fixture.job.jobId());

        assertEquals(COMPLETED, result.status());
        assertEquals(1, result.spawnedChildren());
        BreedingJobDiagnosticSnapshot diagnostics = fixture.services.jobDiagnostics()
                .find(fixture.job.jobId())
                .orElseThrow();
        assertEquals(BreedingJobDiagnosticSnapshot.Outcome.COMPLETED, diagnostics.outcome());
        assertEquals(1, diagnostics.spawnedChildren());
        assertEquals(
                "post-completion-follow-up-error:IllegalStateException",
                diagnostics.reason()
        );
        assertEquals(0, fixture.runtime.rollbackCalls.get());
    }

    @Test
    void parentResolutionOrHeartEffectFailureRollsBackAndFailsJob() {
        Fixture resolutionFailure = fixture(plan(1), scope(), 8, Map.of(), index -> true, true);
        resolutionFailure.runtime.throwOnResolve = true;

        BreedingJobExecutionService.ExecutionResult resolutionResult =
                resolutionFailure.execution.execute(resolutionFailure.job.jobId());

        Fixture heartFailure = fixture(plan(1), scope(), 8, Map.of(), index -> true, true);
        heartFailure.runtime.throwOnHearts = true;

        BreedingJobExecutionService.ExecutionResult heartResult =
                heartFailure.execution.execute(heartFailure.job.jobId());

        assertEquals(FAILED, resolutionResult.status());
        assertEquals(BreedingBirthJobState.FAILED, resolutionResult.job().orElseThrow().state());
        assertEquals(1, resolutionFailure.runtime.rollbackCalls.get());
        assertEquals(FAILED, heartResult.status());
        assertEquals(BreedingBirthJobState.FAILED, heartResult.job().orElseThrow().state());
        assertEquals(1, heartFailure.runtime.rollbackCalls.get());
    }

    @Test
    void asynchronousSchedulingFailureCallbackRollsBackAndFailsJob() {
        Fixture fixture = fixture(plan(1), scope(), 8, Map.of(), index -> true, true);
        fixture.runtime.throwOnRollback = true;

        BreedingJobExecutionService.ExecutionResult result =
                fixture.execution.failScheduledJob(fixture.job.jobId());

        assertEquals(FAILED, result.status());
        assertEquals(BreedingBirthJobState.FAILED, result.job().orElseThrow().state());
        assertEquals(1, fixture.runtime.rollbackCalls.get());
        assertEquals(0, fixture.runtime.spawnCalls.get());
        assertEquals(
                BreedingJobDiagnosticSnapshot.RollbackStatus.FAILED,
                fixture.services.jobDiagnostics()
                        .find(fixture.job.jobId())
                        .orElseThrow()
                        .rollbackStatus()
        );
    }

    private static Fixture fixture(BreedingBirthPlan plan,
                                   BreedingReservationScope scope,
                                   int maxNearby,
                                   Map<String, Integer> live,
                                   IntPredicate spawnResult,
                                   boolean parentsValid) {
        TameworkBreedingServices services = new TameworkBreedingServices(() -> 0.25);
        Object storeScope = new Object();
        UUID jobId = uuid(System.identityHashCode(services));
        BreedingJobAdmission admission = BreedingJobAdmission.of(plan.children(), scope);
        BreedingBirthJob job = BreedingBirthJob.reserved(
                jobId,
                WORLD,
                parent(1L, "profile-a"),
                parent(2L, "profile-b"),
                PASSIVE,
                plan,
                admission,
                ParentBreedingSnapshot.empty(),
                ParentBreedingSnapshot.empty(),
                AppliedCooldownFingerprint.none(),
                AppliedCooldownFingerprint.none(),
                ANCHOR
        );
        services.jobRegistry().register(storeScope, job);
        services.jobDiagnostics().register(storeScope, job);
        BreedingPopulationAdmissionService.AdmissionRequest initialRequest =
                new BreedingPopulationAdmissionService.AdmissionRequest(
                        jobId,
                        WORLD,
                        PASSIVE,
                        plan,
                        ANCHOR,
                        scope,
                        0,
                        Map.of(),
                        BreedingCapacityHeadroom.unlimited()
                );
        services.jobDiagnostics().recordInitialAdmission(
                jobId,
                initialRequest,
                services.populationAdmissionService().admit(initialRequest)
        );
        services.jobRegistry().advance(
                storeScope,
                jobId,
                BreedingBirthJobState.RESERVED,
                BreedingBirthJobState.APPROACHING
        );
        FakeRuntime runtime = new FakeRuntime(
                storeScope,
                scope,
                maxNearby,
                live,
                spawnResult,
                parentsValid
        );
        ArrayList<UUID> scheduled = new ArrayList<>();
        BreedingJobExecutionService<String> execution = new BreedingJobExecutionService<>(
                services,
                runtime,
                (scheduledJobId, delay) -> scheduled.add(scheduledJobId),
                5L
        );
        return new Fixture(services, job, execution, runtime, scheduled);
    }

    private static BreedingBirthPlan plan(int children) {
        return BreedingBirthPlan.of(java.util.stream.IntStream.range(0, children)
                .mapToObj(index -> new PlannedChild(
                        "baby-" + index,
                        "adult",
                        "Female",
                        "family",
                        "cattle"
                ))
                .toList());
    }

    private static BreedingReservationScope scope() {
        return new BreedingReservationScope(10.0, null, List.of());
    }

    private static BreedingParentIdentity parent(long value, String profile) {
        return new BreedingParentIdentity(uuid(value), profile);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }

    private record Fixture(TameworkBreedingServices services,
                           BreedingBirthJob job,
                           BreedingJobExecutionService<String> execution,
                           FakeRuntime runtime,
                           List<UUID> scheduledJobIds) {
    }

    private static final class FakeRuntime implements BreedingJobExecutionService.Runtime<String> {
        private final Object storeScope;
        private final BreedingReservationScope reservationScope;
        private final int maxNearby;
        private final Map<String, Integer> live;
        private final IntPredicate spawnResult;
        private final boolean parentsValid;
        private final AtomicInteger resolveCalls = new AtomicInteger();
        private final AtomicInteger spawnCalls = new AtomicInteger();
        private final AtomicInteger rollbackCalls = new AtomicInteger();
        private boolean throwOnResolve;
        private boolean throwOnHearts;
        private boolean throwOnCompleted;
        private boolean throwOnRollback;

        private FakeRuntime(Object storeScope,
                            BreedingReservationScope reservationScope,
                            int maxNearby,
                            Map<String, Integer> live,
                            IntPredicate spawnResult,
                            boolean parentsValid) {
            this.storeScope = storeScope;
            this.reservationScope = reservationScope;
            this.maxNearby = maxNearby;
            this.live = live;
            this.spawnResult = spawnResult;
            this.parentsValid = parentsValid;
        }

        @Override
        public BreedingJobExecutionService.ParentResolution<String> resolveParents(BreedingBirthJob job) {
            resolveCalls.incrementAndGet();
            if (throwOnResolve) {
                throw new IllegalStateException("parent resolution failed");
            }
            return parentsValid
                    ? BreedingJobExecutionService.ParentResolution.valid(storeScope, "current-parents")
                    : BreedingJobExecutionService.ParentResolution.invalid("missing");
        }

        @Override
        public void showHearts(BreedingBirthJob job, String context) {
            if (throwOnHearts) {
                throw new IllegalStateException("heart effect failed");
            }
        }

        @Override
        public BreedingPopulationAdmissionService.AdmissionRequest buildSpawnAdmissionRequest(
                BreedingBirthJob job,
                String context) {
            return new BreedingPopulationAdmissionService.AdmissionRequest(
                    job.jobId(),
                    WORLD,
                    job.mode(),
                    job.plan(),
                    ANCHOR,
                    reservationScope,
                    maxNearby,
                    live,
                    BreedingCapacityHeadroom.unlimited()
            );
        }

        @Override
        public boolean spawnChild(BreedingBirthJob job,
                                  PlannedChild child,
                                  int childIndex,
                                  String context) {
            spawnCalls.incrementAndGet();
            return spawnResult.test(childIndex);
        }

        @Override
        public void onCompleted(BreedingBirthJob job, int spawnedChildren, String context) {
            if (throwOnCompleted) {
                throw new IllegalStateException("follow-up failed");
            }
        }

        @Override
        public void rollbackProvisionalCooldown(BreedingBirthJob job) {
            rollbackCalls.incrementAndGet();
            if (throwOnRollback) {
                throw new IllegalStateException("rollback failed");
            }
        }
    }
}
