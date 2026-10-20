package com.alechilles.alecstamework.companion.item;

import com.alechilles.alecstamework.companion.admission.CompanionAdmission;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Thread-safe cached admission decisions for slot filters (spec 8.14), keyed by player and
 * profile family (see {@link #family}), and the per-player, per-profile throttles of what a
 * refusal triggers. Filters run under a container lock and may be off the world thread, so they
 * only read this cache; a miss is evaluated later on the world thread.
 *
 * <p>Owner: the plugin, one instance for all worlds. A decision is dropped after {@link #TTL_MS},
 * when the player's own records change ({@link #onRecordChanged}), on config or settings reload
 * ({@link #clear}) and when the player leaves a world ({@link #forgetPlayer}, which also drops
 * the player's throttles).</p>
 */
public final class AdmissionCache {
    public static final long TTL_MS = 30_000L;
    public static final long NOTICE_EVERY_MS = 10_000L;
    /** Inventory resyncs after a refusal; ground pickup retries 4 times a second. */
    public static final long RESYNC_EVERY_MS = 1_000L;
    /** A queued follow-up that has not finished by then is assumed lost and may be queued again. */
    public static final long FOLLOW_UP_RETRY_MS = 5_000L;

    public enum Cached { ALLOW, DENY, MISS }

    private record Decision(boolean allowed, long atMs) {
    }

    private record Key(@Nonnull UUID player, @Nonnull UUID profileId) {
    }

    private final LongSupplier clock;
    private final Map<UUID, Map<String, Decision>> decisions = new ConcurrentHashMap<>();
    private final Map<Key, Long> notices = new ConcurrentHashMap<>();
    private final Map<Key, Long> resyncs = new ConcurrentHashMap<>();
    private final Map<Key, Long> followUps = new ConcurrentHashMap<>();

    public AdmissionCache(@Nonnull LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The cache key for the family a record counts in: its role, and the world it counts in,
     * since per-world limits can admit the same role in one world and not another.
     */
    @Nonnull
    public static String family(@Nonnull CompanionRecord record) {
        return record.roleId() + '@' + CompanionAdmission.scopeWorld(record);
    }

    @Nonnull
    public Cached get(@Nonnull UUID player, @Nonnull String family) {
        Map<String, Decision> byFamily = decisions.get(player);
        Decision decision = byFamily == null ? null : byFamily.get(family);
        if (decision == null || clock.getAsLong() - decision.atMs() >= TTL_MS) {
            return Cached.MISS;
        }
        return decision.allowed() ? Cached.ALLOW : Cached.DENY;
    }

    public void put(@Nonnull UUID player, @Nonnull String family, boolean allowed) {
        decisions.computeIfAbsent(player, id -> new ConcurrentHashMap<>())
                .put(family, new Decision(allowed, clock.getAsLong()));
    }

    /** Drops the player's decisions and throttles; for a player leaving a world. */
    public void forgetPlayer(@Nonnull UUID player) {
        decisions.remove(player);
        notices.keySet().removeIf(key -> key.player().equals(player));
        resyncs.keySet().removeIf(key -> key.player().equals(player));
        followUps.keySet().removeIf(key -> key.player().equals(player));
    }

    /** Drops every decision (config or settings reload); throttles are kept. */
    public void clear() {
        decisions.clear();
    }

    /** True at most once per player and profile every {@link #NOTICE_EVERY_MS}. */
    public boolean noticeDue(@Nonnull UUID player, @Nonnull UUID profileId) {
        return due(notices, new Key(player, profileId), NOTICE_EVERY_MS);
    }

    /** True at most once per player and profile every {@link #RESYNC_EVERY_MS}. */
    public boolean resyncDue(@Nonnull UUID player, @Nonnull UUID profileId) {
        return due(resyncs, new Key(player, profileId), RESYNC_EVERY_MS);
    }

    /**
     * Claims the one queued follow-up for this player and profile. False while another is queued,
     * unless that one was claimed {@link #FOLLOW_UP_RETRY_MS} ago. Release it with
     * {@link #followUpDone}.
     */
    public boolean followUpDue(@Nonnull UUID player, @Nonnull UUID profileId) {
        return due(followUps, new Key(player, profileId), FOLLOW_UP_RETRY_MS);
    }

    public void followUpDone(@Nonnull UUID player, @Nonnull UUID profileId) {
        followUps.remove(new Key(player, profileId));
    }

    /**
     * Index change listener: drops the decisions of the old and new owner when a change can move
     * their counts (a new record, or a change of owner, location kind, role or counting world).
     * It runs under the index lock, so it only removes map entries.
     */
    public void onRecordChanged(@Nullable CompanionRecord before, @Nonnull CompanionRecord after) {
        if (before != null && Objects.equals(before.ownerUuid(), after.ownerUuid())
                && before.location().kind() == after.location().kind()
                && before.roleId().equals(after.roleId())
                && CompanionAdmission.scopeWorld(before).equals(CompanionAdmission.scopeWorld(after))) {
            return;
        }
        if (before != null && before.ownerUuid() != null) {
            decisions.remove(before.ownerUuid());
        }
        if (after.ownerUuid() != null) {
            decisions.remove(after.ownerUuid());
        }
    }

    private boolean due(Map<Key, Long> stamps, Key key, long intervalMs) {
        long now = clock.getAsLong();
        boolean[] due = {false};
        stamps.compute(key, (k, last) -> {
            if (last != null && now - last < intervalMs) {
                return last;
            }
            due[0] = true;
            return now;
        });
        return due[0];
    }
}
