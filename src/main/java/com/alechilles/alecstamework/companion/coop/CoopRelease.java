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

/**
 * Releases one coop resident (spec 8.9), at the morning roam start or when the block breaks.
 *
 * <ul>
 *   <li><b>Companion:</b> {@code restore(COOP_RELEASE)} at the entry's generation, which commits
 *   LIVE and spawns the body; the slot is cleared only after RESTORED. A lost clear is harmless:
 *   the entry is then stale by generation. A record with no owner (an unowned resident imported
 *   from 3.x or 4.x, or one taken in from an unowned capture item) is released unowned instead
 *   ({@link #request}): the body comes out untracked and the record becomes a tombstone, so the
 *   evening intake takes the animal back as an inline unowned resident.</li>
 *   <li><b>Unowned resident:</b> it has no record, so its inline entity is spawned first and the
 *   port clears the slot in the same world task once the body is in the store; a failed spawn
 *   keeps the entry. After a spawn this also queues {@link Port#clearSlot} (a no-op by then) and
 *   holds the slot in flight until it ran: a break release queued before it then still sees the
 *   resident in flight and does not spawn it a second time.</li>
 * </ul>
 *
 * <p>One release per slot runs at a time, until its slot clear has run, so the next sweep cannot
 * spawn an unowned resident twice. A failed release waits {@link #RETRY_DELAY_MS} before the
 * morning sweep tries it again, so one stuck resident neither spams nor blocks the others; the
 * mark expires when it is next checked.
 */
public final class CoopRelease {
    static final long RETRY_DELAY_MS = 30_000L;

    /** A coop block. */
    public record At(@Nonnull String world, int x, int y, int z) {
        public At {
            Objects.requireNonNull(world, "world");
        }
    }

    /**
     * How a release ended. {@code cause} is empty when released, otherwise the restore result's
     * name, {@code SPAWN_FAILED}, {@code BUSY} (a release of this slot is running) or {@code ERROR}.
     */
    public record Outcome(boolean released, @Nonnull String cause) {
        static final Outcome RELEASED = new Outcome(true, "");
    }

    /** The world side of a release. */
    public interface Port {
        @Nonnull
        CompletableFuture<RestoreFlow.Result> restore(@Nonnull RestoreFlow.Request request);

        /**
         * Spawns the unowned resident in {@code entry} and, in the same world task once the body is
         * in the store, removes the entry from the coop. Completes false (never exceptionally)
         * when no body was added.
         */
        @Nonnull
        CompletableFuture<Boolean> spawnUnowned(@Nonnull At at, @Nonnull TameworkCoopSlotsComponent.Slot entry,
                                                @Nonnull RestoreFlow.Destination destination);

        /**
         * Queues the removal of a released resident's {@code entry} from the coop on its world
         * thread when the slot still holds it; a broken or unloaded block is left alone. Completes
         * once that task ran (or could not run).
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
        String key = key(at, entry.slot());
        Long failed = failedAt.get(key);
        if (failed != null) {
            if (clock.getAsLong() - failed < RETRY_DELAY_MS) {
                return false;
            }
            failedAt.remove(key, failed);
        }
        return resident(at, entry);
    }

    /**
     * Starts the release of a resident checked with {@link #resident}. Completes (never
     * exceptionally) once the resident is out and its slot clear has run, or with the failure.
     */
    @Nonnull
    public CompletableFuture<Outcome> release(@Nonnull At at, @Nonnull TameworkCoopSlotsComponent.Slot entry,
                                              @Nonnull RestoreFlow.Destination destination) {
        String key = key(at, entry.slot());
        if (!inFlight.add(key)) {
            return CompletableFuture.completedFuture(new Outcome(false, "BUSY"));
        }
        CompletableFuture<Outcome> out;
        try {
            out = entry.unownedEntity() != null
                    ? port.spawnUnowned(at, entry, destination)
                    .thenCompose(spawned -> spawned
                            ? port.clearSlot(at, entry).exceptionally(failure -> null)
                            .thenApply(ignored -> Outcome.RELEASED)
                            : CompletableFuture.completedFuture(new Outcome(false, "SPAWN_FAILED")))
                    : port.restore(request(entry.profileId(), entry.generation(), record(entry), destination))
                    .thenCompose(result -> result == RestoreFlow.Result.RESTORED
                            ? port.clearSlot(at, entry).exceptionally(failure -> null)
                            .thenApply(ignored -> Outcome.RELEASED)
                            : CompletableFuture.completedFuture(new Outcome(false, result.name())));
        } catch (RuntimeException failure) {
            out = CompletableFuture.failedFuture(failure);
        }
        CompletableFuture<Outcome> done = new CompletableFuture<>();
        out.whenComplete((outcome, failure) -> {
            Outcome ended = failure != null || outcome == null ? new Outcome(false, "ERROR") : outcome;
            if (ended.released()) {
                failedAt.remove(key);
            } else {
                failedAt.put(key, clock.getAsLong());
            }
            inFlight.remove(key);
            done.complete(ended);
        });
        return done;
    }

    /**
     * The COOP_RELEASE of a resident's record at {@code generation}. A record with no owner is
     * released unowned, as an unowned capture item is: a LIVE record needs an owner to be of use
     * to anyone, and a body built from the role is refused without one.
     */
    @Nonnull
    static RestoreFlow.Request request(@Nonnull UUID profileId, long generation, @Nullable CompanionRecord record,
                                       @Nonnull RestoreFlow.Destination destination) {
        RestoreFlow.Request request = RestoreFlow.Request.of(profileId, RestoreRules.Reason.COOP_RELEASE, destination)
                .withGeneration(generation);
        return record != null && record.ownerUuid() == null
                ? request.withOwner(new RestoreFlow.Owner(null, null)) : request;
    }

    /** One slot of one coop, for per-slot bookkeeping. */
    @Nonnull
    static String key(@Nonnull At at, int slot) {
        return at.world() + '|' + at.x() + '|' + at.y() + '|' + at.z() + '|' + slot;
    }
}
