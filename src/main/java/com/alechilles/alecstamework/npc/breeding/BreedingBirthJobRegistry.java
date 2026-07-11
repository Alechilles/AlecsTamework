package com.alechilles.alecstamework.npc.breeding;

import com.alechilles.alecstamework.util.StoreScopedState;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Thread-safe, store-scoped registry that guards delayed breeding birth callbacks.
 *
 * <p>Job registration installs pair, parent, profile, and exact capacity reservations atomically.
 * Terminal jobs remain addressable until scope cleanup, so replaying an old callback cannot create
 * another litter. Global job lookup exposes immutable identity only and never retains a live store.
 */
public final class BreedingBirthJobRegistry {
    private final Object lock = new Object();
    private final StoreScopedState<BreedingBirthJobScopeState> statesByStore =
            new StoreScopedState<>(BreedingBirthJobScopeState::new);
    private final Map<UUID, JobLocator> locatorsByJobId = new HashMap<>();
    private boolean closed;

    /** Attempts to atomically admit a new reserved job into the supplied store scope. */
    @Nonnull
    public AdmissionResult register(@Nonnull Object storeScope, @Nonnull BreedingBirthJob job) {
        Objects.requireNonNull(storeScope, "storeScope");
        Objects.requireNonNull(job, "job");
        if (job.state() != BreedingBirthJobState.RESERVED) {
            throw new IllegalArgumentException("New jobs must be in RESERVED state");
        }
        synchronized (lock) {
            if (closed) {
                return new AdmissionResult(AdmissionStatus.SCOPE_CLOSED, Optional.empty());
            }
            BreedingBirthJobScopeState state = statesByStore.get(storeScope);
            JobLocator existingLocator = liveLocator(job.jobId());
            if (existingLocator != null && !existingLocator.matchesScope(storeScope)) {
                return new AdmissionResult(
                        AdmissionStatus.JOB_ID_CONFLICT,
                        existingLocator.state.find(job.jobId())
                );
            }
            AdmissionResult result = state.register(job);
            if (result.status() == AdmissionStatus.ACCEPTED) {
                locatorsByJobId.put(job.jobId(), new JobLocator(storeScope, state));
            }
            return result;
        }
    }

    /** Advances a presentation step using an expected-state compare-and-set. */
    @Nonnull
    public TransitionResult advance(@Nonnull Object storeScope,
                                    @Nonnull UUID jobId,
                                    @Nonnull BreedingBirthJobState expectedState,
                                    @Nonnull BreedingBirthJobState nextState) {
        requireTransitionArguments(storeScope, jobId, expectedState, nextState);
        synchronized (lock) {
            if (closed) {
                return new TransitionResult(TransitionStatus.SCOPE_CLOSED, Optional.empty());
            }
            return statesByStore.get(storeScope).advance(jobId, expectedState, nextState);
        }
    }

    /** Atomically grants the sole transition into SPAWNING for a delayed callback. */
    @Nonnull
    public SpawnClaimResult claimSpawn(@Nonnull Object storeScope, @Nonnull UUID jobId) {
        Objects.requireNonNull(storeScope, "storeScope");
        Objects.requireNonNull(jobId, "jobId");
        synchronized (lock) {
            if (closed) {
                return new SpawnClaimResult(SpawnClaimStatus.SCOPE_CLOSED, Optional.empty());
            }
            return statesByStore.get(storeScope).claimSpawn(jobId);
        }
    }

    /** Shrinks an admission to an ordered child subsequence and updates exact counts atomically. */
    @Nonnull
    public AdmissionUpdateResult shrinkAdmission(@Nonnull Object storeScope,
                                                  @Nonnull UUID jobId,
                                                  @Nonnull List<PlannedChild> retainedChildren) {
        Objects.requireNonNull(storeScope, "storeScope");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(retainedChildren, "retainedChildren");
        synchronized (lock) {
            if (closed) {
                return new AdmissionUpdateResult(AdmissionUpdateStatus.SCOPE_CLOSED, Optional.empty());
            }
            return statesByStore.get(storeScope).shrinkAdmission(jobId, retainedChildren);
        }
    }

    /** Releases the exact outstanding reservation for one successful or abandoned planned child. */
    @Nonnull
    public ReservationReleaseResult releaseChildReservation(@Nonnull Object storeScope,
                                                             @Nonnull UUID jobId,
                                                             @Nonnull PlannedChild child) {
        Objects.requireNonNull(storeScope, "storeScope");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(child, "child");
        synchronized (lock) {
            if (closed) {
                return new ReservationReleaseResult(ReservationReleaseStatus.SCOPE_CLOSED, Optional.empty());
            }
            return statesByStore.get(storeScope).releaseChildReservation(jobId, child);
        }
    }

    /** Marks a claimed spawn job completed and releases its active indexes and reservations. */
    @Nonnull
    public TerminalResult complete(@Nonnull Object storeScope, @Nonnull UUID jobId) {
        return finish(storeScope, jobId, BreedingBirthJobState.COMPLETED);
    }

    /** Marks an active job failed and releases its active indexes and reservations. */
    @Nonnull
    public TerminalResult fail(@Nonnull Object storeScope, @Nonnull UUID jobId) {
        return finish(storeScope, jobId, BreedingBirthJobState.FAILED);
    }

    /** Cancels an active job by job ID. */
    @Nonnull
    public TerminalResult cancel(@Nonnull Object storeScope, @Nonnull UUID jobId) {
        return finish(storeScope, jobId, BreedingBirthJobState.CANCELLED);
    }

    /** Cancels the active job, if any, containing the current entity UUID. */
    @Nonnull
    public TerminalResult cancelByParentUuid(@Nonnull Object storeScope, @Nonnull UUID parentUuid) {
        Objects.requireNonNull(storeScope, "storeScope");
        Objects.requireNonNull(parentUuid, "parentUuid");
        synchronized (lock) {
            if (closed) {
                return new TerminalResult(TerminalStatus.SCOPE_CLOSED, Optional.empty());
            }
            return statesByStore.get(storeScope).cancelByParentUuid(parentUuid);
        }
    }

    /** Cancels the active job, if any, containing the stable companion profile. */
    @Nonnull
    public TerminalResult cancelByProfileId(@Nonnull Object storeScope, @Nonnull String profileId) {
        Objects.requireNonNull(storeScope, "storeScope");
        String normalizedProfileId = normalizeProfileId(profileId);
        synchronized (lock) {
            if (closed) {
                return new TerminalResult(TerminalStatus.SCOPE_CLOSED, Optional.empty());
            }
            return statesByStore.get(storeScope).cancelByProfileId(normalizedProfileId);
        }
    }

    /** Reads an immutable current or terminal job snapshot from one store scope. */
    @Nonnull
    public Optional<BreedingBirthJob> find(@Nonnull Object storeScope, @Nonnull UUID jobId) {
        Objects.requireNonNull(storeScope, "storeScope");
        Objects.requireNonNull(jobId, "jobId");
        synchronized (lock) {
            return closed ? Optional.empty() : statesByStore.get(storeScope).find(jobId);
        }
    }

    /** Locates a current or terminal job globally using only stable immutable identity. */
    @Nonnull
    public Optional<LocatedJob> locate(@Nonnull UUID jobId) {
        Objects.requireNonNull(jobId, "jobId");
        synchronized (lock) {
            if (closed) {
                return Optional.empty();
            }
            JobLocator locator = liveLocator(jobId);
            if (locator == null) {
                return Optional.empty();
            }
            return locator.state.find(jobId).map(job -> new LocatedJob(job.pairKey().worldId(), job));
        }
    }

    /** Returns the number of non-terminal jobs in one store scope. */
    public int activeJobCount(@Nonnull Object storeScope) {
        Objects.requireNonNull(storeScope, "storeScope");
        synchronized (lock) {
            return closed ? 0 : statesByStore.get(storeScope).activeJobCount();
        }
    }

    /** Returns deterministic exact active reservations for one store scope. */
    @Nonnull
    public List<BreedingActiveReservation> activeReservations(@Nonnull Object storeScope) {
        Objects.requireNonNull(storeScope, "storeScope");
        synchronized (lock) {
            return closed ? List.of() : statesByStore.get(storeScope).activeReservations();
        }
    }

    /** Returns deterministic exact active reservations across all currently reachable scopes. */
    @Nonnull
    public List<BreedingActiveReservation> activeReservations() {
        synchronized (lock) {
            if (closed) {
                return List.of();
            }
            pruneExpiredLocators();
            Set<BreedingBirthJobScopeState> states = Collections.newSetFromMap(new IdentityHashMap<>());
            for (JobLocator locator : locatorsByJobId.values()) {
                states.add(locator.state);
            }
            ArrayList<BreedingActiveReservation> reservations = new ArrayList<>();
            for (BreedingBirthJobScopeState state : states) {
                reservations.addAll(state.activeReservations());
            }
            reservations.sort(null);
            return List.copyOf(reservations);
        }
    }

    /** Closes one scope, releasing its jobs, indexes, reservations, and global locators. */
    public void clearScope(@Nonnull Object storeScope) {
        Objects.requireNonNull(storeScope, "storeScope");
        synchronized (lock) {
            if (closed) {
                return;
            }
            BreedingBirthJobScopeState state = statesByStore.get(storeScope);
            state.closeAndClear();
            locatorsByJobId.entrySet().removeIf(entry -> entry.getValue().state == state);
        }
    }

    /** Permanently closes the registry and releases every reachable scope. */
    public void clearAll() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            Set<BreedingBirthJobScopeState> states = Collections.newSetFromMap(new IdentityHashMap<>());
            for (JobLocator locator : locatorsByJobId.values()) {
                states.add(locator.state);
            }
            for (BreedingBirthJobScopeState state : states) {
                state.closeAndClear();
            }
            locatorsByJobId.clear();
            closed = true;
        }
    }

    @Nonnull
    private TerminalResult finish(Object storeScope, UUID jobId, BreedingBirthJobState outcome) {
        Objects.requireNonNull(storeScope, "storeScope");
        Objects.requireNonNull(jobId, "jobId");
        synchronized (lock) {
            if (closed) {
                return new TerminalResult(TerminalStatus.SCOPE_CLOSED, Optional.empty());
            }
            return statesByStore.get(storeScope).finish(jobId, outcome);
        }
    }

    private JobLocator liveLocator(UUID jobId) {
        JobLocator locator = locatorsByJobId.get(jobId);
        if (locator == null || locator.scope.get() != null) {
            return locator;
        }
        BreedingBirthJobScopeState expiredState = locator.state;
        expiredState.closeAndClear();
        locatorsByJobId.entrySet().removeIf(entry -> entry.getValue().state == expiredState);
        return null;
    }

    private void pruneExpiredLocators() {
        ArrayList<BreedingBirthJobScopeState> expiredStates = new ArrayList<>();
        for (JobLocator locator : locatorsByJobId.values()) {
            if (locator.scope.get() == null && !expiredStates.contains(locator.state)) {
                expiredStates.add(locator.state);
            }
        }
        for (BreedingBirthJobScopeState state : expiredStates) {
            state.closeAndClear();
            locatorsByJobId.entrySet().removeIf(entry -> entry.getValue().state == state);
        }
    }

    private static void requireTransitionArguments(Object storeScope,
                                                   UUID jobId,
                                                   BreedingBirthJobState expectedState,
                                                   BreedingBirthJobState nextState) {
        Objects.requireNonNull(storeScope, "storeScope");
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

    private static final class JobLocator {
        private final WeakReference<Object> scope;
        private final BreedingBirthJobScopeState state;

        private JobLocator(Object scope, BreedingBirthJobScopeState state) {
            this.scope = new WeakReference<>(scope);
            this.state = state;
        }

        private boolean matchesScope(Object expectedScope) {
            return scope.get() == expectedScope;
        }
    }

    /** Admission outcomes distinguish idempotent replay from new acceptance. */
    public enum AdmissionStatus {
        ACCEPTED, ALREADY_REGISTERED, JOB_ID_CONFLICT, PAIR_BUSY, PARENT_BUSY, PROFILE_BUSY,
        WORLD_SCOPE_MISMATCH, SCOPE_CLOSED
    }

    public record AdmissionResult(@Nonnull AdmissionStatus status,
                                  @Nonnull Optional<BreedingBirthJob> job) {
        public AdmissionResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(job, "job");
        }
    }

    public enum TransitionStatus {
        APPLIED, NOT_FOUND, STATE_MISMATCH, INVALID_TRANSITION, TERMINAL, SCOPE_CLOSED
    }

    public record TransitionResult(@Nonnull TransitionStatus status,
                                   @Nonnull Optional<BreedingBirthJob> job) {
        public TransitionResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(job, "job");
        }
    }

    public enum SpawnClaimStatus {
        CLAIMED, ALREADY_CLAIMED, NOT_READY, NOT_FOUND, TERMINAL, SCOPE_CLOSED
    }

    public record SpawnClaimResult(@Nonnull SpawnClaimStatus status,
                                   @Nonnull Optional<BreedingBirthJob> job) {
        public SpawnClaimResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(job, "job");
        }
    }

    public enum AdmissionUpdateStatus {
        APPLIED, UNCHANGED, INVALID_SHRINK, NOT_FOUND, TERMINAL, SCOPE_CLOSED
    }

    public record AdmissionUpdateResult(@Nonnull AdmissionUpdateStatus status,
                                        @Nonnull Optional<BreedingBirthJob> job) {
        public AdmissionUpdateResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(job, "job");
        }
    }

    public enum ReservationReleaseStatus {
        RELEASED, CHILD_NOT_RESERVED, NOT_FOUND, TERMINAL, SCOPE_CLOSED
    }

    public record ReservationReleaseResult(@Nonnull ReservationReleaseStatus status,
                                           @Nonnull Optional<BreedingBirthJob> job) {
        public ReservationReleaseResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(job, "job");
        }
    }

    public enum TerminalStatus {
        APPLIED, NOT_FOUND, NOT_READY, ALREADY_TERMINAL, SCOPE_CLOSED
    }

    public record TerminalResult(@Nonnull TerminalStatus status,
                                 @Nonnull Optional<BreedingBirthJob> job) {
        public TerminalResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(job, "job");
        }
    }

    /** Store-free global job lookup result for delayed schedulers. */
    public record LocatedJob(@Nonnull String worldId, @Nonnull BreedingBirthJob job) {
        public LocatedJob {
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(job, "job");
        }
    }
}
