package com.alechilles.alecstamework.npc.breeding;

import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.ownership.BreedingPopulationAdmissionService;
import com.alechilles.alecstamework.ownership.CompanionPopulationCommitResult;
import com.alechilles.alecstamework.ownership.OwnerComponentMutationService;
import com.alechilles.alecstamework.ownership.PreparedBreedingPopulationBatch;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;

/** Owns state and durable terminal completions for one prepared breeding capability. */
final class BreedingPreparedPopulationEntry {
    private final Object storeScope;
    final UUID jobId;
    private final BreedingPopulationAdmissionService service;
    private final PreparedBreedingPopulationBatch batch;
    private final BreedingPreparedPopulationRegistry.UnitState[] states;
    private final List<CompletableFuture<Boolean>> terminalCompletions;
    private List<Integer> activeUnitIndexes;

    BreedingPreparedPopulationEntry(Object storeScope,
                                    UUID jobId,
                                    BreedingPopulationAdmissionService service,
                                    PreparedBreedingPopulationBatch batch) {
        this.storeScope = java.util.Objects.requireNonNull(storeScope, "storeScope");
        this.jobId = java.util.Objects.requireNonNull(jobId, "jobId");
        this.service = java.util.Objects.requireNonNull(service, "service");
        this.batch = java.util.Objects.requireNonNull(batch, "batch");
        this.states = new BreedingPreparedPopulationRegistry.UnitState[batch.admittedCount()];
        Arrays.fill(this.states, BreedingPreparedPopulationRegistry.UnitState.RESERVED);
        this.terminalCompletions = new ArrayList<>(Collections.nCopies(
                batch.admittedCount(), null
        ));
        List<Integer> indexes = new ArrayList<>(batch.admittedCount());
        for (int index = 0; index < batch.admittedCount(); index++) {
            indexes.add(index);
        }
        this.activeUnitIndexes = List.copyOf(indexes);
    }

    synchronized boolean sameCapability(Object scope,
                                        BreedingPopulationAdmissionService authority,
                                        PreparedBreedingPopulationBatch candidate) {
        return storeScope == scope
                && service == authority
                && batch.populationBatch().batchId().equals(candidate.populationBatch().batchId())
                && batch.attemptKey().equals(candidate.attemptKey())
                && batch.children().equals(candidate.children());
    }

    synchronized Optional<PreparedBreedingPopulationBatch.ReservedChild> child(int index) {
        return validIndex(index) ? Optional.of(batch.child(index)) : Optional.empty();
    }

    synchronized int unitIndexForActiveOrdinal(int activeOrdinal) {
        return activeOrdinal >= 0 && activeOrdinal < activeUnitIndexes.size()
                ? activeUnitIndexes.get(activeOrdinal)
                : -1;
    }

    @Nullable
    synchronized ClaimChunkCoordinate destination(int index) {
        return validIndex(index)
                ? batch.populationBatch().admission(index).claimReservation().destinationChunk()
                : null;
    }

    synchronized boolean claimForSpawn(int index) {
        if (!validIndex(index)
                || states[index] != BreedingPreparedPopulationRegistry.UnitState.RESERVED) {
            return false;
        }
        boolean claimed;
        try {
            claimed = service.claimForSpawn(batch, index);
        } catch (RuntimeException | LinkageError failure) {
            claimed = false;
        }
        if (claimed) {
            states[index] = BreedingPreparedPopulationRegistry.UnitState.APPLYING;
            return true;
        }
        cancel(index, "breeding-population-spawn-claim-rejected");
        return false;
    }

    synchronized OwnerComponentMutationService.WriteResult writeSpawnHolder(
            int index,
            Holder<EntityStore> holder) {
        if (!validIndex(index)
                || states[index] != BreedingPreparedPopulationRegistry.UnitState.APPLYING) {
            throw new IllegalStateException("Breeding population unit is not APPLYING");
        }
        return service.writeSpawnHolder(batch, index, holder);
    }

    CompletableFuture<CompanionPopulationCommitResult> commit(int index) {
        CompletableFuture<Boolean> terminal = new CompletableFuture<>();
        synchronized (this) {
            if (!validIndex(index)
                    || states[index] != BreedingPreparedPopulationRegistry.UnitState.MATERIALIZED) {
                return CompletableFuture.completedFuture(new CompanionPopulationCommitResult(
                        false, "breeding-population-unit-not-applying", false, null
                ));
            }
            states[index] = BreedingPreparedPopulationRegistry.UnitState.COMMITTING;
            terminalCompletions.set(index, terminal);
        }
        final CompletableFuture<CompanionPopulationCommitResult> completion;
        try {
            completion = service.commitAsync(batch, index);
        } catch (RuntimeException | LinkageError failure) {
            retainAmbiguous(index, "breeding-population-commit-start-failed");
            return CompletableFuture.completedFuture(new CompanionPopulationCommitResult(
                    false, "breeding-population-commit-start-failed", false, null
            ));
        }
        if (completion == null) {
            retainAmbiguous(index, "breeding-population-commit-stage-missing");
            return CompletableFuture.completedFuture(new CompanionPopulationCommitResult(
                    false, "breeding-population-commit-stage-missing", false, null
            ));
        }
        return completion.handle((result, failure) -> finishCommit(
                index, terminal, result, failure
        ));
    }

    private CompanionPopulationCommitResult finishCommit(
            int index,
            CompletableFuture<Boolean> terminal,
            CompanionPopulationCommitResult result,
            Throwable failure) {
        boolean committed = failure == null && result != null && result.committed();
        synchronized (this) {
            states[index] = committed
                    ? BreedingPreparedPopulationRegistry.UnitState.COMMITTED
                    : BreedingPreparedPopulationRegistry.UnitState.AMBIGUOUS;
        }
        terminal.complete(committed);
        if (!committed) {
            markDegraded("breeding_population_commit_ambiguous");
        }
        return result == null
                ? new CompanionPopulationCommitResult(
                        false, "breeding-population-commit-failed", false, null)
                : result;
    }

    boolean markMaterialized(int index) {
        synchronized (this) {
            if (validIndex(index)
                    && states[index] == BreedingPreparedPopulationRegistry.UnitState.APPLYING) {
                states[index] = BreedingPreparedPopulationRegistry.UnitState.MATERIALIZED;
                return true;
            }
        }
        markDegraded("breeding_population_materialized_state_conflict");
        return false;
    }

    void cancelRemaining(String reason) {
        cancelRemainingDurably(reason);
    }

    void cancelUnit(int index, String reason) {
        cancel(index, reason);
    }

    CompletableFuture<Boolean> cancelRemainingDurably(String reason) {
        List<CompletableFuture<Boolean>> cancellations = new ArrayList<>(states.length);
        for (int index = 0; index < states.length; index++) {
            cancellations.add(cancel(index, reason));
        }
        return allTerminal(cancellations);
    }

    void retainOnly(List<String> retainedChildKeys, String reason) {
        List<String> retained = List.copyOf(retainedChildKeys);
        List<Integer> retainedIndexes = new ArrayList<>(retained.size());
        for (int index = 0; index < states.length; index++) {
            if (retained.contains(batch.child(index).childKey())) {
                retainedIndexes.add(index);
            } else {
                cancel(index, reason);
            }
        }
        synchronized (this) {
            activeUnitIndexes = List.copyOf(retainedIndexes);
        }
    }

    private CompletableFuture<Boolean> cancel(int index, String reason) {
        CompletableFuture<Boolean> terminal;
        synchronized (this) {
            if (!validIndex(index)) {
                return CompletableFuture.completedFuture(false);
            }
            BreedingPreparedPopulationRegistry.UnitState state = states[index];
            if (state == BreedingPreparedPopulationRegistry.UnitState.CANCELED
                    || state == BreedingPreparedPopulationRegistry.UnitState.COMMITTED) {
                return CompletableFuture.completedFuture(true);
            }
            if (state == BreedingPreparedPopulationRegistry.UnitState.CANCELING
                    || state == BreedingPreparedPopulationRegistry.UnitState.COMMITTING) {
                terminal = terminalCompletions.get(index);
                return terminal != null
                        ? terminal : CompletableFuture.completedFuture(false);
            }
            if (!definitelyCancelable(state)) {
                return CompletableFuture.completedFuture(false);
            }
            states[index] = BreedingPreparedPopulationRegistry.UnitState.CANCELING;
            terminal = new CompletableFuture<>();
            terminalCompletions.set(index, terminal);
        }
        startCancellation(index, normalizeReason(reason), terminal);
        return terminal;
    }

    private void startCancellation(int index,
                                   String reason,
                                   CompletableFuture<Boolean> terminal) {
        CompletableFuture<Boolean> completion;
        try {
            completion = service.cancelAsync(batch, index, reason);
        } catch (RuntimeException | LinkageError failure) {
            completion = null;
        }
        if (completion == null) {
            cancellationFinished(index, terminal, false, "breeding_population_cancel_start_failed");
            return;
        }
        completion.whenComplete((cancelled, failure) -> cancellationFinished(
                index,
                terminal,
                failure == null && Boolean.TRUE.equals(cancelled),
                "breeding_population_cancel_ambiguous"
        ));
    }

    private void cancellationFinished(int index,
                                      CompletableFuture<Boolean> terminal,
                                      boolean canceled,
                                      String degradedReason) {
        synchronized (this) {
            states[index] = canceled
                    ? BreedingPreparedPopulationRegistry.UnitState.CANCELED
                    : BreedingPreparedPopulationRegistry.UnitState.AMBIGUOUS;
        }
        terminal.complete(canceled);
        if (!canceled) {
            markDegraded(degradedReason);
        }
    }

    void retainAmbiguous(int index, String reason) {
        CompletableFuture<Boolean> terminal = null;
        synchronized (this) {
            if (!validIndex(index)
                    || states[index] == BreedingPreparedPopulationRegistry.UnitState.COMMITTED
                    || states[index] == BreedingPreparedPopulationRegistry.UnitState.CANCELED) {
                return;
            }
            states[index] = BreedingPreparedPopulationRegistry.UnitState.AMBIGUOUS;
            terminal = terminalCompletions.get(index);
        }
        if (terminal != null) {
            terminal.complete(false);
        }
        markDegraded(normalizeReason(reason));
    }

    synchronized List<BreedingPreparedPopulationRegistry.UnitState> states() {
        return List.copyOf(Arrays.asList(states.clone()));
    }

    synchronized boolean belongsTo(Object scope) {
        return storeScope == scope;
    }

    private synchronized boolean validIndex(int index) {
        return index >= 0 && index < states.length;
    }

    private static boolean definitelyCancelable(
            BreedingPreparedPopulationRegistry.UnitState state) {
        return state == BreedingPreparedPopulationRegistry.UnitState.RESERVED
                || state == BreedingPreparedPopulationRegistry.UnitState.APPLYING;
    }

    private void markDegraded(String reason) {
        try {
            service.markReadinessDegraded(reason);
        } catch (RuntimeException | LinkageError ignored) {
            // The nonterminal journal remains the conservative source of truth.
        }
    }

    private static CompletableFuture<Boolean> allTerminal(
            List<CompletableFuture<Boolean>> completions) {
        CompletableFuture<?>[] waits = completions.toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(waits).handle((ignored, failure) -> {
            if (failure != null) {
                return false;
            }
            for (CompletableFuture<Boolean> completion : completions) {
                if (!Boolean.TRUE.equals(completion.getNow(false))) {
                    return false;
                }
            }
            return true;
        });
    }

    private static String normalizeReason(String reason) {
        return reason == null || reason.isBlank()
                ? "breeding-population-canceled"
                : reason.trim();
    }
}
