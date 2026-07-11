package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ownership.CompanionPopulationCommitResult;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Terminalizes a live coop release after its atomic population/ledger transaction becomes durable.
 */
final class CoopReleaseSpawnCompletion {
    void finish(
            @Nonnull Supplier<CompletableFuture<CompanionPopulationCommitResult>> commitAction,
            @Nonnull Consumer<CompanionPopulationCommitResult> durableCallback,
            @Nonnull Consumer<String> durabilityCallback,
            @Nonnull Runnable terminalCallback,
            @Nonnull CompletionDispatcher dispatcher
    ) {
        final CompletableFuture<CompanionPopulationCommitResult> completion;
        try {
            completion = commitAction.get();
            if (completion == null) {
                Runnable unavailable = () -> notifyAndTerminate(
                        durabilityCallback,
                        "coop-release-population-commit-unavailable",
                        terminalCallback
                );
                dispatch(dispatcher, unavailable, unavailable);
                return;
            }
        } catch (RuntimeException | LinkageError failure) {
            Runnable failed = () -> notifyAndTerminate(
                    durabilityCallback,
                    "coop-release-population-commit-failed",
                    terminalCallback
            );
            dispatch(dispatcher, failed, failed);
            return;
        }
        completion.whenComplete((commit, failure) -> {
            Runnable applied = () -> finishOnCompletionThread(
                    commit,
                    failure,
                    durableCallback,
                    durabilityCallback,
                    terminalCallback
            );
            Runnable rejected = () -> notifyAndTerminate(
                    durabilityCallback,
                    "coop-release-world-unavailable-after-commit",
                    terminalCallback
            );
            dispatch(dispatcher, applied, rejected);
        });
    }

    private static void finishOnCompletionThread(
            CompanionPopulationCommitResult commit,
            Throwable failure,
            Consumer<CompanionPopulationCommitResult> durableCallback,
            Consumer<String> durabilityCallback,
            Runnable terminalCallback
    ) {
        try {
            if (failure != null || commit == null) {
                notifyBestEffort(
                        durabilityCallback, "coop-release-population-commit-failed"
                );
                return;
            }
            if (!commit.committed()) {
                notifyBestEffort(durabilityCallback, commit.reason());
            }
            if (populationTransactionDurable(commit)) {
                runBestEffort(
                        () -> durableCallback.accept(commit),
                        durabilityCallback,
                        "coop-release-callback-failed"
                );
            }
        } finally {
            runSilently(terminalCallback);
        }
    }

    private static boolean populationTransactionDurable(
            CompanionPopulationCommitResult commit
    ) {
        return commit.committed()
                || (commit.ownerCommit() != null && commit.ownerCommit().committed());
    }

    private static void dispatch(
            CompletionDispatcher dispatcher,
            Runnable applied,
            Runnable rejected
    ) {
        try {
            dispatcher.dispatch(applied, rejected);
        } catch (RuntimeException | LinkageError failure) {
            runSilently(rejected);
        }
    }

    private static void notifyAndTerminate(
            Consumer<String> durabilityCallback,
            String reason,
            Runnable terminalCallback
    ) {
        notifyBestEffort(durabilityCallback, reason);
        runSilently(terminalCallback);
    }

    private static void runBestEffort(
            Runnable action,
            Consumer<String> durabilityCallback,
            String failureReason
    ) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError failure) {
            notifyBestEffort(durabilityCallback, failureReason);
        }
    }

    private static void notifyBestEffort(Consumer<String> callback, String reason) {
        try {
            callback.accept(reason);
        } catch (RuntimeException | LinkageError ignored) {
            // A diagnostic callback must not interfere with population finalization.
        }
    }

    private static void runSilently(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError ignored) {
            // The population operation is already terminal; callers own any local cleanup retry.
        }
    }

    interface CompletionDispatcher {
        void dispatch(@Nonnull Runnable applied, @Nonnull Runnable rejected);
    }
}
