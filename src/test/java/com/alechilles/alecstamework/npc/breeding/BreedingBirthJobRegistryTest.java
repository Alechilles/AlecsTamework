package com.alechilles.alecstamework.npc.breeding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static com.alechilles.alecstamework.npc.breeding.BreedingBirthJobRegistry.AdmissionStatus.ACCEPTED;
import static com.alechilles.alecstamework.npc.breeding.BreedingBirthJobRegistry.AdmissionStatus.ALREADY_REGISTERED;
import static com.alechilles.alecstamework.npc.breeding.BreedingBirthJobRegistry.AdmissionStatus.JOB_ID_CONFLICT;
import static com.alechilles.alecstamework.npc.breeding.BreedingBirthJobRegistry.AdmissionStatus.PARENT_BUSY;
import static com.alechilles.alecstamework.npc.breeding.BreedingBirthJobRegistry.AdmissionStatus.PROFILE_BUSY;
import static com.alechilles.alecstamework.npc.breeding.BreedingBirthJobRegistry.AdmissionUpdateStatus.INVALID_SHRINK;
import static com.alechilles.alecstamework.npc.breeding.BreedingBirthJobRegistry.ReservationReleaseStatus.CHILD_NOT_RESERVED;
import static com.alechilles.alecstamework.npc.breeding.BreedingBirthJobRegistry.ReservationReleaseStatus.RELEASED;
import static com.alechilles.alecstamework.npc.breeding.BreedingBirthJobRegistry.SpawnClaimStatus.ALREADY_CLAIMED;
import static com.alechilles.alecstamework.npc.breeding.BreedingBirthJobRegistry.SpawnClaimStatus.CLAIMED;
import static com.alechilles.alecstamework.npc.breeding.BreedingBirthJobRegistry.SpawnClaimStatus.SCOPE_CLOSED;
import static com.alechilles.alecstamework.npc.breeding.BreedingBirthJobRegistry.SpawnClaimStatus.TERMINAL;
import static com.alechilles.alecstamework.npc.breeding.BreedingBirthJobRegistry.TransitionStatus.APPLIED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for idempotent and cancellation-safe breeding jobs. */
class BreedingBirthJobRegistryTest {
    private static final String WORLD_ID = "test-world";

    @Test
    void rejectsOverlappingEntityAndProfileJobs() {
        BreedingBirthJobRegistry registry = new BreedingBirthJobRegistry();
        Object scope = new Object();
        BreedingParentIdentity parentA = parent(1L, "profile-a");
        BreedingParentIdentity parentB = parent(2L, "profile-b");
        BreedingBirthJob first = job(100L, parentA, parentB);

        assertEquals(ACCEPTED, registry.register(scope, first).status());
        assertEquals(PARENT_BUSY, registry.register(scope, job(
                101L,
                parentA,
                parent(3L, "profile-c")
        )).status());
        assertEquals(PROFILE_BUSY, registry.register(scope, job(
                102L,
                parent(4L, "profile-a"),
                parent(5L, "profile-d")
        )).status());
        assertEquals(1, registry.activeJobCount(scope));
    }

    @Test
    void treatsSameDeterministicJobAsReplayAndDifferentPayloadAsConflict() {
        BreedingBirthJobRegistry registry = new BreedingBirthJobRegistry();
        Object scope = new Object();
        UUID jobId = uuid(100L);
        BreedingBirthJob original = BreedingBirthJob.reserved(
                jobId,
                WORLD_ID,
                parent(1L, "profile-a"),
                parent(2L, "profile-b")
        );

        assertEquals(ACCEPTED, registry.register(scope, original).status());
        assertEquals(ALREADY_REGISTERED, registry.register(scope, original).status());

        BreedingBirthJob conflicting = BreedingBirthJob.reserved(
                jobId,
                WORLD_ID,
                parent(3L, "profile-c"),
                parent(4L, "profile-d")
        );
        assertEquals(JOB_ID_CONFLICT, registry.register(scope, conflicting).status());
    }

    @Test
    @Timeout(10)
    void onlyOneConcurrentCallbackClaimsSpawn() throws Exception {
        BreedingBirthJobRegistry registry = new BreedingBirthJobRegistry();
        Object scope = new Object();
        BreedingBirthJob job = job(100L, parent(1L, "profile-a"), parent(2L, "profile-b"));
        registry.register(scope, job);
        advanceToHearts(registry, scope, job.jobId());

        int callbackCount = 32;
        ExecutorService executor = Executors.newFixedThreadPool(callbackCount);
        CountDownLatch ready = new CountDownLatch(callbackCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<BreedingBirthJobRegistry.SpawnClaimStatus>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < callbackCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return registry.claimSpawn(scope, job.jobId()).status();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int claimed = 0;
            int replayed = 0;
            for (Future<BreedingBirthJobRegistry.SpawnClaimStatus> future : futures) {
                BreedingBirthJobRegistry.SpawnClaimStatus status = future.get(5, TimeUnit.SECONDS);
                claimed += status == CLAIMED ? 1 : 0;
                replayed += status == ALREADY_CLAIMED ? 1 : 0;
            }
            assertEquals(1, claimed);
            assertEquals(callbackCount - 1, replayed);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void completedJobRejectsLateCallbackAndReleasesParents() {
        BreedingBirthJobRegistry registry = new BreedingBirthJobRegistry();
        Object scope = new Object();
        BreedingParentIdentity parentA = parent(1L, "profile-a");
        BreedingBirthJob first = job(100L, parentA, parent(2L, "profile-b"));
        registry.register(scope, first);
        advanceToHearts(registry, scope, first.jobId());
        assertEquals(CLAIMED, registry.claimSpawn(scope, first.jobId()).status());

        BreedingBirthJobRegistry.TerminalResult completed = registry.complete(scope, first.jobId());

        assertEquals(BreedingBirthJobRegistry.TerminalStatus.APPLIED, completed.status());
        assertEquals(BreedingBirthJobState.COMPLETED, completed.job().orElseThrow().state());
        assertEquals(TERMINAL, registry.claimSpawn(scope, first.jobId()).status());
        assertEquals(ALREADY_REGISTERED, registry.register(scope, first).status());
        assertEquals(ACCEPTED, registry.register(scope, job(
                101L,
                parentA,
                parent(3L, "profile-c")
        )).status());
    }

    @Test
    void cancellationByEntityAndProfileReleasesIndexes() {
        BreedingBirthJobRegistry registry = new BreedingBirthJobRegistry();
        Object scope = new Object();
        BreedingParentIdentity parentA = parent(1L, "profile-a");
        BreedingParentIdentity parentB = parent(2L, "profile-b");
        BreedingBirthJob first = job(100L, parentA, parentB);
        registry.register(scope, first);

        BreedingBirthJobRegistry.TerminalResult entityCancellation =
                registry.cancelByParentUuid(scope, parentA.entityUuid());
        assertEquals(BreedingBirthJobRegistry.TerminalStatus.APPLIED, entityCancellation.status());
        assertEquals(BreedingBirthJobState.CANCELLED, entityCancellation.job().orElseThrow().state());
        assertEquals(TERMINAL, registry.claimSpawn(scope, first.jobId()).status());

        BreedingBirthJob second = job(101L, parentA, parent(3L, "profile-c"));
        assertEquals(ACCEPTED, registry.register(scope, second).status());
        BreedingBirthJobRegistry.TerminalResult profileCancellation =
                registry.cancelByProfileId(scope, "  profile-a  ");
        assertEquals(BreedingBirthJobRegistry.TerminalStatus.APPLIED, profileCancellation.status());
        assertEquals(BreedingBirthJobState.CANCELLED, profileCancellation.job().orElseThrow().state());
        assertEquals(0, registry.activeJobCount(scope));
    }

    @Test
    void failedOutcomeIsDistinctAndReleasesParents() {
        BreedingBirthJobRegistry registry = new BreedingBirthJobRegistry();
        Object scope = new Object();
        BreedingParentIdentity parentA = parent(1L, "profile-a");
        BreedingBirthJob first = job(100L, parentA, parent(2L, "profile-b"));
        registry.register(scope, first);
        advanceToHearts(registry, scope, first.jobId());
        assertEquals(CLAIMED, registry.claimSpawn(scope, first.jobId()).status());

        BreedingBirthJobRegistry.TerminalResult failed = registry.fail(scope, first.jobId());

        assertEquals(BreedingBirthJobRegistry.TerminalStatus.APPLIED, failed.status());
        assertEquals(BreedingBirthJobState.FAILED, failed.job().orElseThrow().state());
        assertEquals(ACCEPTED, registry.register(scope, job(
                101L,
                parentA,
                parent(3L, "profile-c")
        )).status());
    }

    @Test
    void scopeCleanupClearsJobsAndRejectsLateWork() {
        BreedingBirthJobRegistry registry = new BreedingBirthJobRegistry();
        Object scope = new Object();
        BreedingBirthJob job = job(100L, parent(1L, "profile-a"), parent(2L, "profile-b"));
        registry.register(scope, job);
        advanceToHearts(registry, scope, job.jobId());

        registry.clearScope(scope);

        assertEquals(0, registry.activeJobCount(scope));
        assertTrue(registry.find(scope, job.jobId()).isEmpty());
        assertEquals(SCOPE_CLOSED, registry.claimSpawn(scope, job.jobId()).status());
        assertEquals(
                BreedingBirthJobRegistry.AdmissionStatus.SCOPE_CLOSED,
                registry.register(scope, job(101L, parent(3L, "profile-c"), parent(4L, "profile-d"))).status()
        );
    }

    @Test
    void globalLocatorRejectsCrossScopeJobIdReuse() {
        BreedingBirthJobRegistry registry = new BreedingBirthJobRegistry();
        Object firstScope = new Object();
        Object secondScope = new Object();
        BreedingBirthJob original = richJob(
                100L,
                parent(1L, "profile-a"),
                parent(2L, "profile-b"),
                "cattle"
        );

        assertEquals(ACCEPTED, registry.register(firstScope, original).status());
        BreedingBirthJobRegistry.LocatedJob located = registry.locate(original.jobId()).orElseThrow();
        assertEquals(WORLD_ID, located.worldId());
        assertEquals(original, located.job());

        BreedingBirthJob conflictingScopeJob = richJob(
                100L,
                parent(3L, "profile-c"),
                parent(4L, "profile-d"),
                "sheep"
        );
        assertEquals(JOB_ID_CONFLICT, registry.register(secondScope, conflictingScopeJob).status());
    }

    @Test
    void activeReservationSnapshotsAreExactImmutableAndDeterministic() {
        BreedingBirthJobRegistry registry = new BreedingBirthJobRegistry();
        Object scope = new Object();
        BreedingBirthJob laterId = richJob(
                200L,
                parent(1L, "profile-a"),
                parent(2L, "profile-b"),
                "cattle",
                "sheep",
                "cattle"
        );
        BreedingBirthJob earlierId = richJob(
                100L,
                parent(3L, "profile-c"),
                parent(4L, "profile-d"),
                "goat"
        );

        registry.register(scope, laterId);
        registry.register(scope, earlierId);
        List<BreedingActiveReservation> reservations = registry.activeReservations();

        assertEquals(List.of(earlierId.jobId(), laterId.jobId()), reservations.stream()
                .map(BreedingActiveReservation::jobId)
                .toList());
        assertEquals(Map.of("cattle", 2, "sheep", 1),
                reservations.get(1).reservation().countsByPopulationType());
        assertThrows(
                UnsupportedOperationException.class,
                () -> reservations.get(1).reservation().countsByPopulationType().put("goat", 1)
        );
        assertThrows(UnsupportedOperationException.class, () -> reservations.add(reservations.getFirst()));
    }

    @Test
    void spawnRecheckCanOnlyShrinkAdmissionAndExactReservation() {
        BreedingBirthJobRegistry registry = new BreedingBirthJobRegistry();
        Object scope = new Object();
        BreedingBirthJob job = richJob(
                100L,
                parent(1L, "profile-a"),
                parent(2L, "profile-b"),
                "cattle",
                "sheep",
                "cattle"
        );
        registry.register(scope, job);
        List<PlannedChild> retained = List.of(job.plan().children().get(1), job.plan().children().get(2));

        BreedingBirthJobRegistry.AdmissionUpdateResult shrink =
                registry.shrinkAdmission(scope, job.jobId(), retained);

        assertEquals(BreedingBirthJobRegistry.AdmissionUpdateStatus.APPLIED, shrink.status());
        assertEquals(retained, shrink.job().orElseThrow().admittedChildren());
        assertEquals(Map.of("cattle", 1, "sheep", 1),
                shrink.job().orElseThrow().reservation().countsByPopulationType());
        assertEquals(INVALID_SHRINK, registry.shrinkAdmission(
                scope,
                job.jobId(),
                List.of(job.plan().children().get(2), job.plan().children().get(1))
        ).status());
        assertEquals(INVALID_SHRINK, registry.shrinkAdmission(
                scope,
                job.jobId(),
                job.plan().children()
        ).status());
    }

    @Test
    void releasesOneExactReservationForEachProcessedChild() {
        BreedingBirthJobRegistry registry = new BreedingBirthJobRegistry();
        Object scope = new Object();
        BreedingBirthJob job = richJob(
                100L,
                parent(1L, "profile-a"),
                parent(2L, "profile-b"),
                "cattle",
                "sheep",
                "cattle"
        );
        registry.register(scope, job);
        PlannedChild cattle = job.plan().children().getFirst();

        BreedingBirthJobRegistry.ReservationReleaseResult first =
                registry.releaseChildReservation(scope, job.jobId(), cattle);
        BreedingBirthJobRegistry.ReservationReleaseResult second =
                registry.releaseChildReservation(scope, job.jobId(), cattle);

        assertEquals(RELEASED, first.status());
        assertEquals(Map.of("cattle", 1, "sheep", 1),
                first.job().orElseThrow().reservation().countsByPopulationType());
        assertEquals(RELEASED, second.status());
        assertEquals(Map.of("sheep", 1), second.job().orElseThrow().reservation().countsByPopulationType());
        assertEquals(CHILD_NOT_RESERVED,
                registry.releaseChildReservation(scope, job.jobId(), cattle).status());
    }

    @Test
    void terminalReplayUsesInitialAdmissionAfterReservationsAreReleased() {
        BreedingBirthJobRegistry registry = new BreedingBirthJobRegistry();
        Object scope = new Object();
        BreedingBirthJob job = richJob(
                100L,
                parent(1L, "profile-a"),
                parent(2L, "profile-b"),
                "cattle"
        );
        registry.register(scope, job);
        advanceToHearts(registry, scope, job.jobId());
        registry.claimSpawn(scope, job.jobId());
        registry.releaseChildReservation(scope, job.jobId(), job.plan().children().getFirst());

        assertEquals(BreedingBirthJobRegistry.TerminalStatus.APPLIED,
                registry.complete(scope, job.jobId()).status());
        assertEquals(ALREADY_REGISTERED, registry.register(scope, job).status());
        assertTrue(registry.locate(job.jobId()).orElseThrow().job().reservation().isEmpty());
    }

    @Test
    void scopeAndGlobalCleanupReleaseLocatorsAndReservations() {
        BreedingBirthJobRegistry registry = new BreedingBirthJobRegistry();
        Object firstScope = new Object();
        Object secondScope = new Object();
        BreedingBirthJob first = richJob(
                100L,
                parent(1L, "profile-a"),
                parent(2L, "profile-b"),
                "cattle"
        );
        BreedingBirthJob second = richJob(
                200L,
                parent(3L, "profile-c"),
                parent(4L, "profile-d"),
                "sheep"
        );
        registry.register(firstScope, first);
        registry.register(secondScope, second);

        registry.clearScope(firstScope);

        assertTrue(registry.locate(first.jobId()).isEmpty());
        assertTrue(registry.locate(second.jobId()).isPresent());
        assertEquals(List.of(second.jobId()), registry.activeReservations().stream()
                .map(BreedingActiveReservation::jobId)
                .toList());

        registry.clearAll();

        assertTrue(registry.locate(second.jobId()).isEmpty());
        assertTrue(registry.activeReservations().isEmpty());
        assertEquals(SCOPE_CLOSED, registry.claimSpawn(secondScope, second.jobId()).status());
        assertEquals(BreedingBirthJobRegistry.AdmissionStatus.SCOPE_CLOSED,
                registry.register(secondScope, richJob(
                        300L,
                        parent(5L, "profile-e"),
                        parent(6L, "profile-f"),
                        "goat"
                )).status());
    }

    @Test
    @Timeout(10)
    void overlappingConcurrentRegistrationsInstallOnlyOneParentReservation() throws Exception {
        BreedingBirthJobRegistry registry = new BreedingBirthJobRegistry();
        Object scope = new Object();
        BreedingParentIdentity sharedParent = parent(1L, "profile-a");
        int contenderCount = 16;
        ExecutorService executor = Executors.newFixedThreadPool(contenderCount);
        CountDownLatch ready = new CountDownLatch(contenderCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<BreedingBirthJobRegistry.AdmissionStatus>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < contenderCount; i++) {
                long id = 100L + i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return registry.register(scope, richJob(
                            id,
                            sharedParent,
                            parent(1_000L + id, "profile-" + id),
                            "cattle"
                    )).status();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int accepted = 0;
            int parentBusy = 0;
            for (Future<BreedingBirthJobRegistry.AdmissionStatus> future : futures) {
                BreedingBirthJobRegistry.AdmissionStatus status = future.get(5, TimeUnit.SECONDS);
                accepted += status == ACCEPTED ? 1 : 0;
                parentBusy += status == PARENT_BUSY ? 1 : 0;
            }
            assertEquals(1, accepted);
            assertEquals(contenderCount - 1, parentBusy);
            assertEquals(1, registry.activeReservations().size());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static void advanceToHearts(BreedingBirthJobRegistry registry, Object scope, UUID jobId) {
        assertEquals(APPLIED, registry.advance(
                scope,
                jobId,
                BreedingBirthJobState.RESERVED,
                BreedingBirthJobState.APPROACHING
        ).status());
        assertEquals(APPLIED, registry.advance(
                scope,
                jobId,
                BreedingBirthJobState.APPROACHING,
                BreedingBirthJobState.HEARTS_SHOWN
        ).status());
    }

    private static BreedingBirthJob job(long jobId,
                                        BreedingParentIdentity parentA,
                                        BreedingParentIdentity parentB) {
        return BreedingBirthJob.reserved(uuid(jobId), WORLD_ID, parentA, parentB);
    }

    private static BreedingBirthJob richJob(long jobId,
                                            BreedingParentIdentity parentA,
                                            BreedingParentIdentity parentB,
                                            String... populationTypes) {
        List<PlannedChild> children = java.util.Arrays.stream(populationTypes)
                .map(type -> new PlannedChild(
                        "baby_" + type,
                        "adult_" + type,
                        "Female",
                        "family_" + type,
                        type
                ))
                .toList();
        BreedingBirthPlan plan = new BreedingBirthPlan(
                new BreedingFertilitySnapshot(1.0, 1.0, children.size(), 0.25, children.size()),
                children
        );
        BreedingReservationScope reservationScope = new BreedingReservationScope(
                10.0,
                null,
                List.of(BreedingPlayerCapacityScope.global(uuid(9_000L)))
        );
        return BreedingBirthJob.reserved(
                uuid(jobId),
                WORLD_ID,
                parentA,
                parentB,
                BreedingPopulationAdmissionService.BreedingMode.PASSIVE,
                plan,
                BreedingJobAdmission.of(children, reservationScope),
                ParentBreedingSnapshot.empty(),
                ParentBreedingSnapshot.empty(),
                AppliedCooldownFingerprint.none(),
                AppliedCooldownFingerprint.none(),
                new BreedingBirthAnchor(jobId, 64.0, jobId)
        );
    }

    private static BreedingParentIdentity parent(long entityId, String profileId) {
        return new BreedingParentIdentity(uuid(entityId), profileId);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
