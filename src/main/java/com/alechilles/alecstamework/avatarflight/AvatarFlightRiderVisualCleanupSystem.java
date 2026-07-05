package com.alechilles.alecstamework.avatarflight;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Removes stale avatar-flight rider visual markers and fake rider entities.
 */
public final class AvatarFlightRiderVisualCleanupSystem extends EntityTickingSystem<EntityStore> {
    private final ComponentType<EntityStore, AvatarFlightRiderVisualComponent> visualType;
    private final ComponentType<EntityStore, AvatarFlightComponent> flightType;
    private final Query<EntityStore> query;

    public AvatarFlightRiderVisualCleanupSystem(
            @Nonnull ComponentType<EntityStore, AvatarFlightRiderVisualComponent> visualType,
            @Nonnull ComponentType<EntityStore, AvatarFlightComponent> flightType) {
        this.visualType = visualType;
        this.flightType = flightType;
        this.query = Query.and(visualType);
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        AvatarFlightRiderVisualComponent visual = archetypeChunk.getComponent(index, visualType);
        if (ref == null || visual == null) {
            return;
        }
        if (visual.isRiderEntity()) {
            cleanupRiderMarker(ref, visual, commandBuffer);
        } else {
            cleanupOwnerMarker(ref, visual, commandBuffer);
        }
    }

    private void cleanupOwnerMarker(@Nonnull Ref<EntityStore> ownerRef,
                                    @Nonnull AvatarFlightRiderVisualComponent visual,
                                    @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Ref<EntityStore> riderRef = AvatarFlightRiderVisualService.resolveRiderRef(commandBuffer.getStore(), visual);
        AvatarFlightComponent flight = commandBuffer.getComponent(ownerRef, flightType);
        if (flight != null && riderRef != null && riderRef.isValid()) {
            return;
        }
        if (riderRef != null) {
            commandBuffer.tryRemoveEntity(riderRef, RemoveReason.REMOVE);
        }
        commandBuffer.tryRemoveComponent(ownerRef, visualType);
    }

    private void cleanupRiderMarker(@Nonnull Ref<EntityStore> riderRef,
                                    @Nonnull AvatarFlightRiderVisualComponent visual,
                                    @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Ref<EntityStore> ownerRef = resolveOwnerRef(commandBuffer.getStore(), visual);
        if (ownerRef != null && ownerRef.isValid()
                && commandBuffer.getComponent(ownerRef, visualType) != null) {
            return;
        }
        commandBuffer.tryRemoveEntity(riderRef, RemoveReason.REMOVE);
    }

    @Nullable
    private static Ref<EntityStore> resolveOwnerRef(@Nonnull Store<EntityStore> store,
                                                    @Nonnull AvatarFlightRiderVisualComponent visual) {
        if (visual.getOwnerUuid().isBlank()) {
            return null;
        }
        try {
            return store.getExternalData().getWorld().getEntityRef(UUID.fromString(visual.getOwnerUuid()));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }
}
