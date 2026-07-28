package com.alechilles.alecstamework.persistence.authoring.runtime;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Ephemeral owner access resolved inside one world-thread continuation.
 *
 * <p>This value must never escape the continuation that created it.</p>
 */
record HytaleOwnerWorldAccess(
        @Nonnull UUID ownerUuid,
        @Nonnull String worldKey,
        @Nonnull World world,
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> actorRef,
        @Nonnull Player player
) {
    HytaleOwnerWorldAccess {
        if (ownerUuid == null || worldKey == null || worldKey.isBlank()
                || world == null || store == null || actorRef == null
                || player == null) {
            throw new IllegalArgumentException(
                    "Complete owner world access is required"
            );
        }
        worldKey = worldKey.trim();
    }
}
