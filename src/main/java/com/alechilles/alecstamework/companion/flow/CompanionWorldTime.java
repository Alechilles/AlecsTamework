package com.alechilles.alecstamework.companion.flow;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.time.Instant;
import javax.annotation.Nonnull;

/** Reads a world's game time for snapshots. Call on the store's world thread. */
public final class CompanionWorldTime {
    private CompanionWorldTime() {
    }

    /**
     * The world's game time in epoch milliseconds (it can be negative), or {@code 0} when the
     * world has no time resource.
     */
    public static long gameTimeMs(@Nonnull Store<EntityStore> store) {
        WorldTimeResource time = store.getResource(WorldTimeResource.getResourceType());
        Instant gameTime = time == null ? null : time.getGameTime();
        return gameTime == null ? 0L : gameTime.toEpochMilli();
    }
}
