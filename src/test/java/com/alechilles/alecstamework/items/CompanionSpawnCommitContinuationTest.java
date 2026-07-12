package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ownership.CompanionPopulationCommitResult;
import com.alechilles.alecstamework.ownership.OwnerPopulationCommitResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for durable-commit-before-source-finalization ordering. */
class CompanionSpawnCommitContinuationTest {
    private final CompanionSpawnCommitContinuation continuation =
            new CompanionSpawnCommitContinuation();

    @Test
    void delayedCommitRetainsSourceUntilDurabilityCompletes() {
        CompletableFuture<CompanionPopulationCommitResult> commit = new CompletableFuture<>();
        List<String> events = new ArrayList<>();

        continuation.finish(
                commit,
                () -> "planned-live-target",
                ignored -> events.add("source"),
                ignored -> events.add("live"),
                () -> CompletableFuture.completedFuture(true),
                reason -> events.add("degraded:" + reason),
                () -> events.add("terminal"),
                immediate()
        );

        assertTrue(events.isEmpty());
        commit.complete(fullCommit());
        assertEquals(List.of("live", "source", "terminal"), events);
    }

    @Test
    void failedCommitAppliesLiveStateButRetainsRecoverableSource() {
        List<String> events = new ArrayList<>();

        continuation.finish(
                CompletableFuture.completedFuture(failedCommit()),
                () -> "planned-live-target",
                ignored -> events.add("source"),
                ignored -> events.add("live"),
                () -> CompletableFuture.completedFuture(true),
                reason -> events.add("degraded:" + reason),
                () -> events.add("terminal"),
                immediate()
        );

        assertEquals("live", events.get(0));
        assertFalse(events.contains("source"));
        assertEquals("terminal", events.get(events.size() - 1));
        assertTrue(events.stream().anyMatch(value -> value.contains("source-retained")));
    }

    @Test
    void asymmetricClaimFailureFinalizesSourceWhenOwnerIsDurablyCommitted() {
        List<String> events = new ArrayList<>();

        continuation.finish(
                CompletableFuture.completedFuture(ownerOnlyCommit()),
                () -> "planned-live-target",
                ignored -> events.add("source"),
                ignored -> events.add("live"),
                () -> CompletableFuture.completedFuture(true),
                reason -> events.add("degraded:" + reason),
                () -> events.add("terminal"),
                immediate()
        );

        assertEquals("live", events.get(0));
        assertEquals("source", events.get(1));
        assertTrue(events.stream().anyMatch(value -> value.contains("claim-index")));
        assertEquals("terminal", events.get(events.size() - 1));
    }

    @Test
    void identityFailureRetainsSourceEvenWhenOwnerCommitSucceeded() {
        List<String> events = new ArrayList<>();
        CompanionPopulationCommitResult result = new CompanionPopulationCommitResult(
                false, "spawn-identity-remap-failed", true, committedOwner()
        );

        continuation.finish(
                CompletableFuture.completedFuture(result),
                () -> "planned-live-target",
                ignored -> events.add("source"),
                ignored -> events.add("live"),
                () -> CompletableFuture.completedFuture(true),
                reason -> events.add("degraded:" + reason),
                () -> events.add("terminal"),
                immediate()
        );

        assertFalse(events.contains("source"));
        assertEquals("terminal", events.get(events.size() - 1));
    }

    @Test
    void throwingLiveCallbackRetainsSourceAndStillTerminalizes() {
        List<String> events = new ArrayList<>();

        continuation.finish(
                CompletableFuture.completedFuture(fullCommit()),
                () -> "planned-live-target",
                ignored -> events.add("source"),
                ignored -> {
                    events.add("live");
                    throw new IllegalStateException("state restore failed");
                },
                () -> CompletableFuture.completedFuture(true),
                reason -> events.add("degraded:" + reason),
                () -> events.add("terminal"),
                immediate()
        );

        assertEquals("live", events.get(0));
        assertFalse(events.contains("source"));
        assertTrue(events.stream().anyMatch(value -> value.contains("source-retained")));
        assertEquals("terminal", events.get(events.size() - 1));
    }

    @Test
    void rejectedSourceFinalizerReportsDegradedWithoutLosingTerminality() {
        List<String> events = new ArrayList<>();

        continuation.finish(
                CompletableFuture.completedFuture(fullCommit()),
                () -> "planned-live-target",
                ignored -> {
                    events.add("source-attempt");
                    return false;
                },
                ignored -> events.add("live"),
                () -> CompletableFuture.completedFuture(true),
                reason -> events.add("degraded:" + reason),
                () -> events.add("terminal"),
                immediate()
        );

        assertEquals(List.of(
                "live", "source-attempt", "degraded:spawn-source-finalization-failed", "terminal"
        ), events);
    }

    @Test
    void rejectedCommitContinuationRetainsSourceAndRunsTerminalCleanup() {
        List<String> events = new ArrayList<>();

        continuation.finish(
                CompletableFuture.completedFuture(fullCommit()),
                () -> "planned-live-target",
                ignored -> events.add("source"),
                ignored -> events.add("live"),
                () -> CompletableFuture.completedFuture(true),
                reason -> events.add("degraded:" + reason),
                () -> events.add("terminal"),
                rejected()
        );

        assertFalse(events.contains("live"));
        assertFalse(events.contains("source"));
        assertEquals(List.of(
                "degraded:spawn-commit-continuation-world-unavailable", "terminal"
        ), events);
    }

    @Test
    void rejectedSourceJournalContinuationStillRunsTerminalCleanup() {
        CompletableFuture<Boolean> sourceDurability = new CompletableFuture<>();
        AtomicInteger dispatches = new AtomicInteger();
        List<String> events = new ArrayList<>();

        continuation.finish(
                CompletableFuture.completedFuture(fullCommit()),
                () -> "planned-live-target",
                ignored -> events.add("source"),
                ignored -> events.add("live"),
                () -> sourceDurability,
                reason -> events.add("degraded:" + reason),
                () -> events.add("terminal"),
                (task, rejected) -> {
                    if (dispatches.incrementAndGet() == 1) {
                        task.run();
                    } else {
                        rejected.run();
                    }
                }
        );

        assertEquals(List.of("live", "source"), events);
        sourceDurability.complete(true);
        assertEquals(List.of(
                "live", "source",
                "degraded:spawn-source-finalization-world-unavailable", "terminal"
        ), events);
    }

    private static CompanionSpawnCommitContinuation.Dispatcher immediate() {
        return (task, rejected) -> {
            task.run();
        };
    }

    private static CompanionSpawnCommitContinuation.Dispatcher rejected() {
        return (task, rejected) -> rejected.run();
    }

    private static CompanionPopulationCommitResult fullCommit() {
        return new CompanionPopulationCommitResult(true, "committed", true, committedOwner());
    }

    private static CompanionPopulationCommitResult ownerOnlyCommit() {
        return new CompanionPopulationCommitResult(
                false, "companion-claim-index-commit-failed", false, committedOwner()
        );
    }

    private static CompanionPopulationCommitResult failedCommit() {
        return new CompanionPopulationCommitResult(false, "owner-failed", false,
                new OwnerPopulationCommitResult(
                        OwnerPopulationCommitResult.Status.PERSISTENCE_DEGRADED,
                        "owner-failed",
                        null
        ));
    }

    @Test
    void invalidatedTargetAfterCommitRetainsSourceAndQuarantines() {
        List<String> events = new ArrayList<>();

        continuation.finish(
                CompletableFuture.completedFuture(fullCommit()),
                () -> null,
                ignored -> events.add("source"),
                ignored -> events.add("live"),
                () -> CompletableFuture.completedFuture(true),
                reason -> events.add("degraded:" + reason),
                () -> events.add("terminal"),
                immediate()
        );

        assertFalse(events.contains("source"));
        assertFalse(events.contains("live"));
        assertTrue(events.stream().anyMatch(value -> value.contains(
                "live-target-unavailable-after-commit-source-retained"
        )));
        assertEquals("terminal", events.get(events.size() - 1));
    }

    @Test
    void sourceJournalFailureCannotRepeatSourceCas() {
        List<String> events = new ArrayList<>();

        continuation.finish(
                CompletableFuture.completedFuture(fullCommit()),
                () -> "planned-live-target",
                ignored -> events.add("source"),
                ignored -> events.add("live"),
                () -> CompletableFuture.completedFuture(false),
                reason -> events.add("degraded:" + reason),
                () -> events.add("terminal"),
                immediate()
        );

        assertEquals(1L, events.stream().filter("source"::equals).count());
        assertTrue(events.contains("degraded:spawn-source-finalization-journal-failed"));
        assertEquals("terminal", events.get(events.size() - 1));
    }

    private static OwnerPopulationCommitResult committedOwner() {
        return new OwnerPopulationCommitResult(
                OwnerPopulationCommitResult.Status.COMMITTED,
                "owner-committed",
                null
        );
    }
}
