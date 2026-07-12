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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Owns exactly-once terminality for prepared owner/claim units attached to active birth jobs.
 *
 * <p>Nearby reservations remain owned by {@link BreedingBirthJobRegistry}. This registry owns only
 * the shared durable owner/claim capability, so releasing nearby headroom can never accidentally
 * cancel a successfully materialized child's canonical population slot.</p>
 */
public final class BreedingPreparedPopulationRegistry {
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    /** Installs one prepared batch before provisional parent effects are allowed to run. */
    @Nonnull
    public InstallStatus install(@Nonnull Object storeScope,
                                 @Nonnull UUID jobId,
                                 @Nonnull BreedingPopulationAdmissionService service,
                                 @Nonnull PreparedBreedingPopulationBatch batch) {
        Entry candidate = new Entry(storeScope, jobId, service, batch);
        Entry existing = entries.putIfAbsent(jobId, candidate);
        if (existing == null) {
            return InstallStatus.INSTALLED;
        }
        return existing.sameCapability(storeScope, service, batch)
                ? InstallStatus.ALREADY_INSTALLED
                : InstallStatus.CONFLICT;
    }

    /** Returns whether this registry owns the exact prepared capability supplied by the caller. */
    public boolean ownsCapability(@Nonnull Object storeScope,
                                  @Nonnull UUID jobId,
                                  @Nonnull BreedingPopulationAdmissionService service,
                                  @Nonnull PreparedBreedingPopulationBatch batch) {
        Entry entry = entries.get(Objects.requireNonNull(jobId, "jobId"));
        return entry != null && entry.sameCapability(storeScope, service, batch);
    }

    /** Returns whether any prepared capability is attached to this exact scoped job. */
    public boolean ownsJob(@Nonnull Object storeScope, @Nonnull UUID jobId) {
        Entry entry = entries.get(Objects.requireNonNull(jobId, "jobId"));
        return entry != null && entry.belongsTo(storeScope);
    }

    /**
     * Makes the registry the sole terminal owner once a job has an installed capability.
     * A conflicting uninstalled candidate is a distinct capability and is closed here as well.
     */
    public boolean cancelOwnedJob(@Nonnull Object storeScope,
                                  @Nonnull UUID jobId,
                                  @Nonnull BreedingPopulationAdmissionService service,
                                  @Nonnull PreparedBreedingPopulationBatch candidate,
                                  @Nonnull String reason) {
        Entry entry = entries.get(Objects.requireNonNull(jobId, "jobId"));
        if (entry == null || !entry.belongsTo(storeScope)) {
            return false;
        }
        boolean exact = entry.sameCapability(storeScope, service, candidate);
        entry.cancelRemaining(reason);
        if (!exact) {
            cancelCandidate(service, candidate, reason);
        }
        return true;
    }

    /** Returns the deterministic child identity attached to one active job unit. */
    @Nonnull
    public Optional<PreparedBreedingPopulationBatch.ReservedChild> child(
            @Nonnull UUID jobId,
            int unitIndex) {
        Entry entry = entries.get(Objects.requireNonNull(jobId, "jobId"));
        return entry == null ? Optional.empty() : entry.child(unitIndex);
    }

    /** Maps the current shrink-only active ordinal to its original prepared batch index. */
    public int unitIndexForActiveOrdinal(@Nonnull UUID jobId, int activeOrdinal) {
        Entry entry = entries.get(Objects.requireNonNull(jobId, "jobId"));
        return entry == null ? -1 : entry.unitIndexForActiveOrdinal(activeOrdinal);
    }

    /** Returns the exact destination chunk reserved for a child, if claim policy supplied one. */
    @Nullable
    public ClaimChunkCoordinate destination(@Nonnull UUID jobId, int unitIndex) {
        Entry entry = entries.get(Objects.requireNonNull(jobId, "jobId"));
        return entry == null ? null : entry.destination(unitIndex);
    }

    /** Revalidates current policy and moves one unit from RESERVED to APPLYING. */
    public boolean claimForSpawn(@Nonnull UUID jobId, int unitIndex) {
        Entry entry = entries.get(Objects.requireNonNull(jobId, "jobId"));
        return entry != null && entry.claimForSpawn(unitIndex);
    }

    /** Writes the deterministic child profile/UUID/owner into the spawn holder. */
    @Nonnull
    public OwnerComponentMutationService.WriteResult writeSpawnHolder(
            @Nonnull UUID jobId,
            int unitIndex,
            @Nonnull Holder<EntityStore> holder) {
        Entry entry = requireEntry(jobId);
        return entry.writeSpawnHolder(unitIndex, holder);
    }

    /** Crosses the live-entity boundary before any post-add initialization can fail. */
    public boolean markMaterialized(@Nonnull UUID jobId, int unitIndex) {
        Entry entry = entries.get(Objects.requireNonNull(jobId, "jobId"));
        return entry != null && entry.markMaterialized(unitIndex);
    }

    /** Converts one live child from APPLYING to a durable committed population unit. */
    @Nonnull
    public CompletableFuture<CompanionPopulationCommitResult> commitSpawn(
            @Nonnull UUID jobId,
            int unitIndex) {
        Entry entry = requireEntry(jobId);
        return entry.commit(unitIndex);
    }

    /** Cancels one definitively unspawned unit. Repeated calls are idempotent. */
    public void cancelUnit(@Nonnull UUID jobId, int unitIndex, @Nonnull String reason) {
        Entry entry = entries.get(Objects.requireNonNull(jobId, "jobId"));
        if (entry != null) {
            entry.cancel(unitIndex, reason);
        }
    }

    /** Cancels every unit that has not crossed the live-spawn boundary. */
    public void cancelRemaining(@Nonnull UUID jobId, @Nonnull String reason) {
        Entry entry = entries.get(Objects.requireNonNull(jobId, "jobId"));
        if (entry != null) {
            entry.cancelRemaining(reason);
        }
    }

    /** Cancels prepared units removed by a spawn-time nearby-cap shrink. */
    public void retainOnly(@Nonnull UUID jobId,
                           @Nonnull List<String> retainedChildKeys,
                           @Nonnull String reason) {
        Entry entry = entries.get(Objects.requireNonNull(jobId, "jobId"));
        if (entry != null) {
            entry.retainOnly(retainedChildKeys, reason);
        }
    }

    /** Leaves an APPLYING unit intact for startup reconciliation after an ambiguous add outcome. */
    public void retainAmbiguous(@Nonnull UUID jobId, int unitIndex, @Nonnull String reason) {
        Entry entry = entries.get(Objects.requireNonNull(jobId, "jobId"));
        if (entry != null) {
            entry.retainAmbiguous(unitIndex, reason);
        }
    }

    /** Cancels all unstarted units for one world/store lifecycle. */
    public void clearScope(@Nonnull Object storeScope, @Nonnull String reason) {
        for (Entry entry : List.copyOf(entries.values())) {
            if (entry.belongsTo(storeScope)) {
                entry.cancelRemaining(reason);
                entries.remove(entry.jobId, entry);
            }
        }
    }

    /** Cancels every unstarted unit during plugin shutdown. */
    public void clearAll(@Nonnull String reason) {
        for (Entry entry : List.copyOf(entries.values())) {
            entry.cancelRemaining(reason);
        }
        entries.clear();
    }

    /** Snapshot used by deterministic tests and diagnostics. */
    @Nonnull
    public List<UnitState> states(@Nonnull UUID jobId) {
        Entry entry = entries.get(Objects.requireNonNull(jobId, "jobId"));
        return entry == null ? List.of() : entry.states();
    }

    private Entry requireEntry(UUID jobId) {
        Entry entry = entries.get(Objects.requireNonNull(jobId, "jobId"));
        if (entry == null) {
            throw new IllegalStateException("Prepared breeding population batch is missing");
        }
        return entry;
    }

    private static void cancelCandidate(BreedingPopulationAdmissionService service,
                                        PreparedBreedingPopulationBatch candidate,
                                        String reason) {
        try {
            CompletableFuture<Integer> completion = service.cancelRemainingAsync(
                    candidate, normalizeReason(reason)
            );
            if (completion == null) {
                service.markReadinessDegraded("breeding_population_conflict_cancel_missing");
                return;
            }
            completion.exceptionally(failure -> {
                service.markReadinessDegraded("breeding_population_conflict_cancel_failed");
                return 0;
            });
        } catch (RuntimeException | LinkageError failure) {
            try {
                service.markReadinessDegraded("breeding_population_conflict_cancel_start_failed");
            } catch (RuntimeException | LinkageError ignored) {
                // Both conservative journal capabilities remain available to reconciliation.
            }
        }
    }

    private static String normalizeReason(String reason) {
        return reason == null || reason.isBlank()
                ? "breeding-population-canceled"
                : reason.trim();
    }

    public enum InstallStatus {
        INSTALLED,
        ALREADY_INSTALLED,
        CONFLICT
    }

    public enum UnitState {
        RESERVED,
        APPLYING,
        MATERIALIZED,
        COMMITTING,
        COMMITTED,
        CANCELING,
        CANCELED,
        AMBIGUOUS
    }

    private static final class Entry {
        private final Object storeScope;
        private final UUID jobId;
        private final BreedingPopulationAdmissionService service;
        private final PreparedBreedingPopulationBatch batch;
        private final UnitState[] states;
        private List<Integer> activeUnitIndexes;

        private Entry(Object storeScope,
                      UUID jobId,
                      BreedingPopulationAdmissionService service,
                      PreparedBreedingPopulationBatch batch) {
            this.storeScope = Objects.requireNonNull(storeScope, "storeScope");
            this.jobId = Objects.requireNonNull(jobId, "jobId");
            this.service = Objects.requireNonNull(service, "service");
            this.batch = Objects.requireNonNull(batch, "batch");
            this.states = new UnitState[batch.admittedCount()];
            Arrays.fill(this.states, UnitState.RESERVED);
            List<Integer> indexes = new ArrayList<>(batch.admittedCount());
            for (int index = 0; index < batch.admittedCount(); index++) {
                indexes.add(index);
            }
            this.activeUnitIndexes = List.copyOf(indexes);
        }

        private synchronized boolean sameCapability(Object scope,
                                                    BreedingPopulationAdmissionService authority,
                                                    PreparedBreedingPopulationBatch candidate) {
            return storeScope == scope
                    && service == authority
                    && batch.populationBatch().batchId().equals(
                            candidate.populationBatch().batchId()
                    )
                    && batch.attemptKey().equals(candidate.attemptKey())
                    && batch.children().equals(candidate.children());
        }

        private synchronized Optional<PreparedBreedingPopulationBatch.ReservedChild> child(int index) {
            return validIndex(index) ? Optional.of(batch.child(index)) : Optional.empty();
        }

        private synchronized int unitIndexForActiveOrdinal(int activeOrdinal) {
            return activeOrdinal >= 0 && activeOrdinal < activeUnitIndexes.size()
                    ? activeUnitIndexes.get(activeOrdinal)
                    : -1;
        }

        private synchronized ClaimChunkCoordinate destination(int index) {
            return validIndex(index)
                    ? batch.populationBatch().admission(index).claimReservation().destinationChunk()
                    : null;
        }

        private synchronized boolean claimForSpawn(int index) {
            if (!validIndex(index) || states[index] != UnitState.RESERVED) {
                return false;
            }
            boolean claimed;
            try {
                claimed = service.claimForSpawn(batch, index);
            } catch (RuntimeException | LinkageError failure) {
                claimed = false;
            }
            if (claimed) {
                states[index] = UnitState.APPLYING;
                return true;
            }
            cancel(index, "breeding-population-spawn-claim-rejected");
            return false;
        }

        private synchronized OwnerComponentMutationService.WriteResult writeSpawnHolder(
                int index,
                Holder<EntityStore> holder) {
            if (!validIndex(index) || states[index] != UnitState.APPLYING) {
                throw new IllegalStateException("Breeding population unit is not APPLYING");
            }
            return service.writeSpawnHolder(batch, index, holder);
        }

        private CompletableFuture<CompanionPopulationCommitResult> commit(int index) {
            synchronized (this) {
                if (!validIndex(index) || states[index] != UnitState.MATERIALIZED) {
                    return CompletableFuture.completedFuture(new CompanionPopulationCommitResult(
                            false, "breeding-population-unit-not-applying", false, null
                    ));
                }
                states[index] = UnitState.COMMITTING;
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
            return completion.handle((result, failure) -> {
                boolean degraded;
                synchronized (Entry.this) {
                    if (failure == null && result != null && result.committed()) {
                        states[index] = UnitState.COMMITTED;
                        degraded = false;
                    } else {
                        states[index] = UnitState.AMBIGUOUS;
                        degraded = true;
                    }
                }
                if (degraded) {
                    markDegraded("breeding_population_commit_ambiguous");
                }
                return result == null
                        ? new CompanionPopulationCommitResult(
                                false, "breeding-population-commit-failed", false, null
                        )
                        : result;
            });
        }

        private boolean markMaterialized(int index) {
            synchronized (this) {
                if (validIndex(index) && states[index] == UnitState.APPLYING) {
                    states[index] = UnitState.MATERIALIZED;
                    return true;
                }
            }
            markDegraded("breeding_population_materialized_state_conflict");
            return false;
        }

        private void cancelRemaining(String reason) {
            for (int index = 0; index < states.length; index++) {
                cancel(index, reason);
            }
        }

        private void retainOnly(List<String> retainedChildKeys, String reason) {
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

        private void cancel(int index, String reason) {
            synchronized (this) {
                if (!validIndex(index) || !definitelyCancelable(states[index])) {
                    return;
                }
                states[index] = UnitState.CANCELING;
            }
            CompletableFuture<Boolean> completion;
            try {
                completion = service.cancelAsync(batch, index, normalizeReason(reason));
            } catch (RuntimeException | LinkageError failure) {
                completion = null;
            }
            if (completion == null) {
                cancellationAmbiguous(index);
                return;
            }
            completion.whenComplete((cancelled, failure) -> {
                boolean degraded;
                synchronized (Entry.this) {
                    if (failure == null && Boolean.TRUE.equals(cancelled)) {
                        states[index] = UnitState.CANCELED;
                        degraded = false;
                    } else {
                        states[index] = UnitState.AMBIGUOUS;
                        degraded = true;
                    }
                }
                if (degraded) {
                    markDegraded("breeding_population_cancel_ambiguous");
                }
            });
        }

        private void retainAmbiguous(int index, String reason) {
            synchronized (this) {
                if (!validIndex(index) || states[index] == UnitState.COMMITTED
                        || states[index] == UnitState.CANCELED) {
                    return;
                }
                states[index] = UnitState.AMBIGUOUS;
            }
            markDegraded(normalizeReason(reason));
        }

        private synchronized List<UnitState> states() {
            return List.copyOf(Arrays.asList(states.clone()));
        }

        private synchronized boolean belongsTo(Object scope) {
            return storeScope == scope;
        }

        private synchronized boolean validIndex(int index) {
            return index >= 0 && index < states.length;
        }

        private static boolean definitelyCancelable(UnitState state) {
            return state == UnitState.RESERVED || state == UnitState.APPLYING;
        }

        private void cancellationAmbiguous(int index) {
            synchronized (this) {
                states[index] = UnitState.AMBIGUOUS;
            }
            markDegraded("breeding_population_cancel_start_failed");
        }

        private void markDegraded(String reason) {
            try {
                service.markReadinessDegraded(reason);
            } catch (RuntimeException | LinkageError ignored) {
                // The nonterminal journal remains the conservative source of truth.
            }
        }

        private static String normalizeReason(String reason) {
            return BreedingPreparedPopulationRegistry.normalizeReason(reason);
        }
    }
}
