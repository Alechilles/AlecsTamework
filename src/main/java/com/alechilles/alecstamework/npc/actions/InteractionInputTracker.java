package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks recent player interaction inputs against NPC targets.
 */
public final class InteractionInputTracker {
    private static final long MAX_AGE_MS = 500L;

    private final ConcurrentHashMap<Ref<EntityStore>, ConcurrentHashMap<UUID, InteractionInput>> inputs =
            new ConcurrentHashMap<>();

    /**
     * Records a player interaction input against an NPC target.
     */
    public void recordInteraction(Ref<EntityStore> targetRef, Player player) {
        if (targetRef == null || player == null) {
            return;
        }
        UUID playerId = player.getUuid();
        if (playerId == null) {
            return;
        }
        inputs.computeIfAbsent(targetRef, key -> new ConcurrentHashMap<>())
                .put(playerId, new InteractionInput(System.currentTimeMillis()));
    }

    /**
     * Consumes a recent interaction input, returning true when one exists.
     */
    public boolean consumeInteraction(Ref<EntityStore> targetRef, UUID playerId) {
        if (targetRef == null || playerId == null) {
            return false;
        }
        ConcurrentHashMap<UUID, InteractionInput> byPlayer = inputs.get(targetRef);
        if (byPlayer == null) {
            return false;
        }
        InteractionInput input = byPlayer.remove(playerId);
        if (input == null) {
            return false;
        }
        if (System.currentTimeMillis() - input.timestampMs > MAX_AGE_MS) {
            return false;
        }
        if (byPlayer.isEmpty()) {
            inputs.remove(targetRef, byPlayer);
        }
        return true;
    }

    private static final class InteractionInput {
        private final long timestampMs;
        private InteractionInput(long timestampMs) {
            this.timestampMs = timestampMs;
        }
    }
}
