package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Minimal world-thread snapshot needed by population observers. */
record CompanionPopulationEntityObservation(@Nonnull UUID npcUuid,
                                            @Nullable UUID ownerUuid,
                                            @Nonnull String worldName,
                                            int chunkX,
                                            int chunkZ) {
    @Nullable
    static CompanionPopulationEntityObservation fromStore(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
            @Nonnull ComponentType<EntityStore, UUIDComponent> uuidType,
            @Nonnull ComponentType<EntityStore, TransformComponent> transformType,
            @Nullable TameworkOwnerComponent ownerOverride
    ) {
        UUIDComponent identity = store.getComponent(ref, uuidType);
        TransformComponent transform = store.getComponent(ref, transformType);
        TameworkOwnerComponent owner = ownerOverride != null
                ? ownerOverride
                : store.getComponent(ref, ownerType);
        return fromComponents(identity, owner, transform, store);
    }

    @Nullable
    static CompanionPopulationEntityObservation fromComponents(
            @Nullable UUIDComponent identity,
            @Nullable TameworkOwnerComponent owner,
            @Nullable TransformComponent transform,
            @Nonnull Store<EntityStore> store
    ) {
        World world = store.getExternalData() == null ? null : store.getExternalData().getWorld();
        if (identity == null || identity.getUuid() == null || transform == null
                || world == null || world.getName() == null || world.getName().isBlank()) {
            return null;
        }
        Vector3d position = transform.getPosition();
        return new CompanionPopulationEntityObservation(
                identity.getUuid(),
                owner == null ? null : owner.getOwnerId(),
                world.getName().trim(),
                ChunkUtil.chunkCoordinate((int) Math.floor(position.x)),
                ChunkUtil.chunkCoordinate((int) Math.floor(position.z))
        );
    }
}
