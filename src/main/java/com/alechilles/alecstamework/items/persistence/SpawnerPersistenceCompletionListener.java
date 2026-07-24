package com.alechilles.alecstamework.items.persistence;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/** Receives terminal spawner persistence feedback on the current actor world thread. */
@FunctionalInterface
public interface SpawnerPersistenceCompletionListener {
    void complete(
            @Nonnull SpawnerPersistenceAuthorResult result,
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> actorRef,
            @Nonnull Player player
    );
}
