package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ownership.CompanionPopulationCommitResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ensures release callbacks wait for the atomic population/ledger transaction. */
class CoopReleaseSpawnCompletionTest {
    @Test
    void throwingDurableCallbackCannotPreventTerminalCleanup() {
        AtomicBoolean commitStarted = new AtomicBoolean();
        AtomicBoolean terminal = new AtomicBoolean();
        List<String> degraded = new ArrayList<>();

        new CoopReleaseSpawnCompletion().finish(
                () -> {
                    commitStarted.set(true);
                    return CompletableFuture.completedFuture(
                            new CompanionPopulationCommitResult(
                                    true, "coop-release-population-committed", true, null
                        )
                    );
                },
                ignored -> {
                    throw new IllegalStateException("callback failure");
                },
                degraded::add,
                () -> terminal.set(true),
                (applied, rejected) -> applied.run()
        );

        assertTrue(commitStarted.get());
        assertTrue(terminal.get());
        assertEquals(List.of("coop-release-callback-failed"), degraded);
    }

    @Test
    void throwingCommitStartStillRunsTerminalCleanup() {
        AtomicBoolean terminal = new AtomicBoolean();
        List<String> degraded = new ArrayList<>();

        new CoopReleaseSpawnCompletion().finish(
                () -> {
                    throw new IllegalStateException("commit start failure");
                },
                ignored -> {
                },
                degraded::add,
                () -> terminal.set(true),
                (applied, rejected) -> applied.run()
        );

        assertTrue(terminal.get());
        assertEquals(List.of("coop-release-population-commit-failed"), degraded);
    }

    @Test
    void terminalCleanupWaitsForDurableCompletion() {
        CompletableFuture<CompanionPopulationCommitResult> commit = new CompletableFuture<>();
        AtomicBoolean released = new AtomicBoolean();
        AtomicBoolean terminal = new AtomicBoolean();

        new CoopReleaseSpawnCompletion().finish(
                () -> commit,
                ignored -> released.set(true),
                ignored -> {
                },
                () -> terminal.set(true),
                (applied, rejected) -> applied.run()
        );

        assertTrue(!released.get());
        assertTrue(!terminal.get());
        commit.complete(new CompanionPopulationCommitResult(
                true, "coop-release-population-committed", true, null
        ));
        assertTrue(released.get());
        assertTrue(terminal.get());
    }
}
