package com.alechilles.alecstamework.ownership.reconciliation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Debounces physical/lifecycle observations per profile and serializes retryable revision races.
 */
public final class CoalescedCompanionPopulationWriter implements AutoCloseable {
    public static final long DEFAULT_COALESCE_DELAY_MS = 1_000L;
    public static final long DEFAULT_RETRY_DELAY_MS = 500L;

    private final Object lock = new Object();
    private final CompanionPopulationObservationPersistence persistence;
    private volatile Listener listener;
    private final ScheduledExecutorService executor;
    private final long coalesceDelayMs;
    private final long retryDelayMs;
    private final Map<String, Slot> slots = new HashMap<>();
    private boolean closed;
    private long observations;
    private long submissions;
    private long coalesced;

    public CoalescedCompanionPopulationWriter(
            @Nonnull CompanionPopulationObservationPersistence persistence,
            @Nonnull Listener listener
    ) {
        this(
                persistence,
                listener,
                newExecutor(),
                DEFAULT_COALESCE_DELAY_MS,
                DEFAULT_RETRY_DELAY_MS
        );
    }

    CoalescedCompanionPopulationWriter(
            @Nonnull CompanionPopulationObservationPersistence persistence,
            @Nonnull Listener listener,
            @Nonnull ScheduledExecutorService executor,
            long coalesceDelayMs,
            long retryDelayMs
    ) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.executor = Objects.requireNonNull(executor, "executor");
        if (coalesceDelayMs < 0L || retryDelayMs < 0L) {
            throw new IllegalArgumentException("Writer delays must be non-negative.");
        }
        this.coalesceDelayMs = coalesceDelayMs;
        this.retryDelayMs = retryDelayMs;
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener == null ? (observation, result) -> { } : listener;
    }

    public boolean record(@Nonnull CompanionPopulationObservation observation) {
        Objects.requireNonNull(observation, "observation");
        ScheduleRequest schedule = null;
        synchronized (lock) {
            if (closed) {
                return false;
            }
            observations++;
            Slot slot = slots.computeIfAbsent(observation.profileId(), ignored -> new Slot());
            if (Objects.equals(slot.latest, observation)) {
                coalesced++;
                return true;
            }
            if (slot.latest != null) {
                coalesced++;
            }
            slot.latest = observation;
            slot.version++;
            if (!slot.inFlight && slot.scheduled == null) {
                schedule = requestScheduleLocked(
                        observation.profileId(), slot, coalesceDelayMs
                );
            }
        }
        return schedule == null || scheduleOutside(schedule);
    }

    /** Flushes the current latest value for every profile without waiting for the debounce delay. */
    @Nonnull
    public CompletableFuture<Void> flushPendingNow() {
        Map<String, Long> cutoffs = new HashMap<>();
        synchronized (lock) {
            for (Map.Entry<String, Slot> entry : slots.entrySet()) {
                cutoffs.put(entry.getKey(), entry.getValue().version);
            }
        }
        List<CompletableFuture<Void>> futures = new ArrayList<>(cutoffs.size());
        for (Map.Entry<String, Long> cutoff : cutoffs.entrySet()) {
            futures.add(flushThrough(cutoff.getKey(), cutoff.getValue()));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    /**
     * Attempts every currently pending profile through its latest observed version once.
     * Retryable results remain queued, but do not keep callers waiting for the operation that
     * currently fences their persistence to become terminal.
     */
    @Nonnull
    public CompletableFuture<Void> flushCurrentAttemptsNow() {
        Map<String, Long> cutoffs = new HashMap<>();
        synchronized (lock) {
            for (Map.Entry<String, Slot> entry : slots.entrySet()) {
                cutoffs.put(entry.getKey(), entry.getValue().version);
            }
        }
        List<CompletableFuture<Void>> futures = new ArrayList<>(cutoffs.size());
        for (Map.Entry<String, Long> cutoff : cutoffs.entrySet()) {
            futures.add(flushAttemptThrough(cutoff.getKey(), cutoff.getValue()));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    @Nonnull
    private CompletableFuture<Void> flushThrough(@Nonnull String profileId, long cutoffVersion) {
        return flushProfile(profileId).thenCompose(ignored -> {
            boolean retryDelayed;
            synchronized (lock) {
                Slot slot = slots.get(profileId);
                if (slot == null || slot.persistedVersion >= cutoffVersion || closed) {
                    return CompletableFuture.completedFuture(null);
                }
                retryDelayed = slot.lastAttemptRetryable;
            }
            return retryDelayed
                    ? delay(retryDelayMs).thenCompose(value -> flushThrough(profileId, cutoffVersion))
                    : flushThrough(profileId, cutoffVersion);
        });
    }

    @Nonnull
    private CompletableFuture<Void> flushAttemptThrough(
            @Nonnull String profileId,
            long cutoffVersion
    ) {
        return flushProfile(profileId).thenCompose(ignored -> {
            synchronized (lock) {
                Slot slot = slots.get(profileId);
                if (slot == null || slot.completedAttemptVersion >= cutoffVersion || closed) {
                    return CompletableFuture.completedFuture(null);
                }
            }
            return flushAttemptThrough(profileId, cutoffVersion);
        });
    }

    @Nonnull
    public Metrics metrics() {
        synchronized (lock) {
            int inFlight = 0;
            for (Slot slot : slots.values()) {
                if (slot.inFlight) {
                    inFlight++;
                }
            }
            return new Metrics(slots.size(), inFlight, observations, submissions, coalesced);
        }
    }

    @Nonnull
    private CompletableFuture<Void> flushProfile(@Nonnull String profileId) {
        CompanionPopulationObservation observation;
        long version;
        CompletableFuture<Void> completion;
        ScheduledFuture<?> scheduledToCancel;
        synchronized (lock) {
            Slot slot = slots.get(profileId);
            if (slot == null) {
                return CompletableFuture.completedFuture(null);
            }
            if (slot.inFlight) {
                return Objects.requireNonNull(
                        slot.inFlightCompletion,
                        "In-flight population observation is missing its completion future."
                );
            }
            scheduledToCancel = detachScheduledLocked(slot);
            observation = slot.latest;
            version = slot.version;
            slot.inFlight = true;
            slot.inFlightCompletion = new CompletableFuture<>();
            completion = slot.inFlightCompletion;
            submissions++;
        }
        cancelOutside(scheduledToCancel);

        CompletableFuture<CompanionPopulationObservationPersistResult> persisted;
        try {
            persisted = persistence.persistAsync(observation);
        } catch (Throwable throwable) {
            persisted = CompletableFuture.failedFuture(throwable);
        }
        persisted.whenComplete((result, failure) -> {
            try {
                complete(profileId, observation, version, result, failure);
                completion.complete(null);
            } catch (Throwable throwable) {
                completion.completeExceptionally(throwable);
            }
        });
        return completion;
    }

    private void complete(@Nonnull String profileId,
                          @Nonnull CompanionPopulationObservation observation,
                          long version,
                          @Nullable CompanionPopulationObservationPersistResult result,
                          @Nullable Throwable failure) {
        CompanionPopulationObservationPersistResult effective = result;
        if (failure != null || effective == null) {
            effective = new CompanionPopulationObservationPersistResult(
                    CompanionPopulationObservationPersistResult.Status.FAILED,
                    observation.expectedRevision(),
                    failure == null ? "observation-write-failed" : failure.getClass().getSimpleName()
            );
        }
        Throwable listenerFailure = null;
        ScheduleRequest schedule = null;
        try {
            listener.onCompleted(observation, effective);
        } catch (Throwable throwable) {
            listenerFailure = throwable;
        }
        synchronized (lock) {
            Slot slot = slots.get(profileId);
            if (slot != null) {
                slot.inFlight = false;
                slot.inFlightCompletion = null;
                slot.completedAttemptVersion = Math.max(slot.completedAttemptVersion, version);
                slot.lastAttemptRetryable = effective.retryable();
                if (closed) {
                    slots.remove(profileId);
                } else {
                    schedule = completeSlot(profileId, version, effective, slot);
                }
            }
        }
        if (schedule != null) {
            scheduleOutside(schedule);
        }
        if (listenerFailure != null) {
            throw new IllegalStateException("Population observation listener failed.", listenerFailure);
        }
    }

    @Nullable
    private ScheduleRequest completeSlot(
            @Nonnull String profileId,
            long version,
            @Nonnull CompanionPopulationObservationPersistResult effective,
            @Nonnull Slot slot
    ) {
        boolean newerObservation = slot.version != version;
        if (effective.persisted()) {
            slot.persistedVersion = Math.max(slot.persistedVersion, version);
            if (!newerObservation) {
                slots.remove(profileId);
                return null;
            }
            slot.latest = slot.latest.withExpectedRevision(effective.revision());
            return requestScheduleLocked(profileId, slot, coalesceDelayMs);
        }
        if (effective.retryable()) {
            slot.latest = slot.latest.withExpectedRevision(effective.revision());
            return requestScheduleLocked(profileId, slot, retryDelayMs);
        }
        slots.remove(profileId);
        return null;
    }

    @Nullable
    private ScheduleRequest requestScheduleLocked(
            @Nonnull String profileId,
            @Nonnull Slot slot,
            long delayMs
    ) {
        if (slot.inFlight || slot.scheduled != null || slot.schedulePending) {
            return null;
        }
        slot.schedulePending = true;
        return new ScheduleRequest(profileId, slot, slot.latest, slot.version, delayMs);
    }

    private boolean scheduleOutside(@Nonnull ScheduleRequest request) {
        final ScheduledFuture<?> scheduled;
        try {
            scheduled = executor.schedule(
                    () -> flushProfile(request.profileId()),
                    request.delayMs(),
                    TimeUnit.MILLISECONDS
            );
        } catch (RuntimeException failure) {
            synchronized (lock) {
                Slot current = slots.get(request.profileId());
                if (current == request.slot()) {
                    current.schedulePending = false;
                }
            }
            complete(
                    request.profileId(), request.observation(), request.version(), null, failure
            );
            return false;
        }
        boolean discard;
        synchronized (lock) {
            Slot current = slots.get(request.profileId());
            if (closed || current != request.slot() || !current.schedulePending
                    || current.inFlight) {
                discard = true;
            } else {
                current.schedulePending = false;
                current.scheduled = scheduled;
                discard = false;
            }
        }
        if (discard) {
            scheduled.cancel(false);
        }
        return true;
    }

    @Nonnull
    private CompletableFuture<Void> delay(long delayMs) {
        CompletableFuture<Void> delayed = new CompletableFuture<>();
        try {
            executor.schedule(() -> delayed.complete(null), delayMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException exception) {
            delayed.completeExceptionally(exception);
        }
        return delayed;
    }

    @Nullable
    private static ScheduledFuture<?> detachScheduledLocked(@Nonnull Slot slot) {
        slot.schedulePending = false;
        ScheduledFuture<?> scheduled = slot.scheduled;
        slot.scheduled = null;
        return scheduled;
    }

    private static void cancelOutside(@Nullable ScheduledFuture<?> scheduled) {
        if (scheduled != null) {
            scheduled.cancel(false);
        }
    }

    @Override
    public void close() {
        List<ScheduledFuture<?>> scheduled = new ArrayList<>();
        synchronized (lock) {
            closed = true;
            for (Slot slot : slots.values()) {
                ScheduledFuture<?> detached = detachScheduledLocked(slot);
                if (detached != null) {
                    scheduled.add(detached);
                }
            }
            slots.clear();
        }
        for (ScheduledFuture<?> future : scheduled) {
            cancelOutside(future);
        }
        executor.shutdownNow();
    }

    @Nonnull
    private static ScheduledExecutorService newExecutor() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "tamework-population-observation-writer");
            thread.setDaemon(true);
            return thread;
        });
    }

    @FunctionalInterface
    public interface Listener {
        void onCompleted(@Nonnull CompanionPopulationObservation observation,
                         @Nonnull CompanionPopulationObservationPersistResult result);
    }

    public record Metrics(int pendingProfiles,
                          int inFlightProfiles,
                          long observations,
                          long submissions,
                          long coalescedObservations) {
    }

    private static final class Slot {
        private CompanionPopulationObservation latest;
        private long version;
        private long persistedVersion = Long.MIN_VALUE;
        private long completedAttemptVersion = Long.MIN_VALUE;
        private boolean inFlight;
        private boolean schedulePending;
        private boolean lastAttemptRetryable;
        private ScheduledFuture<?> scheduled;
        private CompletableFuture<Void> inFlightCompletion;
    }

    private record ScheduleRequest(@Nonnull String profileId,
                                   @Nonnull Slot slot,
                                   @Nonnull CompanionPopulationObservation observation,
                                   long version,
                                   long delayMs) {
    }
}
