package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.config.assets.TwCompanionSummonSettings;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Roster summon and store on the companion index (spec 8.4), shared by the command panel, the
 * timed-summon expiry and the owner logout and death auto-store. The role's summon settings give
 * the timer and the re-summon cooldown: a timed summon gets {@code summonedUntilMs = now +
 * duration}; every store of a roster companion writes {@code summonCooldownUntilMs = now +
 * cooldown}. Only timed summons auto-store when their owner logs out or dies.
 *
 * <p>Bonded companions keep their own timers (plan 6 R18). This class never summons one, and
 * every store of one goes to the bonded store path given at construction, which stores it as
 * {@code STORED(BONDED)} with its roster's cooldown. Unlike roster summons, every active bonded
 * companion is stored when its owner logs out, leaves for another server or changes world.
 * Without a bonded store path bonded companions are left alone.
 *
 * <p>Every method returns at once and may be called from any thread; the flows hop to the body's
 * world themselves. Reads only the lock-free index and config.
 */
public final class RosterSummons {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * A role's summon settings. {@code durationMs} 0 means an untimed summon; {@code cooldownMs} 0
     * means none. {@code autoStoreOnLogout} applies only to timed summons.
     */
    public record Policy(long durationMs, long cooldownMs, boolean autoStoreOnLogout) {
        public static final Policy UNTIMED = new Policy(0L, 0L, false);

        /** The role's effective summon settings; untimed with no cooldown when timed summoning is off. */
        @Nonnull
        public static Policy forRole(@Nullable String roleId) {
            TwCompanionSummonSettings summon = TwCompanionConfig.resolveEffectiveForRole(roleId).getSummon();
            return summon.isEnabled()
                    ? new Policy(summon.getActiveDurationMs(), summon.getResummonCooldownMs(),
                            summon.isAutoStoreOnOwnerLogout())
                    : UNTIMED;
        }
    }

    /** Stores one LIVE companion; {@link StoreFlow#store} in production. */
    public interface Store {
        @Nonnull
        CompletableFuture<StoreFlow.Result> store(@Nonnull UUID profileId, @Nonnull StoredReason reason,
                                                  long cooldownUntilMs);
    }

    private final Function<UUID, CompanionRecord> record;
    private final Function<UUID, List<CompanionRecord>> owned;
    private final Function<RestoreFlow.Request, CompletableFuture<RestoreFlow.Outcome>> restore;
    private final Store store;
    private final Function<UUID, CompletableFuture<Void>> flush;
    private final Function<String, Policy> policies;
    private final LongSupplier clock;
    @Nullable private final Function<CompanionRecord, CompletableFuture<StoreFlow.Result>> bondedStore;

    /** Roster summons with no bonded store path: bonded companions are left alone. */
    public RosterSummons(@Nonnull Function<UUID, CompanionRecord> record,
                         @Nonnull Function<UUID, List<CompanionRecord>> owned,
                         @Nonnull Function<RestoreFlow.Request, CompletableFuture<RestoreFlow.Outcome>> restore,
                         @Nonnull Store store, @Nonnull Function<UUID, CompletableFuture<Void>> flush,
                         @Nonnull Function<String, Policy> policies, @Nonnull LongSupplier clock) {
        this(record, owned, restore, store, flush, policies, clock, null);
    }

    /**
     * @param record   a profile's current record, or null
     * @param owned    every non-released record of an owner
     * @param restore  {@link RestoreFlow#restoreOutcome(RestoreFlow.Request)}
     * @param flush    writes an owner's file to disk; {@code CompanionWriter::flushNow} in production
     * @param policies the summon policy of a role id; {@link Policy#forRole} in production
     * @param clock    wall clock
     * @param bondedStore stores an active bonded companion with its roster's cooldown
     *                    ({@code IndexBondedCompanionApi::storeActive}); null leaves bonded
     *                    companions alone
     */
    public RosterSummons(@Nonnull Function<UUID, CompanionRecord> record,
                         @Nonnull Function<UUID, List<CompanionRecord>> owned,
                         @Nonnull Function<RestoreFlow.Request, CompletableFuture<RestoreFlow.Outcome>> restore,
                         @Nonnull Store store, @Nonnull Function<UUID, CompletableFuture<Void>> flush,
                         @Nonnull Function<String, Policy> policies, @Nonnull LongSupplier clock,
                         @Nullable Function<CompanionRecord, CompletableFuture<StoreFlow.Result>> bondedStore) {
        this.record = Objects.requireNonNull(record, "record");
        this.owned = Objects.requireNonNull(owned, "owned");
        this.restore = Objects.requireNonNull(restore, "restore");
        this.store = Objects.requireNonNull(store, "store");
        this.flush = Objects.requireNonNull(flush, "flush");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.bondedStore = bondedStore;
    }

    /**
     * Summons a stored roster companion to {@code destination}, timed when its role has a summon
     * duration. COOLDOWN, OWNED_LIMIT, DEPLOYED_LIMIT, GROUP_LIMIT, PROVIDER_DENIED and PROVIDER_UNAVAILABLE come
     * back from the restore as they are, with the message key of a population refusal (a cap, an
     * admission provider's denial or a domain limit). A bonded companion is NOT_ALLOWED: the
     * bonded API summons it with its roster's timers. The summon is bound to the record
     * generation the caller checked: a record changed since then ends STALE.
     * {@code expectedGeneration} -1 accepts any.
     */
    @Nonnull
    public CompletableFuture<RestoreFlow.Outcome> summonOutcome(@Nonnull UUID profileId, long expectedGeneration,
                                                                @Nonnull RestoreFlow.Destination destination) {
        CompanionRecord current = record.apply(profileId);
        if (current == null) {
            return CompletableFuture.completedFuture(new RestoreFlow.Outcome(RestoreFlow.Result.NOT_FOUND, null));
        }
        if (current.bonded()) {
            return CompletableFuture.completedFuture(new RestoreFlow.Outcome(RestoreFlow.Result.NOT_ALLOWED, null));
        }
        long durationMs = policy(current).durationMs();
        RestoreFlow.Request request = RestoreFlow.Request.of(profileId, RestoreRules.Reason.SUMMON, destination)
                .withGeneration(expectedGeneration);
        if (durationMs > 0L) {
            request = request.withSummonedUntil(saturatedAdd(clock.getAsLong(), durationMs));
        }
        return restore.apply(request);
    }

    /** Stores a summoned roster companion (the panel's dismiss), TIMED when it has a timer, else ROSTER. */
    @Nonnull
    public CompletableFuture<StoreFlow.Result> store(@Nonnull UUID profileId) {
        CompanionRecord current = record.apply(profileId);
        if (current == null) {
            return CompletableFuture.completedFuture(StoreFlow.Result.NOT_FOUND);
        }
        if (current.bonded()) {
            return storeBonded(current);
        }
        return storeWithCooldown(current, current.summonedUntilMs() != 0L ? StoredReason.TIMED : StoredReason.ROSTER);
    }

    /**
     * The expiry scheduler's store action. Re-checks the record, since a re-summon may have moved
     * the timer after the scheduler returned the id: NOT_FOUND without a record, NOT_LIVE when it
     * has nothing due to store now (the index listener tracks its new timer).
     */
    @Nonnull
    public CompletableFuture<StoreFlow.Result> storeExpired(@Nonnull UUID profileId) {
        CompanionRecord current = record.apply(profileId);
        if (current == null) {
            return CompletableFuture.completedFuture(StoreFlow.Result.NOT_FOUND);
        }
        if (!timedLive(current) || current.summonedUntilMs() > clock.getAsLong()) {
            return CompletableFuture.completedFuture(StoreFlow.Result.NOT_LIVE);
        }
        return report(current, "expired",
                current.bonded() ? storeBonded(current) : storeWithCooldown(current, StoredReason.TIMED));
    }

    /**
     * Stores every LIVE timed summon of {@code owner}. On logout a role whose settings turn
     * auto-store off keeps its summon; on death every timed summon is stored. A bonded companion
     * is stored on logout whether it is timed or not, and stays out on its owner's death.
     * Returns how many stores were started.
     */
    public int storeTimedSummons(@Nonnull UUID owner, boolean logout) {
        int started = 0;
        for (CompanionRecord current : owned.apply(owner)) {
            if (current.bonded()) {
                if (logout && bondedStore != null && current.location().kind() == LocationKind.LIVE) {
                    report(current, "owner-logout", storeBonded(current));
                    started++;
                }
                continue;
            }
            if (!timedLive(current) || logout && !policy(current).autoStoreOnLogout()) {
                continue;
            }
            report(current, logout ? "owner-logout" : "owner-death", storeWithCooldown(current, StoredReason.TIMED));
            started++;
        }
        return started;
    }

    /**
     * Stores every active bonded companion of {@code owner} that is not in {@code world}, the
     * world its owner just entered (plan 6 R18). Returns how many stores were started.
     */
    public int storeBondedOutside(@Nonnull UUID owner, @Nonnull String world) {
        if (bondedStore == null) {
            return 0;
        }
        int started = 0;
        for (CompanionRecord current : owned.apply(owner)) {
            if (current.bonded() && current.location().kind() == LocationKind.LIVE
                    && !world.equals(current.location().world())) {
                report(current, "owner-world-change", storeBonded(current));
                started++;
            }
        }
        return started;
    }

    /**
     * Readies {@code owner} to leave this server: stores every summoned roster companion (LIVE,
     * with a roster id), timed or not, and every active bonded companion through the bonded store
     * path, then flushes the owner's file. Each store keeps
     * the re-summon cooldown, so hopping servers does not reset timers. Companions without a roster
     * stay in the world (spec 13.4). Never blocks: a store that fails is logged and does not stop
     * the others or the flush. The future fails only when the flush itself fails.
     */
    @Nonnull
    public CompletableFuture<Void> prepareTransfer(@Nonnull UUID owner) {
        List<CompletableFuture<Void>> stores = new ArrayList<>();
        for (CompanionRecord current : owned.apply(owner)) {
            if (current.location().kind() != LocationKind.LIVE || current.rosterId() == null
                    || current.bonded() && bondedStore == null) {
                continue;
            }
            StoredReason reason = current.summonedUntilMs() != 0L ? StoredReason.TIMED : StoredReason.ROSTER;
            stores.add(report(current, "transfer",
                    current.bonded() ? storeBonded(current) : storeWithCooldown(current, reason))
                    .handle((outcome, error) -> null));
        }
        return CompletableFuture.allOf(stores.toArray(new CompletableFuture<?>[0])).thenCompose(ignored -> {
            try {
                return flush.apply(owner);
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        });
    }

    private CompletableFuture<StoreFlow.Result> storeWithCooldown(CompanionRecord current, StoredReason reason) {
        long cooldownMs = policy(current).cooldownMs();
        long cooldownUntilMs = cooldownMs > 0L ? saturatedAdd(clock.getAsLong(), cooldownMs) : 0L;
        try {
            return store.store(current.profileId(), reason, cooldownUntilMs);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /** NOT_LIVE, changing nothing, when there is no bonded store path. */
    private CompletableFuture<StoreFlow.Result> storeBonded(CompanionRecord current) {
        if (bondedStore == null) {
            return CompletableFuture.completedFuture(StoreFlow.Result.NOT_LIVE);
        }
        try {
            return bondedStore.apply(current);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /** A failed config lookup falls back to an untimed summon with no cooldown. */
    private Policy policy(CompanionRecord current) {
        try {
            Policy policy = policies.apply(current.roleId());
            return policy == null ? Policy.UNTIMED : policy;
        } catch (RuntimeException | LinkageError failure) {
            return Policy.UNTIMED;
        }
    }

    private static boolean timedLive(CompanionRecord current) {
        return current.location().kind() == LocationKind.LIVE && current.summonedUntilMs() != 0L;
    }

    /**
     * Auto-store has no player to answer, so a failure is logged. A timed record keeps its timer, so
     * the expiry scheduler stores it when the timer runs out and retries a failed expiry store.
     */
    private static CompletableFuture<StoreFlow.Result> report(CompanionRecord current, String cause,
                                                              CompletableFuture<StoreFlow.Result> result) {
        return result.whenComplete((outcome, error) -> {
            if (error != null) {
                LOGGER.at(Level.WARNING).withCause(error).log("Summoned companion %s was not stored (%s)",
                        current.profileId(), cause);
            } else if (outcome == StoreFlow.Result.NO_SNAPSHOT || outcome == StoreFlow.Result.COMMIT_FAILED) {
                LOGGER.at(Level.WARNING).log("Summoned companion %s was not stored (%s): %s",
                        current.profileId(), cause, outcome);
            }
        });
    }

    /** {@code durationMs} is positive; a sum past the end of time stays at the end. */
    private static long saturatedAdd(long nowMs, long durationMs) {
        return nowMs > Long.MAX_VALUE - durationMs ? Long.MAX_VALUE : nowMs + durationMs;
    }
}
