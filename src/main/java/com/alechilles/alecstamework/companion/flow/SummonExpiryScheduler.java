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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
 * object's own lock. {@link #due} takes only this lock, never the index lock. A returned id is
 * not tracked again until its record changes, so a store that fails without changing the record
 * is retried on the record's next change (for example its body unloading). An undone store
 * changes the record; it comes due again no sooner than {@link #RETRY_DELAY_MS} later, so a
 * store that keeps failing is not retried every second.
 */
public final class SummonExpiryScheduler {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final long POLL_INTERVAL_MS = 1_000L;
    static final long RETRY_DELAY_MS = 30_000L;
    private static final Comparator<Entry> ORDER =
            Comparator.comparingLong(Entry::untilMs).thenComparing(Entry::profileId);

    private record Entry(long untilMs, @Nonnull UUID profileId) {
    }

    private final Consumer<UUID> storeExpired;
    private final Object lock = new Object();
    private final TreeSet<Entry> byExpiry = new TreeSet<>(ORDER);
    private final Map<UUID, Long> untilById = new HashMap<>();
    /** Ids returned by {@link #due} that are still timed, mapped to the earliest time they may come due again. */
    private final Map<UUID, Long> retryNotBefore = new HashMap<>();

    /** @param storeExpired stores one expired companion; must not block, and is called on the polling thread */
    public SummonExpiryScheduler(@Nonnull Consumer<UUID> storeExpired) {
        this.storeExpired = Objects.requireNonNull(storeExpired, "storeExpired");
    }

    /** Replaces what is tracked with every LIVE record that has a summon timer. */
    public void rebuild(@Nonnull CompanionIndex index) {
        synchronized (lock) {
            byExpiry.clear();
            untilById.clear();
            retryNotBefore.clear();
            index.forEach(record -> {
                if (timed(record)) {
                    track(record.profileId(), record.summonedUntilMs());
                }
            });
        }
    }

    /** Index change listener. Cheap and non-blocking: it runs under the index lock. */
    public void onChange(@Nullable CompanionRecord before, @Nullable CompanionRecord after) {
        CompanionRecord any = after != null ? after : before;
        if (any == null) {
            return;
        }
        synchronized (lock) {
            if (after != null && timed(after)) {
                track(after.profileId(), after.summonedUntilMs());
            } else {
                drop(any.profileId());
            }
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
                retryNotBefore.put(entry.profileId(), nowMs + RETRY_DELAY_MS);
                expired.add(entry.profileId());
            }
        }
        return expired;
    }

    /** Polls {@link #due} once a second on {@code executor} with the wall clock {@code clock}. */
    public void start(@Nonnull ScheduledExecutorService executor, @Nonnull LongSupplier clock) {
        Objects.requireNonNull(clock, "clock");
        executor.scheduleWithFixedDelay(() -> poll(clock), POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** Never throws: an exception would cancel every later poll. */
    private void poll(LongSupplier clock) {
        try {
            for (UUID profileId : due(clock.getAsLong())) {
                try {
                    storeExpired.accept(profileId);
                } catch (RuntimeException failure) {
                    LOGGER.at(Level.WARNING).withCause(failure)
                            .log("Could not store expired summon of companion %s", profileId);
                }
            }
        } catch (RuntimeException failure) {
            LOGGER.at(Level.WARNING).withCause(failure).log("Summon expiry poll failed");
        }
    }

    private static boolean timed(CompanionRecord record) {
        return record.location().kind() == LocationKind.LIVE && record.summonedUntilMs() != 0L;
    }

    private void track(UUID profileId, long summonedUntilMs) {
        Long notBefore = retryNotBefore.get(profileId);
        long untilMs = notBefore == null ? summonedUntilMs : Math.max(summonedUntilMs, notBefore);
        Long previous = untilById.put(profileId, untilMs);
        if (previous != null) {
            byExpiry.remove(new Entry(previous, profileId));
        }
        byExpiry.add(new Entry(untilMs, profileId));
    }

    private void drop(UUID profileId) {
        retryNotBefore.remove(profileId);
        Long previous = untilById.remove(profileId);
        if (previous != null) {
            byExpiry.remove(new Entry(previous, profileId));
        }
    }
}
