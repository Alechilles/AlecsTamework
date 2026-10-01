package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.companion.flow.RestoreRules;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;

/**
 * Releases one coop resident (spec 8.9), at the morning roam start or when the block breaks.
 *
 * <ul>
 *   <li><b>Companion:</b> {@code restore(COOP_RELEASE)} at the entry's generation, which commits
 *   LIVE and spawns the body; the slot is cleared only after RESTORED. A lost clear is harmless:
 *   the entry is then stale by generation.</li>
 *   <li><b>Unowned resident:</b> it has no record, so its inline entity is spawned first and the
 *   slot is cleared only after a successful spawn; a failed spawn keeps the entry.</li>
 * </ul>
 *
 * <p>One release per slot runs at a time, until its slot clear has run, so the next sweep cannot
 * spawn an unowned resident twice. A failed release waits {@link #RETRY_DELAY_MS} before the
 * morning sweep tries it again, so one stuck resident neither spams nor blocks the others.
 */
public final class CoopRelease {
    static final long RETRY_DELAY_MS = 30_000L;

    /** A coop block. */
    public record At(@Nonnull String world, int x, int y, int z) {
        public At {
            Objects.requireNonNull(world, "world");
        }
    }

    /** The world side of a release. */
    public interface Port {
        @Nonnull
        CompletableFuture<RestoreFlow.Result> restore(@Nonnull RestoreFlow.Request request);

        /** Spawns an unowned resident; completes false (never exceptionally) when no body was added. */
        @Nonnull
        CompletableFuture<Boolean> spawnUnowned(@Nonnull BsonDocument entity, @Nonnull RestoreFlow.Destination destination);

        /**
         * Removes {@code entry} from the coop on its world thread when the slot still holds it; a
         * broken or unloaded block is left alone. Completes once that ran (or could not run).
         */
        @Nonnull
        CompletableFuture<Void> clearSlot(@Nonnull At at, @Nonnull TameworkCoopSlotsComponent.Slot entry);
    }

    private final Function<UUID, CompanionRecord> records;
    private final Port port;
    private final LongSupplier clock;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> failedAt = new ConcurrentHashMap<>();

    public CoopRelease(@Nonnull Function<UUID, CompanionRecord> records, @Nonnull Port port,
                       @Nonnull LongSupplier clock) {
        this.records = Objects.requireNonNull(records, "records");
        this.port = Objects.requireNonNull(port, "port");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The entry's record, or null for an unowned resident or a missing record. */
    @Nullable
    public CompanionRecord record(@Nonnull TameworkCoopSlotsComponent.Slot entry) {
        return entry.profileId() == null ? null : records.apply(entry.profileId());
    }

    /** A current resident (not a stale entry) with no release running. */
    public boolean resident(@Nonnull At at, @Nonnull TameworkCoopSlotsComponent.Slot entry) {
        return !inFlight.contains(key(at, entry.slot()))
                && CoopSlots.occupied(entry, record(entry), at.world(), at.x(), at.y(), at.z());
    }

    /** Like {@link #resident}, also skipping a resident whose last release failed recently. */
    public boolean releasableNow(@Nonnull At at, @Nonnull TameworkCoopSlotsComponent.Slot entry) {
        Long failed = failedAt.get(key(at, entry.slot()));
        return (failed == null || clock.getAsLong() - failed >= RETRY_DELAY_MS) && resident(at, entry);
    }

    /**
     * Starts the release of a resident checked with {@link #resident}. Completes true once the
     * resident is out (never exceptionally); its slot clear has then run.
     */
    @Nonnull
    public CompletableFuture<Boolean> release(@Nonnull At at, @Nonnull TameworkCoopSlotsComponent.Slot entry,
                                              @Nonnull RestoreFlow.Destination destination) {
        String key = key(at, entry.slot());
        if (!inFlight.add(key)) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> out;
        try {
            out = entry.unownedEntity() != null
                    ? port.spawnUnowned(entry.unownedEntity(), destination)
                    : port.restore(RestoreFlow.Request.of(entry.profileId(), RestoreRules.Reason.COOP_RELEASE,
                            destination).withGeneration(entry.generation()))
                    .thenApply(result -> result == RestoreFlow.Result.RESTORED);
        } catch (RuntimeException failure) {
            out = CompletableFuture.failedFuture(failure);
        }
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        out.exceptionally(failure -> false).thenCompose(released -> released
                        ? port.clearSlot(at, entry).exceptionally(failure -> null).thenApply(ignored -> true)
                        : CompletableFuture.completedFuture(false))
                .whenComplete((released, failure) -> {
                    boolean ok = failure == null && Boolean.TRUE.equals(released);
                    if (ok) {
                        failedAt.remove(key);
                    } else {
                        failedAt.put(key, clock.getAsLong());
                    }
                    inFlight.remove(key);
                    done.complete(ok);
                });
        return done;
    }

    /** Drops failure marks older than the retry delay. Called once per sweep. */
    public void pruneFailures() {
        long now = clock.getAsLong();
        failedAt.values().removeIf(failed -> now - failed >= RETRY_DELAY_MS);
    }

    private static String key(At at, int slot) {
        return at.world() + '|' + at.x() + '|' + at.y() + '|' + at.z() + '|' + slot;
    }
}
