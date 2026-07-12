package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.ownership.CompanionPopulationCommitResult;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/** Finalizes one live offspring independently of optional progression, effects, and logging. */
final class BreedingLiveChildCompletion {
    void finish(
            @Nonnull List<SideEffect> sideEffects,
            @Nonnull Supplier<CompletableFuture<CompanionPopulationCommitResult>> commitAction,
            @Nonnull Consumer<String> durabilityCallback,
            @Nonnull Runnable reservationRelease
    ) {
        try {
            for (SideEffect sideEffect : sideEffects) {
                runSideEffect(sideEffect, durabilityCallback);
            }
            try {
                CompletableFuture<CompanionPopulationCommitResult> completion = commitAction.get();
                if (completion == null) {
                    notifyBestEffort(durabilityCallback, "breeding-population-commit-unavailable");
                } else {
                    completion.whenComplete((commit, failure) -> {
                        if (failure != null || commit == null || !commit.committed()) {
                            notifyBestEffort(
                                    durabilityCallback,
                                    commit == null
                                            ? "breeding-population-commit-failed"
                                            : commit.reason()
                            );
                        }
                    });
                }
            } catch (RuntimeException | LinkageError failure) {
                notifyBestEffort(durabilityCallback, "breeding-population-commit-failed");
            }
        } finally {
            runSilently(reservationRelease);
        }
    }

    private static void runSideEffect(
            SideEffect sideEffect,
            Consumer<String> durabilityCallback
    ) {
        try {
            sideEffect.action().run();
        } catch (RuntimeException | LinkageError failure) {
            notifyBestEffort(durabilityCallback, sideEffect.failureReason());
        }
    }

    private static void notifyBestEffort(Consumer<String> callback, String reason) {
        try {
            callback.accept(reason);
        } catch (RuntimeException | LinkageError ignored) {
            // Diagnostics must not strand a claimed live-child admission.
        }
    }

    private static void runSilently(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError ignored) {
            // Lease expiry is a secondary cleanup fallback if local release itself fails.
        }
    }

    record SideEffect(@Nonnull String failureReason, @Nonnull Runnable action) {
        SideEffect {
            if (failureReason == null || failureReason.isBlank() || action == null) {
                throw new IllegalArgumentException("A named breeding side effect is required.");
            }
        }
    }
}
