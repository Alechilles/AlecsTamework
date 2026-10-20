package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The cosmetic side of a bonded summon, driven by the record's summon timer
 * ({@code summonedUntilMs}): the family's {@code SummonAuraEffectId} when a companion comes into
 * the world, its {@code ExpiryWarningEffectId} {@link #WARNING_LEAD_MS} before the session ends,
 * the owner's "expires in" notifications at 60, 30, 10, 5, 4, 3, 2 and 1 seconds before it ends,
 * and fall protection for a player riding the companion when the session ends.
 *
 * <p>One pending timer per timed active bonded companion, not a scan: {@link #onChanged} (an
 * index listener that runs after the index lock is released) schedules the first warning when a
 * companion gets a timer and cancels the pending one when the companion stops being active or
 * its timer changes. Each warning arms the next one. {@link #rebuild} schedules the companions
 * already active at start. The warning task re-reads the record, so a timer left over from an
 * earlier session does nothing.</p>
 *
 * <p>Nothing here touches an entity: {@link Bodies} hops to the body's world thread. Effects are
 * best effort and never change a record. Owner: {@code Tamework} builds one with the bonded API
 * and closes it with the API; {@link #close} cancels every pending warning.</p>
 */
public final class BondedSummonEffects implements AutoCloseable {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    /** How long before the session ends the warning effect starts. */
    public static final long WARNING_LEAD_MS = 5_000L;
    /** Seconds before the session ends at which the owner is told, in the order they come due. */
    private static final int[] NOTICE_SECONDS = {60, 30, 10, 5, 4, 3, 2, 1};

    /** Plays effects on a companion's loaded body. Every method returns at once and may be called from any thread. */
    public interface Bodies {
        /**
         * Plays {@code effectId} on the companion (or on its owner while the owner wears the
         * companion's model). {@code keepUntilMs} is the wall-clock time the effect must last to
         * at least, or 0 for the effect's own duration. Does nothing without a loaded body.
         */
        void playEffect(@Nonnull CompanionRecord record, @Nonnull String effectId, long keepUntilMs);

        /**
         * Tells the owner that the companion expires in {@code warning.secondsRemaining()} seconds,
         * with the name the panel shows, in the owner's language. Does nothing when the owner is offline.
         */
        void notifyExpiry(@Nonnull CompanionRecord record,
                          @Nonnull BondedCompanionExpiryWarningSchedule.Warning warning);

        /** Protects a player riding the companion from the fall that follows its removal. */
        void armExpiryDismount(@Nonnull CompanionRecord record);
    }

    /** Runs a task once after a delay; the returned action cancels it. */
    @FunctionalInterface
    public interface Scheduler {
        @Nonnull
        Runnable schedule(@Nonnull Runnable task, long delayMs);
    }

    private record Pending(long untilMs, Runnable cancel) {
    }

    private final Function<UUID, CompanionRecord> records;
    private final BondedRecords.Families families;
    private final Bodies bodies;
    private final Scheduler scheduler;
    private final LongSupplier clock;
    private final Map<UUID, Pending> warnings = new HashMap<>();
    private boolean closed;

    /**
     * @param records   a profile's current record, or null; {@code CompanionIndex::get}
     * @param scheduler the companion module's timer thread, which the module stops at shutdown
     * @param clock     wall clock
     */
    public BondedSummonEffects(@Nonnull Function<UUID, CompanionRecord> records,
                               @Nonnull BondedRecords.Families families, @Nonnull Bodies bodies,
                               @Nonnull Scheduler scheduler, @Nonnull LongSupplier clock) {
        this.records = Objects.requireNonNull(records, "records");
        this.families = Objects.requireNonNull(families, "families");
        this.bodies = Objects.requireNonNull(bodies, "bodies");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Schedules the warning of every timed active bonded companion in the index. */
    public void rebuild(@Nonnull CompanionIndex index) {
        index.forEach(this::track);
    }

    /** Index listener, after the lock: keeps the pending warning in step with the record's timer. */
    public void onChanged(@Nullable CompanionRecord before, @Nonnull CompanionRecord after) {
        if (!after.bonded() && (before == null || !before.bonded())) {
            return;
        }
        // Listeners of two changes can arrive out of order, so the current record decides.
        CompanionRecord current = records.apply(after.profileId());
        track(current != null ? current : after);
    }

    /** A restore brought the companion into the world: plays the family's summon aura. */
    public void summoned(@Nonnull UUID profileId) {
        try {
            CompanionRecord record = records.apply(profileId);
            if (record == null || record.location().kind() != LocationKind.LIVE) {
                return;
            }
            BondedCompanionPolicy family = BondedRecords.policy(record, families);
            if (family != null && family.summonAuraEffectId() != null) {
                bodies.playEffect(record, family.summonAuraEffectId(), 0L);
            }
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.at(Level.WARNING).withCause(failure)
                    .log("Summon aura failed for bonded companion %s", profileId);
        }
    }

    /**
     * The summon timer of {@code profileId} ran out and its store is about to start. Call before
     * the store, so the rider is read while the body is still there.
     */
    public void expiring(@Nonnull UUID profileId) {
        try {
            CompanionRecord record = records.apply(profileId);
            if (record != null && record.bonded() && record.location().kind() == LocationKind.LIVE
                    && record.summonedUntilMs() != 0L && record.summonedUntilMs() <= clock.getAsLong()) {
                bodies.armExpiryDismount(record);
            }
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.at(Level.WARNING).withCause(failure)
                    .log("Expiry fall protection failed for bonded companion %s", profileId);
        }
    }

    /** Cancels every pending warning; later changes schedule nothing. */
    @Override
    public void close() {
        synchronized (warnings) {
            closed = true;
            warnings.values().forEach(pending -> pending.cancel().run());
            warnings.clear();
        }
    }

    private void track(CompanionRecord record) {
        long untilMs = record.bonded() && record.location().kind() == LocationKind.LIVE
                ? record.summonedUntilMs() : 0L;
        UUID profileId = record.profileId();
        synchronized (warnings) {
            Pending pending = warnings.get(profileId);
            if (pending != null && pending.untilMs() == untilMs) {
                return;
            }
            if (pending != null) {
                pending.cancel().run();
                warnings.remove(profileId);
            }
            long nowMs = clock.getAsLong();
            if (closed || untilMs == 0L || untilMs <= nowMs) {
                return;
            }
            if (untilMs - nowMs < WARNING_LEAD_MS) {
                // A session with less than the lead left (a restart close to its end) gets the effect at once.
                schedule(profileId, untilMs, 0, 0L);
            } else {
                armNext(profileId, untilMs, Integer.MAX_VALUE, nowMs);
            }
        }
    }

    /**
     * Under the lock: arms the first notice below {@code belowSeconds} that is still ahead. When
     * none is left the entry stays tracked until the record changes, so nothing is warned twice.
     */
    private void armNext(UUID profileId, long untilMs, int belowSeconds, long nowMs) {
        for (int seconds : NOTICE_SECONDS) {
            long dueMs = untilMs - seconds * 1_000L;
            if (seconds < belowSeconds && dueMs >= nowMs) {
                schedule(profileId, untilMs, seconds, dueMs - nowMs);
                return;
            }
        }
        warnings.put(profileId, new Pending(untilMs, () -> { }));
    }

    /** Under the lock. {@code seconds} is the notice to give, or 0 for the late effect alone. */
    private void schedule(UUID profileId, long untilMs, int seconds, long delayMs) {
        try {
            warnings.put(profileId,
                    new Pending(untilMs, scheduler.schedule(() -> warn(profileId, untilMs, seconds), delayMs)));
        } catch (RuntimeException stopped) {
            // The timer thread stops at shutdown; there is nothing left to warn.
        }
    }

    /** Timer thread: gives one warning and arms the next. */
    private void warn(UUID profileId, long untilMs, int seconds) {
        try {
            CompanionRecord record = records.apply(profileId);
            if (record == null || !record.bonded() || record.location().kind() != LocationKind.LIVE
                    || record.summonedUntilMs() != untilMs) {
                return;
            }
            if (seconds == 0 || seconds * 1_000L == WARNING_LEAD_MS) {
                BondedCompanionPolicy family = BondedRecords.policy(record, families);
                if (family != null && family.expiryWarningEffectId() != null) {
                    bodies.playEffect(record, family.expiryWarningEffectId(), untilMs);
                }
            }
            if (seconds != 0 && record.ownerUuid() != null) {
                BondedCompanionExpiryWarningSchedule.warning(untilMs, untilMs - seconds * 1_000L)
                        .ifPresent(warning -> bodies.notifyExpiry(record, warning));
            }
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.at(Level.WARNING).withCause(failure)
                    .log("Expiry warning failed for bonded companion %s", profileId);
        }
        synchronized (warnings) {
            Pending pending = warnings.get(profileId);
            // A store, a new summon or close() since this task started has already replaced the entry.
            if (!closed && pending != null && pending.untilMs() == untilMs) {
                armNext(profileId, untilMs, seconds == 0 ? Integer.MAX_VALUE : seconds, clock.getAsLong());
            }
        }
    }

}
