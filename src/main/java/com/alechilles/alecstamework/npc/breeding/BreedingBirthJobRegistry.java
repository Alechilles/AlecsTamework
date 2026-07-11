package com.alechilles.alecstamework.npc.breeding;

import com.alechilles.alecstamework.util.StoreScopedState;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Thread-safe, store-scoped registry that guards delayed breeding birth callbacks.
 *
 * <p>Active jobs are indexed by deterministic job ID, canonical pair, current parent UUID, and
 * stable parent profile. Terminal jobs remain addressable until world cleanup so replaying an old
 * callback cannot recreate a litter. This registry performs no game-world mutation.
 */
public final class BreedingBirthJobRegistry {
    private final StoreScopedState<ScopeState> statesByStore = new StoreScopedState<>(ScopeState::new);

    /** Attempts to admit a new reserved job into the supplied store scope. */
    @Nonnull
    public AdmissionResult register(@Nonnull Object storeScope, @Nonnull BreedingBirthJob job) {
        Objects.requireNonNull(storeScope, "storeScope");
        Objects.requireNonNull(job, "job");
        if (job.state() != BreedingBirthJobState.RESERVED) {
            throw new IllegalArgumentException("New jobs must be in RESERVED state");
        }
        return statesByStore.get(storeScope).register(job);
    }

    /** Advances a presentation step using an expected-state compare-and-set. */
    @Nonnull
    public TransitionResult advance(@Nonnull Object storeScope,
                                    @Nonnull UUID jobId,
                                    @Nonnull BreedingBirthJobState expectedState,
                                    @Nonnull BreedingBirthJobState nextState) {
        return state(storeScope).advance(jobId, expectedState, nextState);
    }

    /** Atomically grants the sole transition into SPAWNING for a delayed callback. */
    @Nonnull
    public SpawnClaimResult claimSpawn(@Nonnull Object storeScope, @Nonnull UUID jobId) {
        return state(storeScope).claimSpawn(jobId);
    }

    /** Marks a claimed spawn job completed and releases its active indexes. */
    @Nonnull
    public TerminalResult complete(@Nonnull Object storeScope, @Nonnull UUID jobId) {
        return state(storeScope).finish(jobId, BreedingBirthJobState.COMPLETED);
    }

    /** Marks an active job failed and releases its active indexes. */
    @Nonnull
    public TerminalResult fail(@Nonnull Object storeScope, @Nonnull UUID jobId) {
        return state(storeScope).finish(jobId, BreedingBirthJobState.FAILED);
    }

    /** Cancels an active job by deterministic job ID. */
    @Nonnull
    public TerminalResult cancel(@Nonnull Object storeScope, @Nonnull UUID jobId) {
        return state(storeScope).finish(jobId, BreedingBirthJobState.CANCELLED);
    }

    /** Cancels the active job, if any, containing the current entity UUID. */
    @Nonnull
    public TerminalResult cancelByParentUuid(@Nonnull Object storeScope, @Nonnull UUID parentUuid) {
        return state(storeScope).cancelByParentUuid(parentUuid);
    }

    /** Cancels the active job, if any, containing the stable companion profile. */
    @Nonnull
    public TerminalResult cancelByProfileId(@Nonnull Object storeScope, @Nonnull String profileId) {
        return state(storeScope).cancelByProfileId(profileId);
    }

    /** Reads an immutable current or terminal job snapshot. */
    @Nonnull
    public Optional<BreedingBirthJob> find(@Nonnull Object storeScope, @Nonnull UUID jobId) {
        return state(storeScope).find(jobId);
    }

    /** Returns the number of non-terminal jobs in the store scope. */
    public int activeJobCount(@Nonnull Object storeScope) {
        return state(storeScope).activeJobCount();
    }

    /**
     * Closes a store scope and releases every active and terminal registry record.
     *
     * <p>Late callbacks against the closed scope remain rejected instead of recreating state.
     */
    public void clearScope(@Nonnull Object storeScope) {
        state(storeScope).closeAndClear();
    }

    @Nonnull
    private ScopeState state(@Nonnull Object storeScope) {
        Objects.requireNonNull(storeScope, "storeScope");
        return statesByStore.get(storeScope);
    }

    /** Admission outcomes are explicit so idempotent replay is not confused with acceptance. */
    public enum AdmissionStatus {
        ACCEPTED,
        ALREADY_REGISTERED,
        JOB_ID_CONFLICT,
        PAIR_BUSY,
        PARENT_BUSY,
        PROFILE_BUSY,
        WORLD_SCOPE_MISMATCH,
        SCOPE_CLOSED
    }

    /** Immutable result of attempting to register a reserved job. */
    public record AdmissionResult(@Nonnull AdmissionStatus status,
                                  @Nonnull Optional<BreedingBirthJob> job) {
        public AdmissionResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(job, "job");
        }
    }

    /** State-transition outcomes for non-spawn presentation steps. */
    public enum TransitionStatus {
        APPLIED,
        NOT_FOUND,
        STATE_MISMATCH,
        INVALID_TRANSITION,
        TERMINAL,
        SCOPE_CLOSED
    }

    /** Immutable result of a compare-and-set presentation transition. */
    public record TransitionResult(@Nonnull TransitionStatus status,
                                   @Nonnull Optional<BreedingBirthJob> job) {
        public TransitionResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(job, "job");
        }
    }

    /** Guarded spawn-claim outcomes for delayed callbacks. */
    public enum SpawnClaimStatus {
        CLAIMED,
        ALREADY_CLAIMED,
        NOT_READY,
        NOT_FOUND,
        TERMINAL,
        SCOPE_CLOSED
    }

    /** Immutable result of the sole operation allowed to enter SPAWNING. */
    public record SpawnClaimResult(@Nonnull SpawnClaimStatus status,
                                   @Nonnull Optional<BreedingBirthJob> job) {
        public SpawnClaimResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(job, "job");
        }
    }

    /** Terminal transition outcomes shared by complete, cancel, and fail operations. */
    public enum TerminalStatus {
        APPLIED,
        NOT_FOUND,
        NOT_READY,
        ALREADY_TERMINAL,
        SCOPE_CLOSED
    }

    /** Immutable result carrying the final snapshot when one exists. */
    public record TerminalResult(@Nonnull TerminalStatus status,
                                 @Nonnull Optional<BreedingBirthJob> job) {
        public TerminalResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(job, "job");
        }
    }

    private static final class ScopeState {
        private final Map<UUID, BreedingBirthJob> jobsById = new HashMap<>();
        private final Map<BreedingPairKey, UUID> activeJobByPair = new HashMap<>();
        private final Map<UUID, UUID> activeJobByParentUuid = new HashMap<>();
        private final Map<String, UUID> activeJobByProfileId = new HashMap<>();
        private String worldId;
        private boolean closed;

        synchronized AdmissionResult register(BreedingBirthJob requested) {
            if (closed) {
                return admission(AdmissionStatus.SCOPE_CLOSED, null);
            }
            BreedingBirthJob existing = jobsById.get(requested.jobId());
            if (existing != null) {
                AdmissionStatus status = existing.hasSameIdentity(requested)
                        ? AdmissionStatus.ALREADY_REGISTERED
                        : AdmissionStatus.JOB_ID_CONFLICT;
                return admission(status, existing);
            }
            if (worldId != null && !worldId.equals(requested.pairKey().worldId())) {
                return admission(AdmissionStatus.WORLD_SCOPE_MISMATCH, null);
            }
            UUID pairJobId = activeJobByPair.get(requested.pairKey());
            if (pairJobId != null) {
                return admission(AdmissionStatus.PAIR_BUSY, jobsById.get(pairJobId));
            }
            UUID parentJobId = activeParentJobId(requested);
            if (parentJobId != null) {
                return admission(AdmissionStatus.PARENT_BUSY, jobsById.get(parentJobId));
            }
            UUID profileJobId = activeProfileJobId(requested);
            if (profileJobId != null) {
                return admission(AdmissionStatus.PROFILE_BUSY, jobsById.get(profileJobId));
            }
            worldId = requested.pairKey().worldId();
            jobsById.put(requested.jobId(), requested);
            index(requested);
            return admission(AdmissionStatus.ACCEPTED, requested);
        }

        synchronized TransitionResult advance(UUID jobId,
                                              BreedingBirthJobState expectedState,
                                              BreedingBirthJobState nextState) {
            requireTransitionArguments(jobId, expectedState, nextState);
            if (closed) {
                return transition(TransitionStatus.SCOPE_CLOSED, null);
            }
            BreedingBirthJob current = jobsById.get(jobId);
            if (current == null) {
                return transition(TransitionStatus.NOT_FOUND, null);
            }
            if (current.state().isTerminal()) {
                return transition(TransitionStatus.TERMINAL, current);
            }
            if (current.state() != expectedState) {
                return transition(TransitionStatus.STATE_MISMATCH, current);
            }
            if (!expectedState.mayAdvanceTo(nextState)) {
                return transition(TransitionStatus.INVALID_TRANSITION, current);
            }
            BreedingBirthJob updated = current.withState(nextState);
            jobsById.put(jobId, updated);
            return transition(TransitionStatus.APPLIED, updated);
        }

        synchronized SpawnClaimResult claimSpawn(UUID jobId) {
            Objects.requireNonNull(jobId, "jobId");
            if (closed) {
                return spawnClaim(SpawnClaimStatus.SCOPE_CLOSED, null);
            }
            BreedingBirthJob current = jobsById.get(jobId);
            if (current == null) {
                return spawnClaim(SpawnClaimStatus.NOT_FOUND, null);
            }
            if (current.state() == BreedingBirthJobState.SPAWNING) {
                return spawnClaim(SpawnClaimStatus.ALREADY_CLAIMED, current);
            }
            if (current.state().isTerminal()) {
                return spawnClaim(SpawnClaimStatus.TERMINAL, current);
            }
            if (current.state() != BreedingBirthJobState.HEARTS_SHOWN) {
                return spawnClaim(SpawnClaimStatus.NOT_READY, current);
            }
            BreedingBirthJob updated = current.withState(BreedingBirthJobState.SPAWNING);
            jobsById.put(jobId, updated);
            return spawnClaim(SpawnClaimStatus.CLAIMED, updated);
        }

        synchronized TerminalResult finish(UUID jobId, BreedingBirthJobState outcome) {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(outcome, "outcome");
            if (!outcome.isTerminal()) {
                throw new IllegalArgumentException("Outcome must be terminal");
            }
            if (closed) {
                return terminal(TerminalStatus.SCOPE_CLOSED, null);
            }
            BreedingBirthJob current = jobsById.get(jobId);
            if (current == null) {
                return terminal(TerminalStatus.NOT_FOUND, null);
            }
            if (current.state().isTerminal()) {
                return terminal(TerminalStatus.ALREADY_TERMINAL, current);
            }
            if (outcome == BreedingBirthJobState.COMPLETED
                    && current.state() != BreedingBirthJobState.SPAWNING) {
                return terminal(TerminalStatus.NOT_READY, current);
            }
            BreedingBirthJob updated = current.withState(outcome);
            jobsById.put(jobId, updated);
            releaseIndexes(current);
            return terminal(TerminalStatus.APPLIED, updated);
        }

        synchronized TerminalResult cancelByParentUuid(UUID parentUuid) {
            Objects.requireNonNull(parentUuid, "parentUuid");
            if (closed) {
                return terminal(TerminalStatus.SCOPE_CLOSED, null);
            }
            UUID jobId = activeJobByParentUuid.get(parentUuid);
            return jobId == null
                    ? terminal(TerminalStatus.NOT_FOUND, null)
                    : finish(jobId, BreedingBirthJobState.CANCELLED);
        }

        synchronized TerminalResult cancelByProfileId(String profileId) {
            String normalizedProfileId = normalizeProfileId(profileId);
            if (closed) {
                return terminal(TerminalStatus.SCOPE_CLOSED, null);
            }
            UUID jobId = activeJobByProfileId.get(normalizedProfileId);
            return jobId == null
                    ? terminal(TerminalStatus.NOT_FOUND, null)
                    : finish(jobId, BreedingBirthJobState.CANCELLED);
        }

        synchronized Optional<BreedingBirthJob> find(UUID jobId) {
            Objects.requireNonNull(jobId, "jobId");
            if (closed) {
                return Optional.empty();
            }
            return Optional.ofNullable(jobsById.get(jobId));
        }

        synchronized int activeJobCount() {
            return closed ? 0 : activeJobByPair.size();
        }

        synchronized void closeAndClear() {
            closed = true;
            jobsById.clear();
            activeJobByPair.clear();
            activeJobByParentUuid.clear();
            activeJobByProfileId.clear();
            worldId = null;
        }

        private UUID activeParentJobId(BreedingBirthJob job) {
            UUID jobId = activeJobByParentUuid.get(job.firstParent().entityUuid());
            return jobId != null ? jobId : activeJobByParentUuid.get(job.secondParent().entityUuid());
        }

        private UUID activeProfileJobId(BreedingBirthJob job) {
            UUID jobId = activeJobByProfileId.get(job.firstParent().profileId());
            return jobId != null ? jobId : activeJobByProfileId.get(job.secondParent().profileId());
        }

        private void index(BreedingBirthJob job) {
            activeJobByPair.put(job.pairKey(), job.jobId());
            activeJobByParentUuid.put(job.firstParent().entityUuid(), job.jobId());
            activeJobByParentUuid.put(job.secondParent().entityUuid(), job.jobId());
            activeJobByProfileId.put(job.firstParent().profileId(), job.jobId());
            activeJobByProfileId.put(job.secondParent().profileId(), job.jobId());
        }

        private void releaseIndexes(BreedingBirthJob job) {
            activeJobByPair.remove(job.pairKey(), job.jobId());
            activeJobByParentUuid.remove(job.firstParent().entityUuid(), job.jobId());
            activeJobByParentUuid.remove(job.secondParent().entityUuid(), job.jobId());
            activeJobByProfileId.remove(job.firstParent().profileId(), job.jobId());
            activeJobByProfileId.remove(job.secondParent().profileId(), job.jobId());
        }

        private static void requireTransitionArguments(UUID jobId,
                                                       BreedingBirthJobState expectedState,
                                                       BreedingBirthJobState nextState) {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(expectedState, "expectedState");
            Objects.requireNonNull(nextState, "nextState");
        }

        private static String normalizeProfileId(String profileId) {
            Objects.requireNonNull(profileId, "profileId");
            String normalized = profileId.trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("profileId must not be blank");
            }
            return normalized;
        }

        private static AdmissionResult admission(AdmissionStatus status, BreedingBirthJob job) {
            return new AdmissionResult(status, Optional.ofNullable(job));
        }

        private static TransitionResult transition(TransitionStatus status, BreedingBirthJob job) {
            return new TransitionResult(status, Optional.ofNullable(job));
        }

        private static SpawnClaimResult spawnClaim(SpawnClaimStatus status, BreedingBirthJob job) {
            return new SpawnClaimResult(status, Optional.ofNullable(job));
        }

        private static TerminalResult terminal(TerminalStatus status, BreedingBirthJob job) {
            return new TerminalResult(status, Optional.ofNullable(job));
        }
    }
}
