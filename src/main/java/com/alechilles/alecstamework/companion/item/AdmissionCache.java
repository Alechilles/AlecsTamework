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
 * profile family (see {@link #family}). Filters run under a container lock and may be off the
 * world thread, so they only read this cache; a miss is evaluated later on the world thread.
 *
 * <p>Owner: the plugin, one instance for all worlds. A decision is dropped after {@link #TTL_MS},
 * when the player's own records change ({@link #onRecordChanged}), on config reload
 * ({@link #clear}) and when the player leaves a world ({@link #forgetPlayer}).</p>
 */
public final class AdmissionCache {
    public static final long TTL_MS = 30_000L;
    public static final long NOTICE_EVERY_MS = 10_000L;

    public enum Cached { ALLOW, DENY, MISS }

    private record Decision(boolean allowed, long atMs) {
    }

    private record NoticeKey(@Nonnull UUID player, @Nonnull UUID profileId) {
    }

    private final LongSupplier clock;
    private final Map<UUID, Map<String, Decision>> decisions = new ConcurrentHashMap<>();
    private final Map<NoticeKey, Long> notices = new ConcurrentHashMap<>();

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

    public void invalidatePlayer(@Nonnull UUID player) {
        decisions.remove(player);
    }

    /** Drops the player's decisions and notice throttles; for a player leaving a world. */
    public void forgetPlayer(@Nonnull UUID player) {
        decisions.remove(player);
        notices.keySet().removeIf(key -> key.player().equals(player));
    }

    public void clear() {
        decisions.clear();
    }

    /** True at most once per player and profile every {@link #NOTICE_EVERY_MS}. */
    public boolean noticeDue(@Nonnull UUID player, @Nonnull UUID profileId) {
        long now = clock.getAsLong();
        boolean[] due = {false};
        notices.compute(new NoticeKey(player, profileId), (key, last) -> {
            if (last != null && now - last < NOTICE_EVERY_MS) {
                return last;
            }
            due[0] = true;
            return now;
        });
        return due[0];
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
}
