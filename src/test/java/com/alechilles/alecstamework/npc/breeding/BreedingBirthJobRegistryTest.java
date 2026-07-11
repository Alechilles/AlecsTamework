package com.alechilles.alecstamework.npc.breeding;

import java.util.ArrayList;
import java.util.List;
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
import static com.alechilles.alecstamework.npc.breeding.BreedingBirthJobRegistry.SpawnClaimStatus.ALREADY_CLAIMED;
import static com.alechilles.alecstamework.npc.breeding.BreedingBirthJobRegistry.SpawnClaimStatus.CLAIMED;
import static com.alechilles.alecstamework.npc.breeding.BreedingBirthJobRegistry.SpawnClaimStatus.SCOPE_CLOSED;
import static com.alechilles.alecstamework.npc.breeding.BreedingBirthJobRegistry.SpawnClaimStatus.TERMINAL;
import static com.alechilles.alecstamework.npc.breeding.BreedingBirthJobRegistry.TransitionStatus.APPLIED;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static BreedingParentIdentity parent(long entityId, String profileId) {
        return new BreedingParentIdentity(uuid(entityId), profileId);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
