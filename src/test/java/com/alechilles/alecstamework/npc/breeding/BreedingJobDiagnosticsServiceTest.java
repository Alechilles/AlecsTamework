package com.alechilles.alecstamework.npc.breeding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.alechilles.alecstamework.npc.breeding.BreedingJobDiagnosticSnapshot.Outcome.COMPLETED;
import static com.alechilles.alecstamework.npc.breeding.BreedingJobDiagnosticSnapshot.RollbackStatus.NOT_ATTEMPTED;
import static com.alechilles.alecstamework.npc.breeding.BreedingPopulationAdmissionService.BreedingMode.PASSIVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for process-local exact birth outcome and capacity diagnostics. */
class BreedingJobDiagnosticsServiceTest {
    private static final String WORLD = "world-a";
    private static final BreedingBirthAnchor ANCHOR = new BreedingBirthAnchor(1.0, 64.0, 2.0);

    @Test
    void retainsInitialAndSpawnCapacityWithExactTerminalSpawnCount() {
        BreedingBirthJobRegistry registry = new BreedingBirthJobRegistry();
        BreedingPopulationAdmissionService admissionService =
                new BreedingPopulationAdmissionService(registry);
        BreedingJobDiagnosticsService diagnostics = new BreedingJobDiagnosticsService();
        Object scope = new Object();
        BreedingBirthPlan plan = plan(2);
        BreedingPopulationAdmissionService.AdmissionRequest initialRequest = request(
                uuid(100L),
                plan,
                4,
                Map.of("cattle", 1),
                2
        );
        BreedingPopulationAdmissionService.AdmissionResult initial =
                admissionService.admit(initialRequest);
        BreedingBirthJob job = job(initialRequest.jobId(), plan, initial.admission());

        assertTrue(diagnostics.register(scope, job));
        assertTrue(diagnostics.recordInitialAdmission(job.jobId(), initialRequest, initial));

        BreedingPopulationAdmissionService.AdmissionRequest spawnRequest = request(
                job.jobId(),
                plan,
                2,
                Map.of("cattle", 1),
                1
        );
        BreedingPopulationAdmissionService.AdmissionResult spawn =
                admissionService.recheckAtSpawn(spawnRequest, job.initialAdmission());
        assertTrue(diagnostics.recordSpawnRecheck(job.jobId(), spawnRequest, spawn));
        assertTrue(diagnostics.recordOutcome(
                job.jobId(),
                COMPLETED,
                1,
                "child-spawn-failures=1",
                NOT_ATTEMPTED,
                null
        ));

        BreedingJobDiagnosticSnapshot snapshot = diagnostics
                .findLatestByParentUuid(scope, job.firstParent().entityUuid())
                .orElseThrow();
        assertEquals(job.jobId(), snapshot.jobId());
        assertEquals(2, snapshot.initialCapacity().admittedChildren());
        assertEquals(1, snapshot.initialCapacity().liveNearbyByPopulationType().get("cattle"));
        assertEquals(2, snapshot.initialCapacity().combinedTotalHeadroom());
        assertEquals(1, snapshot.spawnCapacity().admittedChildren());
        assertEquals(1, snapshot.spawnCapacity().combinedTotalHeadroom());
        assertTrue(snapshot.spawnedCountFinal());
        assertEquals(1, snapshot.spawnedChildren());
        assertEquals(COMPLETED, snapshot.outcome());
        assertEquals("child-spawn-failures=1", snapshot.reason());
    }

    @Test
    void newestJobReplacesParentLookupAndScopeCleanupDropsHistory() {
        BreedingJobDiagnosticsService diagnostics = new BreedingJobDiagnosticsService();
        Object scope = new Object();
        BreedingBirthPlan plan = plan(1);
        BreedingBirthJob first = job(
                uuid(200L),
                plan,
                BreedingJobAdmission.of(plan.children(), BreedingReservationScope.unscoped())
        );
        BreedingBirthJob second = job(
                uuid(201L),
                plan,
                BreedingJobAdmission.of(plan.children(), BreedingReservationScope.unscoped())
        );

        assertTrue(diagnostics.register(scope, first));
        assertTrue(diagnostics.register(scope, second));
        assertEquals(
                second.jobId(),
                diagnostics.findLatestByParentUuid(scope, second.firstParent().entityUuid())
                        .orElseThrow()
                        .jobId()
        );

        diagnostics.clearScope(scope);

        assertFalse(diagnostics.find(first.jobId()).isPresent());
        assertFalse(diagnostics.find(second.jobId()).isPresent());
        assertFalse(diagnostics.findLatestByParentUuid(
                scope,
                second.firstParent().entityUuid()
        ).isPresent());
    }

    @Test
    void supersededTerminalHistoryIsPrunedOnlyAfterNeitherParentReferencesIt() {
        BreedingJobDiagnosticsService diagnostics = new BreedingJobDiagnosticsService();
        Object scope = new Object();
        BreedingBirthJob first = job(uuid(300L), uuid(10L), uuid(11L));
        BreedingBirthJob sharesFirst = job(uuid(301L), uuid(10L), uuid(12L));
        BreedingBirthJob replacesSecond = job(uuid(302L), uuid(11L), uuid(13L));

        diagnostics.register(scope, first);
        diagnostics.recordOutcome(first.jobId(), COMPLETED, 1, null, NOT_ATTEMPTED, null);
        diagnostics.register(scope, sharesFirst);

        assertTrue(diagnostics.find(first.jobId()).isPresent(),
                "the former partner still references the first terminal job");

        diagnostics.register(scope, replacesSecond);

        assertFalse(diagnostics.find(first.jobId()).isPresent(),
                "a terminal job with no latest-parent reference should be pruned");
        assertTrue(diagnostics.find(sharesFirst.jobId()).isPresent());
        assertTrue(diagnostics.find(replacesSecond.jobId()).isPresent());
    }

    @Test
    void terminalHistoryCapIsDeterministicAndNeverDropsActiveJobs() {
        BreedingJobDiagnosticsService diagnostics = new BreedingJobDiagnosticsService();
        Object scope = new Object();
        BreedingBirthJob active = job(uuid(400L), uuid(20L), uuid(21L));
        diagnostics.register(scope, active);
        ArrayList<UUID> terminalJobIds = new ArrayList<>();
        int totalTerminal = BreedingJobDiagnosticsService.MAX_TERMINAL_SNAPSHOTS_PER_SCOPE + 5;
        for (int index = 0; index < totalTerminal; index++) {
            UUID jobId = uuid(1_000L + index);
            BreedingBirthJob terminal = job(
                    jobId,
                    uuid(10_000L + index * 2L),
                    uuid(10_001L + index * 2L)
            );
            diagnostics.register(scope, terminal);
            diagnostics.recordOutcome(jobId, COMPLETED, 1, null, NOT_ATTEMPTED, null);
            terminalJobIds.add(jobId);
        }

        assertTrue(diagnostics.find(active.jobId()).isPresent());
        for (int index = 0; index < 5; index++) {
            assertFalse(diagnostics.find(terminalJobIds.get(index)).isPresent());
        }
        int retained = 0;
        for (UUID jobId : terminalJobIds) {
            if (diagnostics.find(jobId).isPresent()) {
                retained++;
            }
        }
        assertEquals(BreedingJobDiagnosticsService.MAX_TERMINAL_SNAPSHOTS_PER_SCOPE, retained);
        assertTrue(diagnostics.find(terminalJobIds.getLast()).isPresent());
    }

    private static BreedingPopulationAdmissionService.AdmissionRequest request(
            UUID jobId,
            BreedingBirthPlan plan,
            int maxNearby,
            Map<String, Integer> liveNearby,
            int playerHeadroom) {
        BreedingPlayerCapacityScope playerScope =
                BreedingPlayerCapacityScope.perWorld(WORLD, uuid(900L));
        BreedingReservationScope reservationScope = new BreedingReservationScope(
                10.0,
                null,
                List.of(playerScope)
        );
        return new BreedingPopulationAdmissionService.AdmissionRequest(
                jobId,
                WORLD,
                PASSIVE,
                plan,
                ANCHOR,
                reservationScope,
                maxNearby,
                liveNearby,
                new BreedingCapacityHeadroom(
                        OptionalInt.empty(),
                        Map.of(playerScope, playerHeadroom)
                )
        );
    }

    private static BreedingBirthJob job(UUID jobId,
                                        BreedingBirthPlan plan,
                                        BreedingJobAdmission admission) {
        return BreedingBirthJob.reserved(
                jobId,
                WORLD,
                new BreedingParentIdentity(uuid(1L), "profile-a"),
                new BreedingParentIdentity(uuid(2L), "profile-b"),
                PASSIVE,
                plan,
                admission,
                ParentBreedingSnapshot.empty(),
                ParentBreedingSnapshot.empty(),
                AppliedCooldownFingerprint.none(),
                AppliedCooldownFingerprint.none(),
                ANCHOR
        );
    }

    private static BreedingBirthJob job(UUID jobId, UUID firstParent, UUID secondParent) {
        BreedingBirthPlan plan = plan(1);
        return BreedingBirthJob.reserved(
                jobId,
                WORLD,
                new BreedingParentIdentity(firstParent, "profile-" + firstParent),
                new BreedingParentIdentity(secondParent, "profile-" + secondParent),
                PASSIVE,
                plan,
                BreedingJobAdmission.of(plan.children(), BreedingReservationScope.unscoped()),
                ParentBreedingSnapshot.empty(),
                ParentBreedingSnapshot.empty(),
                AppliedCooldownFingerprint.none(),
                AppliedCooldownFingerprint.none(),
                ANCHOR
        );
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

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
