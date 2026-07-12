package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ownership.CompanionPopulationCommitResult;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Orders durable population apply, fresh world-thread target resolution, source CAS, and journal
 * terminality without retaining an ECS reference or NPC object across a database completion.
 */
final class CompanionSpawnCommitContinuation {
    <T> void finish(
            @Nullable CompletableFuture<CompanionPopulationCommitResult> commit,
            @Nonnull LiveResolver<T> liveResolver,
            @Nullable Predicate<T> sourceFinalization,
            @Nonnull Consumer<T> liveContinuation,
            @Nonnull Supplier<CompletableFuture<Boolean>> sourceDurability,
            @Nonnull Consumer<String> degraded,
            @Nonnull Runnable terminal,
            @Nonnull Dispatcher dispatcher
    ) {
        if (commit == null) {
            complete(null, new IllegalStateException("commit unavailable"), liveResolver,
                    sourceFinalization, liveContinuation, sourceDurability,
                    degraded, terminal, dispatcher);
            return;
        }
        commit.whenComplete((result, failure) -> complete(
                result, failure, liveResolver, sourceFinalization, liveContinuation,
                sourceDurability, degraded, terminal, dispatcher
        ));
    }

    private <T> void complete(
            @Nullable CompanionPopulationCommitResult result,
            @Nullable Throwable failure,
            LiveResolver<T> liveResolver,
            @Nullable Predicate<T> sourceFinalization,
            Consumer<T> liveContinuation,
            Supplier<CompletableFuture<Boolean>> sourceDurability,
            Consumer<String> degraded,
            Runnable terminal,
            Dispatcher dispatcher
    ) {
        Runnable worldTask = () -> finishOnWorld(
                result, failure, liveResolver, sourceFinalization, liveContinuation,
                sourceDurability, degraded, terminal, dispatcher
        );
        dispatch(dispatcher, worldTask, () -> {
            notify(degraded, "spawn-commit-continuation-world-unavailable");
            runTerminal(terminal);
        });
    }

    private <T> void finishOnWorld(
            @Nullable CompanionPopulationCommitResult result,
            @Nullable Throwable failure,
            LiveResolver<T> liveResolver,
            @Nullable Predicate<T> sourceFinalization,
            Consumer<T> liveContinuation,
            Supplier<CompletableFuture<Boolean>> sourceDurability,
            Consumer<String> degraded,
            Runnable terminal,
            Dispatcher dispatcher
    ) {
        boolean ownerCommitted = result != null && result.ownerCommit() != null
                && result.ownerCommit().committed();
        boolean identitySafe = result != null && !result.reason().startsWith("spawn-identity-");
        boolean sourceSafe = failure == null && result != null && identitySafe
                && (result.committed() || ownerCommitted);
        T live = resolveLive(liveResolver);
        if (live == null) {
            notify(degraded, "spawn-live-target-unavailable-after-commit-source-retained");
            notifyCommitFailure(result, failure, degraded);
            runTerminal(terminal);
            return;
        }
        boolean liveApplied = runLive(liveContinuation, live, degraded);
        if (sourceFinalization != null && sourceSafe && liveApplied) {
            if (!runSource(sourceFinalization, live)) {
                notify(degraded, "spawn-source-finalization-failed");
                notifyCommitFailure(result, failure, degraded);
                runTerminal(terminal);
                return;
            }
            notifyCommitFailure(result, failure, degraded);
            finishSourceDurability(sourceDurability, degraded, terminal, dispatcher);
            return;
        }
        if (sourceFinalization != null) {
            notify(degraded, !sourceSafe
                    ? result == null
                    ? "spawn-population-commit-failed-source-retained"
                    : result.reason() + "-source-retained"
                    : "spawn-live-continuation-failed-source-retained");
        }
        notifyCommitFailure(result, failure, degraded);
        runTerminal(terminal);
    }

    private static void finishSourceDurability(
            Supplier<CompletableFuture<Boolean>> sourceDurability,
            Consumer<String> degraded,
            Runnable terminal,
            Dispatcher dispatcher
    ) {
        final CompletableFuture<Boolean> completion;
        try {
            completion = sourceDurability.get();
        } catch (RuntimeException | LinkageError failure) {
            notify(degraded, "spawn-source-finalization-journal-start-failed");
            runTerminal(terminal);
            return;
        }
        if (completion == null) {
            notify(degraded, "spawn-source-finalization-journal-unavailable");
            runTerminal(terminal);
            return;
        }
        completion.whenComplete((finished, failure) -> {
            Runnable worldTask = () -> {
                if (failure != null || !Boolean.TRUE.equals(finished)) {
                    notify(degraded, "spawn-source-finalization-journal-failed");
                }
                runTerminal(terminal);
            };
            dispatch(dispatcher, worldTask, () -> {
                notify(degraded, "spawn-source-finalization-world-unavailable");
                runTerminal(terminal);
            });
        });
    }

    @Nullable
    private static <T> T resolveLive(LiveResolver<T> resolver) {
        try {
            return resolver.resolve();
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    private static <T> boolean runSource(Predicate<T> sourceFinalization, T live) {
        try {
            return sourceFinalization.test(live);
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private static <T> boolean runLive(
            Consumer<T> continuation,
            T live,
            Consumer<String> degraded
    ) {
        try {
            continuation.accept(live);
            return true;
        } catch (RuntimeException | LinkageError failure) {
            notify(degraded, "spawn-post-callback-failed");
            return false;
        }
    }

    private static void notifyCommitFailure(
            @Nullable CompanionPopulationCommitResult result,
            @Nullable Throwable failure,
            Consumer<String> degraded
    ) {
        if (failure != null || result == null || !result.committed()) {
            notify(degraded, result == null ? "spawn-population-commit-failed" : result.reason());
        }
    }

    private static void dispatch(Dispatcher dispatcher, Runnable task, Runnable rejected) {
        try {
            dispatcher.dispatch(task, rejected);
        } catch (RuntimeException | LinkageError failure) {
            runTerminal(rejected);
        }
    }

    private static void notify(Consumer<String> degraded, String reason) {
        try {
            degraded.accept(reason);
        } catch (RuntimeException | LinkageError ignored) {
            // Diagnostics do not own terminality.
        }
    }

    private static void runTerminal(Runnable terminal) {
        try {
            terminal.run();
        } catch (RuntimeException | LinkageError ignored) {
            // The population/source operation is already terminal or conservatively retained.
        }
    }

    @FunctionalInterface
    interface LiveResolver<T> {
        @Nullable T resolve();
    }

    @FunctionalInterface
    interface Dispatcher {
        void dispatch(@Nonnull Runnable task, @Nonnull Runnable rejected);
    }
}
