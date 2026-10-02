package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One per server (spec 8.4): keeps LIVE records with a summon timer ordered by expiry and stores
 * each one when its wall-clock time passes. Rebuilt from the index at start; fed by the index
 * change listener. {@link #due(long)} is pure so the timing is testable; {@link #start} polls it
 * once a second on the given executor and hands each due id to the store action, which hops to
 * the body's world itself. The executor's owner stops the polling by shutting it down.
 *
 * <p>{@link #onChange} runs under the index lock, so it only updates small maps under this
 * object's own lock. {@link #due} takes only this lock, never the index lock. A store that ends
 * in anything but STORED, NOT_LIVE, NOT_FOUND or NO_SNAPSHOT (logged once, not retried) is tried again {@link #RETRY_DELAY_MS} after the
 * poll that started it; the store action re-checks the record, so a leftover retry is harmless.
 */
public final class SummonExpiryScheduler {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final long POLL_INTERVAL_MS = 1_000L;
    static final long RETRY_DELAY_MS = 30_000L;
    private static final Comparator<Entry> ORDER =
            Comparator.comparingLong(Entry::untilMs).thenComparing(Entry::profileId);

    private record Entry(long untilMs, @Nonnull UUID profileId) {
    }

    private final Function<UUID, CompletableFuture<StoreFlow.Result>> storeExpired;
    private final Object lock = new Object();
    private final TreeSet<Entry> byExpiry = new TreeSet<>(ORDER);
    private final Map<UUID, Long> untilById = new HashMap<>();

    /**
     * @param storeExpired stores one expired companion and completes with the store's result; must
     *                     not block, and is called on the polling thread
     */
    public SummonExpiryScheduler(@Nonnull Function<UUID, CompletableFuture<StoreFlow.Result>> storeExpired) {
        this.storeExpired = Objects.requireNonNull(storeExpired, "storeExpired");
    }

    /** Replaces what is tracked with every LIVE record that has a summon timer. */
    public void rebuild(@Nonnull CompanionIndex index) {
        synchronized (lock) {
            byExpiry.clear();
            untilById.clear();
            index.forEach(record -> {
                if (timed(record)) {
                    track(record.profileId(), record.summonedUntilMs());
                }
            });
        }
    }

    /** Index change listener. Cheap and non-blocking: it runs under the index lock. */
    public void onChange(@Nullable CompanionRecord before, @Nonnull CompanionRecord after) {
        synchronized (lock) {
            if (timed(after)) {
                track(after.profileId(), after.summonedUntilMs());
            } else {
                drop(after.profileId());
            }
        }
    }

    /** Tracks {@code profileId} to come due at {@code atMs}, or later if a newer timer is tracked. */
    void retryAt(@Nonnull UUID profileId, long atMs) {
        synchronized (lock) {
            // A newer timer tracked since the poll (a re-summon) must not be pulled earlier or lost.
            Long current = untilById.get(profileId);
            track(profileId, current == null ? atMs : Math.max(atMs, current));
        }
    }

    /** Removes and returns the ids whose timer is at or before {@code nowMs}, earliest first. */
    @Nonnull
    public List<UUID> due(long nowMs) {
        List<UUID> expired = new ArrayList<>();
        synchronized (lock) {
            while (!byExpiry.isEmpty() && byExpiry.first().untilMs() <= nowMs) {
                Entry entry = byExpiry.pollFirst();
                untilById.remove(entry.profileId());
                expired.add(entry.profileId());
            }
        }
        return expired;
    }

    /** Polls {@link #due} once a second on {@code executor} with the wall clock {@code clock}. */
    public void start(@Nonnull ScheduledExecutorService executor, @Nonnull LongSupplier clock) {
        Objects.requireNonNull(clock, "clock");
        executor.scheduleWithFixedDelay(() -> poll(clock.getAsLong()), POLL_INTERVAL_MS, POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    /** One poll at {@code nowMs}. Never throws: an exception would cancel every later poll. */
    void poll(long nowMs) {
        try {
            for (UUID profileId : due(nowMs)) {
                CompletableFuture<StoreFlow.Result> stored;
                try {
                    stored = storeExpired.apply(profileId);
                } catch (RuntimeException failure) {
                    stored = CompletableFuture.failedFuture(failure);
                }
                stored.whenComplete((result, error) -> {
                    if (error == null && result == StoreFlow.Result.NO_SNAPSHOT) {
                        // Nothing a retry could change: no body is loaded and no snapshot exists.
                        // The timer is dropped; the owner's next summon recovers the companion.
                        LOGGER.at(Level.WARNING).log("Expired companion %s has no loaded body and no snapshot to "
                                + "store; its summon timer is dropped", profileId);
                        return;
                    }
                    if (error != null || !finished(result)) {
                        retryAt(profileId, nowMs + RETRY_DELAY_MS);
                    }
                });
            }
        } catch (RuntimeException failure) {
            LOGGER.at(Level.WARNING).withCause(failure).log("Summon expiry poll failed");
        }
    }

    /** The companion was stored, or is no longer a live companion to store. */
    private static boolean finished(@Nullable StoreFlow.Result result) {
        return result == StoreFlow.Result.STORED || result == StoreFlow.Result.NOT_LIVE
                || result == StoreFlow.Result.NOT_FOUND;
    }

    private static boolean timed(CompanionRecord record) {
        return record.location().kind() == LocationKind.LIVE && record.summonedUntilMs() != 0L;
    }

    private void track(UUID profileId, long untilMs) {
        Long previous = untilById.put(profileId, untilMs);
        if (previous != null) {
            byExpiry.remove(new Entry(previous, profileId));
        }
        byExpiry.add(new Entry(untilMs, profileId));
    }

    private void drop(UUID profileId) {
        Long previous = untilById.remove(profileId);
        if (previous != null) {
            byExpiry.remove(new Entry(previous, profileId));
        }
    }
}
