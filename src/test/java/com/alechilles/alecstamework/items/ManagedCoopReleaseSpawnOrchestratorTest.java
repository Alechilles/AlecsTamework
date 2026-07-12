package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.SpawnReady;
import com.alechilles.alecstamework.items.ManagedCoopReleaseSpawnOrchestrator.Admission;
import com.alechilles.alecstamework.items.ManagedCoopReleaseSpawnOrchestrator.Finalization;
import com.alechilles.alecstamework.items.ManagedCoopReleaseSpawnOrchestrator.Outcome;
import com.alechilles.alecstamework.items.ManagedCoopReleaseSpawnOrchestrator.SpawnAttempt;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static com.alechilles.alecstamework.items.ManagedCoopReleaseSpawnOrchestrator.Status.BLOCKED;
import static com.alechilles.alecstamework.items.ManagedCoopReleaseSpawnOrchestrator.Status.PERSISTENCE_FAILED;
import static com.alechilles.alecstamework.items.ManagedCoopReleaseSpawnOrchestrator.Status.SPAWN_AMBIGUOUS;
import static com.alechilles.alecstamework.items.ManagedCoopReleaseSpawnOrchestrator.Status.SPAWN_FAILED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedCoopReleaseSpawnOrchestratorTest {
    private static final UUID SOURCE = uuid(1);
    private static final UUID PLANNED = uuid(2);

    @Test
    void spawnsBeforePersistenceAndDispatchesPresentationOnlyAfterFinalized() throws Exception {
        List<String> events = new ArrayList<>();
        CompletableFuture<Finalization> persistence = new CompletableFuture<>();
        ManagedCoopReleaseSpawnOrchestrator orchestrator =
                new ManagedCoopReleaseSpawnOrchestrator(
                        (claim, actual, recordedAt) -> {
                            events.add("finalization_submitted:" + actual);
                            return persistence;
                        },
                        command -> events.add("presentation:" + command.actualTargetUuid())
                );
        SpawnReady claim = claim();

        CompletableFuture<Outcome> completion = orchestrator.coordinate(
                claim,
                Admission.clearToSpawn(),
                () -> {
                    events.add("spawn");
                    return SpawnAttempt.spawned(PLANNED);
                },
                100L
        );

        assertEquals(List.of("spawn", "finalization_submitted:" + PLANNED), events);
        assertFalse(completion.isDone());
        persistence.complete(finalized(claim));
        Outcome outcome = completion.get(3, TimeUnit.SECONDS);
        assertEquals(ManagedCoopReleaseSpawnOrchestrator.Status.FINALIZED, outcome.status());
        assertTrue(outcome.spawnedThisAttempt());
        assertTrue(outcome.presentationDispatched());
        assertEquals(PLANNED, outcome.actualTargetUuid());
        assertEquals(List.of(
                "spawn",
                "finalization_submitted:" + PLANNED,
                "presentation:" + PLANNED
        ), events);
    }

    @Test
    void spawnFailureNeverCallsFinalizerOrPresentation() throws Exception {
        AtomicInteger finalizations = new AtomicInteger();
        AtomicInteger presentations = new AtomicInteger();
        ManagedCoopReleaseSpawnOrchestrator orchestrator =
                new ManagedCoopReleaseSpawnOrchestrator(
                        (claim, actual, recordedAt) -> {
                            finalizations.incrementAndGet();
                            return CompletableFuture.completedFuture(finalized(claim));
                        },
                        command -> presentations.incrementAndGet()
                );

        Outcome outcome = orchestrator.coordinate(
                claim(), Admission.clearToSpawn(),
                () -> SpawnAttempt.failed("role_not_found"), 100L
        ).get(3, TimeUnit.SECONDS);

        assertEquals(SPAWN_FAILED, outcome.status());
        assertEquals(0, finalizations.get());
        assertEquals(0, presentations.get());
    }

    @Test
    void ambiguousSpawnRetainsReceiptAndNeverRespawnsOrFinalizesBlindly() throws Exception {
        AtomicInteger spawns = new AtomicInteger();
        AtomicInteger finalizations = new AtomicInteger();
        ManagedCoopReleaseSpawnOrchestrator orchestrator =
                new ManagedCoopReleaseSpawnOrchestrator(
                        (claim, actual, recordedAt) -> {
                            finalizations.incrementAndGet();
                            return CompletableFuture.completedFuture(finalized(claim));
                        },
                        command -> { }
                );

        Outcome ambiguous = orchestrator.coordinate(
                claim(), Admission.clearToSpawn(),
                () -> {
                    spawns.incrementAndGet();
                    return SpawnAttempt.ambiguous("spawn_result_uncertain");
                }, 100L).get(3, TimeUnit.SECONDS);
        Outcome replay = orchestrator.coordinate(
                claim(), Admission.clearToSpawn(),
                () -> {
                    spawns.incrementAndGet();
                    return SpawnAttempt.spawned(PLANNED);
                }, 101L).get(3, TimeUnit.SECONDS);

        assertEquals(SPAWN_AMBIGUOUS, ambiguous.status());
        assertEquals(ManagedCoopReleaseSpawnOrchestrator.Status.DEDUPLICATED,
                replay.status());
        assertEquals(1, spawns.get());
        assertEquals(0, finalizations.get());
    }

    @Test
    void mismatchedSpawnIdentityClosesGateInsteadOfPermittingAnotherSpawn() throws Exception {
        AtomicInteger spawns = new AtomicInteger();
        AtomicInteger finalizations = new AtomicInteger();
        ManagedCoopReleaseSpawnOrchestrator orchestrator =
                new ManagedCoopReleaseSpawnOrchestrator(
                        (claim, actual, recordedAt) -> {
                            finalizations.incrementAndGet();
                            return CompletableFuture.completedFuture(finalized(claim));
                        },
                        command -> { }
                );

        Outcome first = orchestrator.coordinate(
                claim(), Admission.clearToSpawn(),
                () -> {
                    spawns.incrementAndGet();
                    return SpawnAttempt.spawned(uuid(99));
                }, 100L
        ).get(3, TimeUnit.SECONDS);
        Outcome replay = orchestrator.coordinate(
                claim(), Admission.clearToSpawn(),
                () -> {
                    spawns.incrementAndGet();
                    return SpawnAttempt.spawned(PLANNED);
                }, 101L
        ).get(3, TimeUnit.SECONDS);

        assertEquals(SPAWN_AMBIGUOUS, first.status());
        assertEquals(BLOCKED, replay.status());
        assertEquals(1, spawns.get());
        assertEquals(0, finalizations.get());
    }

    @Test
    void failedFinalizationClosesSpawnGateAndSameMarkedUuidCanRetry() throws Exception {
        AtomicInteger spawns = new AtomicInteger();
        AtomicInteger finalizations = new AtomicInteger();
        AtomicInteger presentations = new AtomicInteger();
        SpawnReady claim = claim();
        ManagedCoopReleaseSpawnOrchestrator orchestrator =
                new ManagedCoopReleaseSpawnOrchestrator(
                        (ignored, actual, recordedAt) -> {
                            int call = finalizations.incrementAndGet();
                            return CompletableFuture.completedFuture(call == 1
                                    ? Finalization.failed("write_failed")
                                    : finalized(claim));
                        },
                        command -> presentations.incrementAndGet()
                );

        Outcome first = orchestrator.coordinate(
                claim, Admission.clearToSpawn(),
                () -> {
                    spawns.incrementAndGet();
                    return SpawnAttempt.spawned(PLANNED);
                }, 100L
        ).get(3, TimeUnit.SECONDS);
        Outcome missingLiveReplay = orchestrator.coordinate(
                claim, Admission.clearToSpawn(),
                () -> {
                    spawns.incrementAndGet();
                    return SpawnAttempt.spawned(PLANNED);
                }, 101L
        ).get(3, TimeUnit.SECONDS);
        Outcome markedReplay = orchestrator.coordinate(
                claim, Admission.matching(PLANNED),
                () -> {
                    spawns.incrementAndGet();
                    return SpawnAttempt.spawned(PLANNED);
                }, 102L
        ).get(3, TimeUnit.SECONDS);

        assertEquals(PERSISTENCE_FAILED, first.status());
        assertEquals(BLOCKED, missingLiveReplay.status());
        assertTrue(missingLiveReplay.detail().contains("requires_matching_live_projection"));
        assertEquals(ManagedCoopReleaseSpawnOrchestrator.Status.FINALIZED, markedReplay.status());
        assertFalse(markedReplay.spawnedThisAttempt());
        assertEquals(1, spawns.get());
        assertEquals(2, finalizations.get());
        assertEquals(1, presentations.get());
    }

    @Test
    void coldReplayAdoptsExactMarkedProjectionWithoutSpawning() throws Exception {
        AtomicInteger spawns = new AtomicInteger();
        AtomicInteger finalizations = new AtomicInteger();
        SpawnReady claim = claim();
        ManagedCoopReleaseSpawnOrchestrator orchestrator =
                new ManagedCoopReleaseSpawnOrchestrator(
                        (ignored, actual, recordedAt) -> {
                            finalizations.incrementAndGet();
                            return CompletableFuture.completedFuture(finalized(claim));
                        },
                        command -> { }
                );

        Outcome outcome = orchestrator.coordinate(
                claim, Admission.matching(PLANNED),
                () -> {
                    spawns.incrementAndGet();
                    return SpawnAttempt.spawned(PLANNED);
                }, 100L
        ).get(3, TimeUnit.SECONDS);

        assertEquals(ManagedCoopReleaseSpawnOrchestrator.Status.FINALIZED, outcome.status());
        assertFalse(outcome.spawnedThisAttempt());
        assertEquals(0, spawns.get());
        assertEquals(1, finalizations.get());
    }

    @Test
    void conflictingMarkedUuidBlocksBeforeSpawnOrPersistence() throws Exception {
        AtomicInteger spawns = new AtomicInteger();
        AtomicInteger finalizations = new AtomicInteger();
        ManagedCoopReleaseSpawnOrchestrator orchestrator =
                new ManagedCoopReleaseSpawnOrchestrator(
                        (claim, actual, recordedAt) -> {
                            finalizations.incrementAndGet();
                            return CompletableFuture.completedFuture(finalized(claim));
                        },
                        command -> { }
                );

        Outcome outcome = orchestrator.coordinate(
                claim(), Admission.matching(uuid(99)),
                () -> {
                    spawns.incrementAndGet();
                    return SpawnAttempt.spawned(PLANNED);
                }, 100L
        ).get(3, TimeUnit.SECONDS);

        assertEquals(BLOCKED, outcome.status());
        assertEquals(0, spawns.get());
        assertEquals(0, finalizations.get());
    }

    @Test
    void deduplicatedOrMismatchedFinalizationNeverDispatchesPresentation() throws Exception {
        AtomicInteger presentations = new AtomicInteger();
        SpawnReady claim = claim();
        AtomicInteger call = new AtomicInteger();
        ManagedCoopReleaseSpawnOrchestrator orchestrator =
                new ManagedCoopReleaseSpawnOrchestrator(
                        (ignored, actual, recordedAt) -> CompletableFuture.completedFuture(
                                call.incrementAndGet() == 1
                                        ? Finalization.deduplicated("in_flight")
                                        : Finalization.failed("mismatched_finalization")),
                        command -> presentations.incrementAndGet()
                );

        Outcome deduplicated = orchestrator.coordinate(
                claim, Admission.clearToSpawn(), () -> SpawnAttempt.spawned(PLANNED), 100L
        ).get(3, TimeUnit.SECONDS);
        Outcome mismatch = orchestrator.coordinate(
                claim, Admission.matching(PLANNED), () -> SpawnAttempt.spawned(PLANNED), 101L
        ).get(3, TimeUnit.SECONDS);

        assertEquals(ManagedCoopReleaseSpawnOrchestrator.Status.DEDUPLICATED,
                deduplicated.status());
        assertEquals(PERSISTENCE_FAILED, mismatch.status());
        assertEquals(0, presentations.get());
    }

    private static SpawnReady claim() {
        return new SpawnReady(
                "managed-coop-release:test",
                "profile-a",
                "resident-a",
                new ManagedCoopAuthorityKey("world", 1, 2, 3),
                "coop-a",
                2,
                SOURCE,
                PLANNED,
                null,
                "a".repeat(64),
                0L,
                1L,
                1L,
                OperationState.SPAWN_CLAIMED,
                8L,
                true
        );
    }

    private static Finalization finalized(SpawnReady claim) {
        return Finalization.finalized(null);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
