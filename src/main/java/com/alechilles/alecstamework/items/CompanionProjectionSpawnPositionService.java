package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.math.TameworkRotationUtil;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Resolves visible, terrain-safe placement for API-created companion projections. */
final class CompanionProjectionSpawnPositionService {
    private static final double SAFE_DISTANCE = 5.0D;
    private final CommandCompanionPlacementService placement =
            new CommandCompanionPlacementService();

    @Nonnull
    Placement resolve(
            @Nonnull World world,
            @Nullable UUID actorUuid,
            @Nullable String roleId,
            int chunkX,
            int chunkZ,
            @Nonnull WorldChunk fallbackChunk) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        Ref<EntityStore> actorRef = actorUuid == null ? null : world.getEntityRef(actorUuid);
        Vector3d position = actorRef == null ? null : placement.computeSafeRecallPosition(
                actorRef, store, SAFE_DISTANCE, roleId, null);
        boolean actorRelative = position != null;
        if (position == null) {
            position = fallbackPosition(chunkX, chunkZ, fallbackChunk);
        }
        Rotation3f rotation = rotationTowardActor(actorRef, store, position);
        log(roleId, actorUuid, position, actorRelative);
        return new Placement(position, rotation, actorRelative);
    }

    @Nonnull
    static Vector3d fallbackPosition(int chunkX, int chunkZ, @Nonnull WorldChunk chunk) {
        int local = ChunkUtil.SIZE / 2;
        return new Vector3d(
                ChunkUtil.minBlock(chunkX) + local + 0.5D,
                chunk.getHeight(local, local) + 1.0D,
                ChunkUtil.minBlock(chunkZ) + local + 0.5D);
    }

    @Nonnull
    private static Rotation3f rotationTowardActor(
            @Nullable Ref<EntityStore> actorRef,
            Store<EntityStore> store,
            Vector3d position) {
        if (actorRef == null || !actorRef.isValid()) return new Rotation3f();
        TransformComponent transform = store.getComponent(
                actorRef, TransformComponent.getComponentType());
        if (transform == null || transform.getPosition() == null) return new Rotation3f();
        Vector3d towardActor = new Vector3d(transform.getPosition()).sub(position);
        towardActor.y = 0.0D;
        return towardActor.lengthSquared() > 0.0001D
                ? TameworkRotationUtil.lookAt(towardActor) : new Rotation3f();
    }

    private static void log(
            @Nullable String roleId,
            @Nullable UUID actorUuid,
            Vector3d position,
            boolean actorRelative) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null) return;
        plugin.getLogger().at(Level.INFO).log(String.format(
                Locale.US,
                "Companion projection placement role=%s actor=%s mode=%s position=(%.2f, %.2f, %.2f)",
                roleId, actorUuid, actorRelative ? "near-actor" : "chunk-fallback",
                position.x, position.y, position.z));
    }

    record Placement(@Nonnull Vector3d position,
                     @Nonnull Rotation3f rotation,
                     boolean actorRelative) {
    }
}
