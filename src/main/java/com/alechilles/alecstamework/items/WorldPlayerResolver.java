package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves a player, reference, and current store together inside a world-thread continuation. */
final class WorldPlayerResolver {
    private WorldPlayerResolver() {
    }

    @Nullable
    static ResolvedPlayer resolve(@Nonnull World world, @Nonnull UUID playerUuid) {
        if (!world.isAlive() || world.getEntityStore() == null
                || Player.getComponentType() == null) {
            return null;
        }
        try {
            Store<EntityStore> store = world.getEntityStore().getStore();
            Ref<EntityStore> ref = world.getEntityRef(playerUuid);
            if (store == null || ref == null || !ref.isValid()) {
                return null;
            }
            Player player = store.getComponent(ref, Player.getComponentType());
            return player == null ? null : new ResolvedPlayer(player, ref, store, world);
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    /**
     * Re-resolves a possibly transfer-spanning player component through its stable player ref.
     * A custom page can outlive the source-world entity ref while the holder is moved to another
     * world, so menu actions must not trust the ref captured when the page was opened.
     */
    @Nullable
    static ResolvedPlayer resolveCurrent(@Nonnull Player capturedPlayer) {
        try {
            PlayerRef playerRef = capturedPlayer.getPlayerRef();
            Ref<EntityStore> ref = playerRef != null ? playerRef.getReference() : null;
            if (ref == null || !ref.isValid()) {
                return null;
            }
            Store<EntityStore> store = ref.getStore();
            World world = store != null && store.getExternalData() != null
                    ? store.getExternalData().getWorld() : null;
            if (store == null || world == null || !world.isAlive()) {
                return null;
            }
            Player livePlayer = store.getComponent(ref, Player.getComponentType());
            return livePlayer == null ? null : new ResolvedPlayer(livePlayer, ref, store, world);
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    record ResolvedPlayer(@Nonnull Player player,
                          @Nonnull Ref<EntityStore> ref,
                          @Nonnull Store<EntityStore> store,
                          @Nonnull World world) {
    }
}
