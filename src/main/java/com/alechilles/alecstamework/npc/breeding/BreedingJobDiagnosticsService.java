package com.alechilles.alecstamework.npc.breeding;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Thread-safe, store-scoped diagnostic companion to {@link BreedingBirthJobRegistry}.
 *
 * <p>This service intentionally does not influence admission, scheduling, spawning, or rollback.
 * It retains the latest job evidence for each parent until its store scope is cleared, while weak
 * scope locators prevent abandoned worlds from being retained by diagnostics.</p>
 */
public final class BreedingJobDiagnosticsService {
    static final int MAX_TERMINAL_SNAPSHOTS_PER_SCOPE = 128;

    private final Object lock = new Object();
    private final Map<Object, ScopeState> statesByStore = new WeakHashMap<>();
    private final Map<UUID, JobLocator> locatorsByJobId = new HashMap<>();
    private boolean closed;

    /** Registers a job for parent lookup before attaching admission details. */
    public boolean register(@Nonnull Object storeScope, @Nonnull BreedingBirthJob job) {
        Objects.requireNonNull(storeScope, "storeScope");
        Objects.requireNonNull(job, "job");
        synchronized (lock) {
            pruneExpiredScopes();
            if (closed) {
                return false;
            }
            JobLocator existing = liveLocator(job.jobId());
            if (existing != null && existing.scope.get() != storeScope) {
                return false;
            }
            ScopeState state = statesByStore.computeIfAbsent(storeScope, ignored -> new ScopeState());
            state.register(job);
            locatorsByJobId.put(job.jobId(), new JobLocator(storeScope, state));
            pruneLocatorsForState(state);
            return true;
        }
    }

    /** Attaches the exact initial population decision to an already registered job. */
    public boolean recordInitialAdmission(
            @Nonnull UUID jobId,
            @Nonnull BreedingPopulationAdmissionService.AdmissionRequest request,
            @Nonnull BreedingPopulationAdmissionService.AdmissionResult result) {
        return recordCapacity(jobId, request, result, true);
    }

    /** Attaches the exact spawn-time recheck and any capacity-driven admission shrink. */
    public boolean recordSpawnRecheck(
            @Nonnull UUID jobId,
            @Nonnull BreedingPopulationAdmissionService.AdmissionRequest request,
            @Nonnull BreedingPopulationAdmissionService.AdmissionResult result) {
        return recordCapacity(jobId, request, result, false);
    }

    /** Records one authoritative terminal outcome without changing gameplay job state. */
    public boolean recordOutcome(@Nonnull UUID jobId,
                                 @Nonnull BreedingJobDiagnosticSnapshot.Outcome outcome,
                                 int spawnedChildren,
                                 @Nullable String reason,
                                 @Nonnull BreedingJobDiagnosticSnapshot.RollbackStatus rollbackStatus,
                                 @Nullable String rollbackDetail) {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(rollbackStatus, "rollbackStatus");
        if (outcome == BreedingJobDiagnosticSnapshot.Outcome.ACTIVE || spawnedChildren < 0) {
            return false;
        }
        synchronized (lock) {
            pruneExpiredScopes();
            JobLocator locator = closed ? null : liveLocator(jobId);
            if (locator == null || !locator.state.recordOutcome(
                    jobId,
                    outcome,
                    spawnedChildren,
                    reason,
                    rollbackStatus,
                    rollbackDetail
            )) {
                return false;
            }
            pruneLocatorsForState(locator.state);
            return true;
        }
    }

    /** Reads one job diagnostic snapshot by immutable job ID. */
    @Nonnull
    public Optional<BreedingJobDiagnosticSnapshot> find(@Nonnull UUID jobId) {
        Objects.requireNonNull(jobId, "jobId");
        synchronized (lock) {
            pruneExpiredScopes();
            JobLocator locator = closed ? null : liveLocator(jobId);
            return locator != null ? locator.state.find(jobId) : Optional.empty();
        }
    }

    /** Reads the newest registered job for a parent's current entity UUID in one store. */
    @Nonnull
    public Optional<BreedingJobDiagnosticSnapshot> findLatestByParentUuid(
            @Nonnull Object storeScope,
            @Nonnull UUID parentUuid) {
        Objects.requireNonNull(storeScope, "storeScope");
        Objects.requireNonNull(parentUuid, "parentUuid");
        synchronized (lock) {
            pruneExpiredScopes();
            if (closed) {
                return Optional.empty();
            }
            ScopeState state = statesByStore.get(storeScope);
            return state != null ? state.findLatestByParentUuid(parentUuid) : Optional.empty();
        }
    }

    /** Clears one unloaded store without affecting other worlds. */
    public void clearScope(@Nonnull Object storeScope) {
        Objects.requireNonNull(storeScope, "storeScope");
        synchronized (lock) {
            pruneExpiredScopes();
            if (closed) {
                return;
            }
            ScopeState state = statesByStore.remove(storeScope);
            if (state == null) {
                return;
            }
            state.clear();
            locatorsByJobId.entrySet().removeIf(entry -> entry.getValue().state == state);
        }
    }

    /** Permanently closes this diagnostic bundle and drops all process-local history. */
    public void clearAll() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            for (ScopeState state : statesByStore.values()) {
                state.clear();
            }
            statesByStore.clear();
            locatorsByJobId.clear();
            closed = true;
        }
    }

    private boolean recordCapacity(UUID jobId,
                                   BreedingPopulationAdmissionService.AdmissionRequest request,
                                   BreedingPopulationAdmissionService.AdmissionResult result,
                                   boolean initial) {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
        if (!jobId.equals(request.jobId()) || !request.plan().equals(result.sourcePlan())) {
            return false;
        }
        BreedingJobDiagnosticSnapshot.CapacitySnapshot capacity;
        try {
            capacity = BreedingJobDiagnosticSnapshot.CapacitySnapshot.from(request, result);
        } catch (RuntimeException exception) {
            // Diagnostics must never interrupt an otherwise valid breeding transaction.
            return false;
        }
        synchronized (lock) {
            pruneExpiredScopes();
            JobLocator locator = closed ? null : liveLocator(jobId);
            return locator != null && locator.state.recordCapacity(jobId, capacity, initial);
        }
    }

    /** Sweeps every weakly expired store whenever diagnostics are otherwise accessed. */
    private void pruneExpiredScopes() {
        Set<ScopeState> expiredStates = new HashSet<>();
        for (JobLocator locator : locatorsByJobId.values()) {
            if (locator.scope.get() == null) {
                expiredStates.add(locator.state);
            }
        }
        for (ScopeState expired : expiredStates) {
            expired.clear();
            locatorsByJobId.entrySet().removeIf(entry -> entry.getValue().state == expired);
            statesByStore.values().removeIf(state -> state == expired);
        }
    }

    private void pruneLocatorsForState(ScopeState state) {
        locatorsByJobId.entrySet().removeIf(entry -> entry.getValue().state == state
                && !state.contains(entry.getKey()));
    }

    @Nullable
    private JobLocator liveLocator(UUID jobId) {
        JobLocator locator = locatorsByJobId.get(jobId);
        if (locator == null || locator.scope.get() != null) {
            return locator;
        }
        ScopeState expired = locator.state;
        expired.clear();
        locatorsByJobId.entrySet().removeIf(entry -> entry.getValue().state == expired);
        statesByStore.values().removeIf(state -> state == expired);
        return null;
    }

    private static final class ScopeState {
        private final Map<UUID, BreedingJobDiagnosticSnapshot> snapshotsByJobId = new HashMap<>();
        private final Map<UUID, UUID> latestJobByParentUuid = new HashMap<>();
        private final Map<UUID, Long> registrationOrderByJobId = new HashMap<>();
        private final Set<UUID> activeJobIds = new HashSet<>();
        private long nextRegistrationOrder;

        private void register(BreedingBirthJob job) {
            if (!snapshotsByJobId.containsKey(job.jobId())) {
                snapshotsByJobId.put(
                        job.jobId(),
                        BreedingJobDiagnosticSnapshot.registered(job.jobId())
                );
                registrationOrderByJobId.put(job.jobId(), nextRegistrationOrder++);
                activeJobIds.add(job.jobId());
            }
            latestJobByParentUuid.put(job.firstParent().entityUuid(), job.jobId());
            latestJobByParentUuid.put(job.secondParent().entityUuid(), job.jobId());
            pruneTerminalHistory();
        }

        private boolean recordCapacity(UUID jobId,
                                       BreedingJobDiagnosticSnapshot.CapacitySnapshot capacity,
                                       boolean initial) {
            BreedingJobDiagnosticSnapshot current = snapshotsByJobId.get(jobId);
            if (current == null) {
                return false;
            }
            snapshotsByJobId.put(
                    jobId,
                    initial ? current.withInitialCapacity(capacity) : current.withSpawnCapacity(capacity)
            );
            return true;
        }

        private boolean recordOutcome(UUID jobId,
                                      BreedingJobDiagnosticSnapshot.Outcome outcome,
                                      int spawnedChildren,
                                      String reason,
                                      BreedingJobDiagnosticSnapshot.RollbackStatus rollbackStatus,
                                      String rollbackDetail) {
            BreedingJobDiagnosticSnapshot current = snapshotsByJobId.get(jobId);
            if (current == null) {
                return false;
            }
            snapshotsByJobId.put(
                    jobId,
                    current.withOutcome(
                            outcome,
                            spawnedChildren,
                            reason,
                            rollbackStatus,
                            rollbackDetail
                    )
            );
            activeJobIds.remove(jobId);
            pruneTerminalHistory();
            return true;
        }

        private Optional<BreedingJobDiagnosticSnapshot> find(UUID jobId) {
            return Optional.ofNullable(snapshotsByJobId.get(jobId));
        }

        private Optional<BreedingJobDiagnosticSnapshot> findLatestByParentUuid(UUID parentUuid) {
            UUID jobId = latestJobByParentUuid.get(parentUuid);
            return jobId != null ? find(jobId) : Optional.empty();
        }

        private boolean contains(UUID jobId) {
            return snapshotsByJobId.containsKey(jobId);
        }

        /** Drops superseded terminal jobs, then caps remaining latest-per-parent history. */
        private void pruneTerminalHistory() {
            Set<UUID> latestJobs = new HashSet<>(latestJobByParentUuid.values());
            for (UUID jobId : new ArrayList<>(snapshotsByJobId.keySet())) {
                if (!activeJobIds.contains(jobId) && !latestJobs.contains(jobId)) {
                    remove(jobId);
                }
            }
            List<UUID> terminal = snapshotsByJobId.keySet().stream()
                    .filter(jobId -> !activeJobIds.contains(jobId))
                    .sorted(this::compareRegistrationOrder)
                    .toList();
            int excess = terminal.size() - MAX_TERMINAL_SNAPSHOTS_PER_SCOPE;
            for (int index = 0; index < excess; index++) {
                remove(terminal.get(index));
            }
        }

        private int compareRegistrationOrder(UUID left, UUID right) {
            int order = Long.compare(
                    registrationOrderByJobId.getOrDefault(left, Long.MIN_VALUE),
                    registrationOrderByJobId.getOrDefault(right, Long.MIN_VALUE)
            );
            return order != 0 ? order : left.compareTo(right);
        }

        private void remove(UUID jobId) {
            snapshotsByJobId.remove(jobId);
            activeJobIds.remove(jobId);
            registrationOrderByJobId.remove(jobId);
            latestJobByParentUuid.entrySet().removeIf(entry -> jobId.equals(entry.getValue()));
        }

        private void clear() {
            snapshotsByJobId.clear();
            latestJobByParentUuid.clear();
            registrationOrderByJobId.clear();
            activeJobIds.clear();
        }
    }

    private static final class JobLocator {
        private final WeakReference<Object> scope;
        private final ScopeState state;

        private JobLocator(Object scope, ScopeState state) {
            this.scope = new WeakReference<>(scope);
            this.state = state;
        }
    }
}
