package com.alechilles.alecstamework.items;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;

/**
 * The capture failure cooldown ({@code FailureCooldownMs}) per player and capture item config.
 * Held in memory only: a restart clears it. Expired entries are dropped when read or when a new
 * failure is recorded, so the map holds at most one entry per player and config still cooling down.
 */
final class SpawnerCaptureFailureCooldowns implements SpawnerCaptureRollService.CooldownGate {
    private record Key(UUID player, String itemConfigId) {
    }

    private final Map<Key, Long> untilMs = new ConcurrentHashMap<>();

    @Override
    public boolean active(UUID actorUuid, String itemConfigId, UUID currentAttemptId, long nowMs) {
        if (actorUuid == null || itemConfigId == null) {
            return false;
        }
        Key key = new Key(actorUuid, itemConfigId);
        Long until = untilMs.get(key);
        if (until == null) {
            return false;
        }
        if (nowMs < until) {
            return true;
        }
        untilMs.remove(key, until);
        return false;
    }

    /** Starts a cooldown that ends at {@code untilMs} (wall clock). */
    void record(@Nonnull UUID player, @Nonnull String itemConfigId, long untilMs, long nowMs) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(itemConfigId, "itemConfigId");
        this.untilMs.values().removeIf(until -> until <= nowMs);
        if (untilMs > nowMs) {
            this.untilMs.merge(new Key(player, itemConfigId), untilMs, Math::max);
        }
    }
}
