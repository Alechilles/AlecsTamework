package com.alechilles.alecstamework.damage;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/** Removes expiry-dismount fall protection once its player has safely landed. */
public final class ExpiryDismountLandingProtectionSystem
        extends EntityTickingSystem<EntityStore> {
    private final Query<EntityStore> query = Query.and(
            Player.getComponentType(),
            UUIDComponent.getComponentType(),
            MovementStatesComponent.getComponentType());

    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void tick(
            float dt,
            int index,
            @Nonnull ArchetypeChunk<EntityStore> chunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        UUIDComponent uuid = chunk.getComponent(
                index, UUIDComponent.getComponentType());
        MovementStatesComponent movement = chunk.getComponent(
                index, MovementStatesComponent.getComponentType());
        var states = movement == null ? null
                : movement.getMovementStates();
        if (uuid == null || uuid.getUuid() == null || states == null
                || !states.onGround) {
            return;
        }
        ExpiryDismountFallProtectionService.getInstance().clearWhenGrounded(
                uuid.getUuid(), System.currentTimeMillis());
    }
}
