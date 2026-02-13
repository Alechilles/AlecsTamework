package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the most recent player interaction input to disambiguate Use/Primary/Secondary.
 */
public final class InteractionInputTracker {
    private static final long INPUT_TTL_MS = 750L;
    private static final ConcurrentHashMap<UUID, InputRecord> LAST_INPUT = new ConcurrentHashMap<>();

    private InteractionInputTracker() {
    }

    public static void record(Player player, Entity target, InteractionType type) {
        if (player == null || type == null) {
            return;
        }
        UUID playerId = player.getUuid();
        UUID targetId = target != null ? target.getUuid() : null;
        if (playerId == null) {
            return;
        }
        LAST_INPUT.put(playerId, new InputRecord(targetId, type, System.currentTimeMillis()));
    }

    public static InteractionType getLastInteractionType(Player player, UUID targetId) {
        if (player == null || targetId == null) {
            return null;
        }
        UUID playerId = player.getUuid();
        if (playerId == null) {
            return null;
        }
        InputRecord record = LAST_INPUT.get(playerId);
        if (record == null) {
            return null;
        }
        long age = System.currentTimeMillis() - record.timestampMs;
        if (age > INPUT_TTL_MS) {
            return null;
        }
        if (record.targetId != null && !targetId.equals(record.targetId)) {
            return null;
        }
        return record.type;
    }

    private static final class InputRecord {
        private final UUID targetId;
        private final InteractionType type;
        private final long timestampMs;

        private InputRecord(UUID targetId, InteractionType type, long timestampMs) {
            this.targetId = targetId;
            this.type = type;
            this.timestampMs = timestampMs;
        }
    }
}
