package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkHookComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Dispatches the shared NPC hook payload used by commands and bonded-panel actions. */
final class CommandNpcHookDispatchService {
    boolean dispatch(@Nonnull String hookId,
                     @Nonnull Player player,
                     @Nullable String itemId,
                     @Nonnull Ref<EntityStore> npcRef,
                     @Nonnull Store<EntityStore> store,
                     @Nullable Vector3d targetPosition) {
        if (npcRef == null || !npcRef.isValid() || store == null
                || npcRef.getStore() != store) {
            return false;
        }
        TameworkHookComponent component = createComponent(hookId, player, itemId,
                System.currentTimeMillis(), targetPosition);
        if (component == null || TameworkHookComponent.getComponentType() == null) {
            return false;
        }
        store.putComponent(npcRef, TameworkHookComponent.getComponentType(),
                component);
        return true;
    }

    @Nullable
    static TameworkHookComponent createComponent(@Nullable String hookId,
                                                  @Nullable Player player,
                                                  @Nullable String itemId,
                                                  long timestampMs,
                                                  @Nullable Vector3d targetPosition) {
        if (hookId == null || hookId.isBlank() || player == null) {
            return null;
        }
        UUID playerId = player.getUuid();
        String playerName = player.getPlayerRef() != null
                ? player.getPlayerRef().getUsername() : null;
        return new TameworkHookComponent(hookId, playerId, playerName, itemId,
                timestampMs, true, targetPosition);
    }
}
