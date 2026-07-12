package com.alechilles.alecstamework.npc.breeding;

import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.ownership.BreedingPopulationAdmissionService;
import com.alechilles.alecstamework.ownership.CompanionPopulationCommitResult;
import com.alechilles.alecstamework.ownership.OwnerComponentMutationService;
import com.alechilles.alecstamework.ownership.PreparedBreedingPopulationBatch;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
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
 * <p>Nearby reservations remain owned by {@link BreedingBirthJobRegistry}. This registry also
 * joins asynchronous preparation to durable cancellation, so parent capture cannot outrun a late
 * prepared batch or a still-replayable child operation.</p>
 */
public final class BreedingPreparedPopulationRegistry {
    private final Map<UUID, BreedingPreparedPopulationEntry> entries =
            new ConcurrentHashMap<>();
    private final BreedingPreparedCancellationCoordinator cancellation =
            new BreedingPreparedCancellationCoordinator();

    /** Registers preparation before durable admission can create replayable child operations. */
    public boolean beginPreparation(@Nonnull Object storeScope, @Nonnull UUID jobId) {
        return cancellation.beginPreparation(storeScope, jobId);
    }

    /** Registers exact parent identities so terminal jobs cannot hide an unsafe preparation gate. */
    public boolean beginPreparation(@Nonnull Object storeScope,
                                    @Nonnull UUID jobId,
                                    @Nonnull BreedingParentIdentity firstParent,
                                    @Nonnull BreedingParentIdentity secondParent) {
        return cancellation.beginPreparation(
                storeScope, jobId, firstParent, secondParent
        );
    }

    /** Closes one registered preparation only after its prepared capability has been installed. */
    public boolean finishPreparation(@Nonnull Object storeScope, @Nonnull UUID jobId) {
        return cancellation.finishPreparation(storeScope, jobId);
    }

    /** Permanently fails a capture gate when a produced capability could not be registered. */
    public void failPreparation(@Nonnull Object storeScope, @Nonnull UUID jobId) {
        cancellation.failPreparation(storeScope, jobId);
    }

    /** Installs one prepared batch before provisional parent effects are allowed to run. */
    @Nonnull
    public InstallStatus install(@Nonnull Object storeScope,
                                 @Nonnull UUID jobId,
                                 @Nonnull BreedingPopulationAdmissionService service,
                                 @Nonnull PreparedBreedingPopulationBatch batch) {
        BreedingPreparedPopulationEntry candidate = new BreedingPreparedPopulationEntry(
                storeScope, jobId, service, batch
        );
        BreedingPreparedPopulationEntry existing = entries.putIfAbsent(jobId, candidate);
        if (existing == null) {
            cancellation.registerCapability(
                    storeScope, jobId, candidate::cancelRemainingDurably
            );
            return InstallStatus.INSTALLED;
        }
        if (existing.sameCapability(storeScope, service, batch)) {
            return InstallStatus.ALREADY_INSTALLED;
        }
        cancellation.registerCapability(
                storeScope,
                jobId,
                reason -> cancelCandidateDurably(service, batch, reason)
        );
        return InstallStatus.CONFLICT;
    }

    /** Returns whether this registry owns the exact prepared capability supplied by the caller. */
    public boolean ownsCapability(@Nonnull Object storeScope,
                                  @Nonnull UUID jobId,
                                  @Nonnull BreedingPopulationAdmissionService service,
                                  @Nonnull PreparedBreedingPopulationBatch batch) {
        BreedingPreparedPopulationEntry entry = entries.get(Objects.requireNonNull(jobId, "jobId"));
        return entry != null && entry.sameCapability(storeScope, service, batch);
    }

    /** Returns whether any prepared capability is attached to this exact scoped job. */
    public boolean ownsJob(@Nonnull Object storeScope, @Nonnull UUID jobId) {
        BreedingPreparedPopulationEntry entry = entries.get(Objects.requireNonNull(jobId, "jobId"));
        return entry != null && entry.belongsTo(storeScope);
    }

    /** Cancels the installed capability and any conflicting candidate owned by this job gate. */
    public boolean cancelOwnedJob(@Nonnull Object storeScope,
                                  @Nonnull UUID jobId,
                                  @Nonnull BreedingPopulationAdmissionService service,
                                  @Nonnull PreparedBreedingPopulationBatch candidate,
                                  @Nonnull String reason) {
        BreedingPreparedPopulationEntry entry = entries.get(Objects.requireNonNull(jobId, "jobId"));
        if (entry == null || !entry.belongsTo(storeScope)) {
            return false;
        }
        boolean exact = entry.sameCapability(storeScope, service, candidate);
        entry.cancelRemaining(reason);
        if (!exact) {
            cancelCandidateDurably(service, candidate, reason);
        }
        return true;
    }

    /**
     * Returns the shared durability barrier for capture-triggered cancellation.
     * The result is true only after preparation is closed and all units are COMMITTED or CANCELED.
     */
    @Nonnull
    public CompletableFuture<Boolean> cancelRemainingDurably(
            @Nonnull Object storeScope,
            @Nonnull UUID jobId,
            @Nonnull String reason) {
        return cancellation.cancelDurably(storeScope, jobId, reason);
    }

    /** Finds every retained preparation gate for either exact parent identity. */
    @Nullable
    public CompletableFuture<Boolean> cancelRemainingDurablyByParent(
            @Nonnull Object storeScope,
            @Nonnull UUID parentUuid,
            @Nullable String stableProfileId,
            @Nonnull String reason) {
        return cancellation.cancelDurablyByParent(
                storeScope, parentUuid, stableProfileId, reason
        );
    }

    /** Releases one capture fence after capture persistence or a safely terminal failure. */
    public void releaseCaptureFence(@Nonnull Object storeScope,
                                    @Nonnull UUID parentUuid,
                                    @Nullable String stableProfileId,
                                    boolean captured) {
        cancellation.releaseParentFence(
                storeScope, parentUuid, stableProfileId, captured
        );
    }

    @Nonnull
    public Optional<PreparedBreedingPopulationBatch.ReservedChild> child(
            @Nonnull UUID jobId,
            int unitIndex) {
        BreedingPreparedPopulationEntry entry = entries.get(Objects.requireNonNull(jobId, "jobId"));
        return entry == null ? Optional.empty() : entry.child(unitIndex);
    }

    public int unitIndexForActiveOrdinal(@Nonnull UUID jobId, int activeOrdinal) {
        BreedingPreparedPopulationEntry entry = entries.get(Objects.requireNonNull(jobId, "jobId"));
        return entry == null ? -1 : entry.unitIndexForActiveOrdinal(activeOrdinal);
    }

    @Nullable
    public ClaimChunkCoordinate destination(@Nonnull UUID jobId, int unitIndex) {
        BreedingPreparedPopulationEntry entry = entries.get(Objects.requireNonNull(jobId, "jobId"));
        return entry == null ? null : entry.destination(unitIndex);
    }

    public boolean claimForSpawn(@Nonnull UUID jobId, int unitIndex) {
        BreedingPreparedPopulationEntry entry = entries.get(Objects.requireNonNull(jobId, "jobId"));
        return entry != null && entry.claimForSpawn(unitIndex);
    }

    @Nonnull
    public OwnerComponentMutationService.WriteResult writeSpawnHolder(
            @Nonnull UUID jobId,
            int unitIndex,
            @Nonnull Holder<EntityStore> holder) {
        return requireEntry(jobId).writeSpawnHolder(unitIndex, holder);
    }

    public boolean markMaterialized(@Nonnull UUID jobId, int unitIndex) {
        BreedingPreparedPopulationEntry entry = entries.get(Objects.requireNonNull(jobId, "jobId"));
        return entry != null && entry.markMaterialized(unitIndex);
    }

    @Nonnull
    public CompletableFuture<CompanionPopulationCommitResult> commitSpawn(
            @Nonnull UUID jobId,
            int unitIndex) {
        return requireEntry(jobId).commit(unitIndex);
    }

    public void cancelUnit(@Nonnull UUID jobId, int unitIndex, @Nonnull String reason) {
        BreedingPreparedPopulationEntry entry = entries.get(Objects.requireNonNull(jobId, "jobId"));
        if (entry != null) {
            entry.cancelUnit(unitIndex, reason);
        }
    }

    public void cancelRemaining(@Nonnull UUID jobId, @Nonnull String reason) {
        BreedingPreparedPopulationEntry entry = entries.get(Objects.requireNonNull(jobId, "jobId"));
        if (entry != null) {
            entry.cancelRemaining(reason);
        }
    }

    public void retainOnly(@Nonnull UUID jobId,
                           @Nonnull List<String> retainedChildKeys,
                           @Nonnull String reason) {
        BreedingPreparedPopulationEntry entry = entries.get(Objects.requireNonNull(jobId, "jobId"));
        if (entry != null) {
            entry.retainOnly(retainedChildKeys, reason);
        }
    }

    public void retainAmbiguous(@Nonnull UUID jobId, int unitIndex, @Nonnull String reason) {
        BreedingPreparedPopulationEntry entry = entries.get(Objects.requireNonNull(jobId, "jobId"));
        if (entry != null) {
            entry.retainAmbiguous(unitIndex, reason);
        }
    }

    public void clearScope(@Nonnull Object storeScope, @Nonnull String reason) {
        for (BreedingPreparedPopulationEntry entry : List.copyOf(entries.values())) {
            if (entry.belongsTo(storeScope)) {
                entry.cancelRemaining(reason);
                entries.remove(entry.jobId, entry);
            }
        }
        cancellation.clearScope(storeScope);
    }

    public void clearAll(@Nonnull String reason) {
        for (BreedingPreparedPopulationEntry entry : List.copyOf(entries.values())) {
            entry.cancelRemaining(reason);
        }
        entries.clear();
        cancellation.clearAll();
    }

    @Nonnull
    public List<UnitState> states(@Nonnull UUID jobId) {
        BreedingPreparedPopulationEntry entry = entries.get(Objects.requireNonNull(jobId, "jobId"));
        return entry == null ? List.of() : entry.states();
    }

    private BreedingPreparedPopulationEntry requireEntry(UUID jobId) {
        BreedingPreparedPopulationEntry entry = entries.get(Objects.requireNonNull(jobId, "jobId"));
        if (entry == null) {
            throw new IllegalStateException("Prepared breeding population batch is missing");
        }
        return entry;
    }

    private static CompletableFuture<Boolean> cancelCandidateDurably(
            BreedingPopulationAdmissionService service,
            PreparedBreedingPopulationBatch candidate,
            String reason) {
        try {
            CompletableFuture<Integer> completion = service.cancelRemainingAsync(
                    candidate, normalizeReason(reason)
            );
            if (completion == null) {
                service.markReadinessDegraded("breeding_population_conflict_cancel_missing");
                return CompletableFuture.completedFuture(false);
            }
            return completion.handle((count, failure) -> {
                boolean terminal = failure == null
                        && count != null && count == candidate.admittedCount();
                if (!terminal) {
                    service.markReadinessDegraded("breeding_population_conflict_cancel_failed");
                }
                return terminal;
            });
        } catch (RuntimeException | LinkageError failure) {
            try {
                service.markReadinessDegraded("breeding_population_conflict_cancel_start_failed");
            } catch (RuntimeException | LinkageError ignored) {
                // Both conservative journal capabilities remain available to reconciliation.
            }
            return CompletableFuture.completedFuture(false);
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
}
