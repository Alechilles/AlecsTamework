package com.alechilles.alecstamework.npc.turning;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Runtime-only (no codec, never saved) turn tracking for an NPC whose model has turn-in-place
 * animation sets. Holds the body yaw seen last tick and the turn clip this system is playing.
 * Used only by the owning world thread.
 */
public final class TurnInPlaceState implements Component<EntityStore> {
    @Nullable
    private static ComponentType<EntityStore, TurnInPlaceState> type;

    float lastYaw = Float.NaN;
    /** -1 turning left, 1 turning right, 0 not playing a turn clip. */
    int direction;
    /** Seconds the current turn clip has played. */
    float playedSeconds;
    /** Seconds the body has turned slower than the stop rate while a clip plays. */
    float slowSeconds;

    public static void register(@Nonnull Tamework plugin) {
        type = plugin.getEntityStoreRegistry().registerComponent(TurnInPlaceState.class, TurnInPlaceState::new);
    }

    @Nullable
    public static ComponentType<EntityStore, TurnInPlaceState> getComponentType() {
        return type;
    }

    @Override
    public TurnInPlaceState clone() {
        TurnInPlaceState copy = new TurnInPlaceState();
        copy.lastYaw = lastYaw;
        copy.direction = direction;
        copy.playedSeconds = playedSeconds;
        copy.slowSeconds = slowSeconds;
        return copy;
    }
}
