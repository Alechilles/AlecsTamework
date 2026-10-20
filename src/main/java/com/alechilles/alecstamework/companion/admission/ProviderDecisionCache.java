package com.alechilles.alecstamework.companion.admission;

import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Cached admission provider decisions for the synchronous sites (plan 6 R11): tame pre-checks,
 * the tame re-check, holder transfer, the pickup filter and litters. Those sites run on a world
 * thread, some under the index lock, and cannot wait for a provider. {@link #decide} only reads
 * a map; on a miss it refuses with {@link #CHECKING_MESSAGE_KEY} and starts one evaluation, whose
 * provider call runs on the provider registry's own threads.
 *
 * <p>A decision is kept per owner and managed family (see {@link ProviderAdmission#familyKey})
 * for {@link #TTL_MS}; an unavailable answer only for {@link #UNAVAILABLE_TTL_MS}, so a timeout
 * or a saturated provider pool is asked again soon. The provider is always asked the same
 * question, "may this owner get a new companion of this family, as {@code after} describes it",
 * so one decision serves a tame, a transfer to the owner and a litter. An answer that depends on
 * the exact place or the previous owner is therefore reused for up to 30 s at these sites; the
 * asynchronous flows always ask about the exact change.</p>
 *
 * <p>Owner: the plugin, one instance. An owner's decisions are dropped when the owner's counts
 * change ({@link #onRecordChanged}) and when the owner disconnects ({@link #forgetOwner}); all
 * are dropped on a config reload ({@link #clear}), when a provider registers or unregisters, and
 * on shutdown ({@link #close}), after which nothing is cached or started.</p>
 */
public final class ProviderDecisionCache {
    public static final long TTL_MS = 30_000L;
    public static final long UNAVAILABLE_TTL_MS = 5_000L;
    /** A started evaluation with no answer after this long is assumed lost and may be started again. */
    static final long PENDING_RETRY_MS = 10_000L;
    /** Owners kept before expired entries are swept; owners that are never online can add entries. */
    private static final int SWEEP_ABOVE_OWNERS = 512;
    /** Message key of a refusal whose decision is still being fetched. */
    public static final String CHECKING_MESSAGE_KEY = "tamework.ui.population.checkingRequirements";

    private static final ProviderAdmission.Outcome CHECKING = new ProviderAdmission.Outcome(
            CompanionAdmission.Provided.none(), CompanionAdmission.Refusal.PROVIDER_UNAVAILABLE,
            CHECKING_MESSAGE_KEY, true);

    /** A started evaluation ({@code outcome} null) or its answer. Compared by identity. */
    private static final class Slot {
        @Nullable private final ProviderAdmission.Outcome outcome;
        private final long atMs;

        private Slot(@Nullable ProviderAdmission.Outcome outcome, long atMs) {
            this.outcome = outcome;
            this.atMs = atMs;
        }

        private boolean fresh(long now) {
            long life = outcome == null ? PENDING_RETRY_MS
                    : outcome.refusal() == CompanionAdmission.Refusal.PROVIDER_UNAVAILABLE ? UNAVAILABLE_TTL_MS
                    : TTL_MS;
            return now - atMs < life;
        }
    }

    private final ProviderAdmission providers;
    private final LongSupplier clock;
    private final Map<UUID, Map<String, Slot>> byOwner = new ConcurrentHashMap<>();
    private volatile Object registrations;
    private volatile boolean closed;

    public ProviderDecisionCache(@Nonnull ProviderAdmission providers, @Nonnull LongSupplier clock) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.registrations = providers.registrations();
    }

    /**
     * The provider's decision on the change from {@code before} (null for a new record) to
     * {@code after}, without waiting. Not asked when the change needs no admission or the role
     * is not managed. A cached allow carries the claims and domain limits for the check under
     * the index lock; a cached refusal carries its message key. Without a cached decision the
     * answer is a refusal with {@link #CHECKING_MESSAGE_KEY}, and one evaluation is started
     * unless one is already running for this owner and family. Safe under the index lock.
     */
    @Nonnull
    public ProviderAdmission.Outcome decide(@Nullable CompanionRecord before, @Nonnull CompanionRecord after) {
        UUID owner = after.ownerUuid();
        if (owner == null || !ProviderAdmission.needsAdmission(before, after)) {
            return ProviderAdmission.NOT_ASKED;
        }
        String family = providers.familyKey(after.roleId());
        if (family == null) {
            return ProviderAdmission.NOT_ASKED;
        }
        if (closed) {
            return CHECKING;
        }
        dropOnRegistrationChange();
        Map<String, Slot> slots = byOwner.computeIfAbsent(owner, id -> new ConcurrentHashMap<>());
        long now = clock.getAsLong();
        Slot slot = slots.get(family);
        if (slot == null || !slot.fresh(now)) {
            Slot pending = new Slot(null, now);
            if (slot == null ? slots.putIfAbsent(family, pending) == null : slots.replace(family, slot, pending)) {
                evaluate(slots, family, pending, after);
            }
            // The stage is already complete when no provider call was needed (for example no provider).
            slot = slots.get(family);
        }
        return slot == null || slot.outcome == null ? CHECKING : slot.outcome;
    }

    /**
     * Starts the decisions a player is likely to need soon: a new LIVE companion in
     * {@code world} for each managed family. They are asked one after the other, so a join does
     * not fill the provider's bounded queue.
     */
    public void warm(@Nonnull UUID owner, @Nonnull String world) {
        if (closed) {
            return;
        }
        List<String> roles = providers.familyRoles();
        warmNext(owner, world, roles, 0);
    }

    private void warmNext(UUID owner, String world, List<String> roles, int at) {
        for (int i = at; i < roles.size(); i++) {
            String family = providers.familyKey(roles.get(i));
            if (family == null || closed) {
                continue;
            }
            Map<String, Slot> slots = byOwner.computeIfAbsent(owner, id -> new ConcurrentHashMap<>());
            Slot pending = new Slot(null, clock.getAsLong());
            Slot known = slots.get(family);
            if (known != null && known.fresh(pending.atMs)
                    || !(known == null ? slots.putIfAbsent(family, pending) == null
                    : slots.replace(family, known, pending))) {
                continue;
            }
            int next = i + 1;
            evaluate(slots, family, pending, candidate(owner, roles.get(i), world))
                    .thenRun(() -> warmNext(owner, world, roles, next));
            return;
        }
    }

    /** A new LIVE companion of {@code roleId} for {@code owner} in {@code world}. */
    private static CompanionRecord candidate(UUID owner, String roleId, String world) {
        return CompanionRecord.builder(UUID.randomUUID(), roleId, CompanionLocation.live(world, 0, 0, 0))
                .ownerUuid(owner).homeWorld(world).build();
    }

    private CompletionStage<Void> evaluate(Map<String, Slot> slots, String family, Slot pending,
                                           CompanionRecord after) {
        return providers.evaluate(null, after).handle((outcome, error) -> {
            // The slot is replaced only while it is still this evaluation's: an invalidation in
            // between removed it, and its answer may be about counts that changed since.
            if (closed || error != null || outcome == null || !outcome.asked()) {
                slots.remove(family, pending);
            } else {
                slots.replace(family, pending, new Slot(outcome, clock.getAsLong()));
            }
            return null;
        });
    }

    /**
     * Index after-unlock listener: drops the decisions of an owner whose counts changed (a
     * record gained or lost that owner, or entered or left LIVE). One change keeps them: an
     * admission that stored exactly the claims of the owner's cached allow. That decision
     * produced the change, and the claims now on the record are counted by the check under the
     * index lock, so the rest of a litter, or the next tame, is not refused while the provider
     * is asked again.
     */
    public void onRecordChanged(@Nullable CompanionRecord before, @Nonnull CompanionRecord after) {
        UUID oldOwner = before == null || !before.countsAsOwned() ? null : before.ownerUuid();
        UUID newOwner = after.countsAsOwned() ? after.ownerUuid() : null;
        boolean wasLive = oldOwner != null && before.isDeployed();
        boolean isLive = newOwner != null && after.isDeployed();
        if (Objects.equals(oldOwner, newOwner) && wasLive == isLive) {
            return;
        }
        if (oldOwner != null && !oldOwner.equals(newOwner)) {
            byOwner.remove(oldOwner);
        }
        if (newOwner != null && !admittedByCachedAllow(before, after)) {
            byOwner.remove(newOwner);
        }
    }

    private boolean admittedByCachedAllow(@Nullable CompanionRecord before, CompanionRecord after) {
        if (!ProviderAdmission.needsAdmission(before, after)) {
            return false;
        }
        Map<String, Slot> slots = byOwner.get(after.ownerUuid());
        String family = slots == null ? null : providers.familyKey(after.roleId());
        Slot slot = family == null ? null : slots.get(family);
        return slot != null && slot.outcome != null && slot.outcome.admitted()
                && slot.outcome.provided().claims().equals(after.domainClaims());
    }

    /** Drops an owner's decisions; for a player who disconnected. */
    public void forgetOwner(@Nonnull UUID owner) {
        byOwner.remove(owner);
        if (byOwner.size() > SWEEP_ABOVE_OWNERS) {
            long now = clock.getAsLong();
            byOwner.values().forEach(slots -> slots.values().removeIf(slot -> !slot.fresh(now)));
            byOwner.values().removeIf(Map::isEmpty);
        }
    }

    /** Drops every decision; for a config reload. Running evaluations no longer store their answer. */
    public void clear() {
        byOwner.clear();
    }

    /** Shutdown: drops every decision; later calls cache nothing and start nothing. */
    public void close() {
        closed = true;
        byOwner.clear();
    }

    private void dropOnRegistrationChange() {
        Object now = providers.registrations();
        if (!now.equals(registrations)) {
            registrations = now;
            byOwner.clear();
        }
    }
}
