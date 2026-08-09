package com.alechilles.alecstamework.items.scarecrow;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.spawning.assets.spawnsuppression.SpawnSuppression;
import com.hypixel.hytale.server.spawning.suppression.component.SpawnSuppressionComponent;
import java.util.Objects;
import javax.annotation.Nonnull;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3i;

/** Maintains the invisible native spawn suppressor paired with each real scarecrow block. */
public final class ScarecrowSuppressorService {
    private static final double POSITION_EPSILON_SQUARED = 0.0001;

    private final ComponentType<EntityStore, TransformComponent> transformType;
    private final ComponentType<EntityStore, SpawnSuppressionComponent> suppressionType;
    private final ComponentType<EntityStore, UUIDComponent> uuidType;
    private final Query<EntityStore> suppressorQuery;

    ScarecrowSuppressorService() {
        this(
                TransformComponent.getComponentType(),
                SpawnSuppressionComponent.getComponentType(),
                UUIDComponent.getComponentType()
        );
    }

    ScarecrowSuppressorService(
            @Nonnull ComponentType<EntityStore, TransformComponent> transformType,
            @Nonnull ComponentType<EntityStore, SpawnSuppressionComponent> suppressionType,
            @Nonnull ComponentType<EntityStore, UUIDComponent> uuidType
    ) {
        this.transformType = Objects.requireNonNull(transformType, "transformType");
        this.suppressionType = Objects.requireNonNull(suppressionType, "suppressionType");
        this.uuidType = Objects.requireNonNull(uuidType, "uuidType");
        this.suppressorQuery = Query.and(transformType, suppressionType);
    }

    /** Creates a suppressor after native block placement has completed successfully. */
    public void reconcilePlaced(@Nonnull World world, @Nonnull Vector3i blockPosition) {
        BlockType blockType = blockTypeAt(world, blockPosition);
        if (blockType == null
                || !ScarecrowIds.ITEM_ID.equals(blockType.getId())
                || SpawnSuppression.getAssetMap().getAsset(ScarecrowIds.SUPPRESSION_ID) == null) {
            return;
        }
        Store<EntityStore> store = entityStore(world);
        if (store != null) {
            ensureAt(store, suppressorPosition(blockPosition));
        }
    }

    /** Removes a suppressor after native block breaking has completed successfully. */
    public void reconcileBroken(@Nonnull World world, @Nonnull Vector3i blockPosition) {
        BlockType blockType = blockTypeAt(world, blockPosition);
        if (blockType == null || ScarecrowIds.ITEM_ID.equals(blockType.getId())) {
            return;
        }
        Store<EntityStore> store = entityStore(world);
        if (store != null) {
            removeAt(store, suppressorPosition(blockPosition));
        }
    }

    void ensureAt(@Nonnull Store<EntityStore> store, @Nonnull Vector3dc position) {
        if (hasAt(store, position)) {
            return;
        }
        Holder<EntityStore> holder = store.getRegistry().newHolder();
        holder.addComponent(transformType, new TransformComponent(new Vector3d(position), Rotation3f.IDENTITY));
        holder.addComponent(suppressionType, new SpawnSuppressionComponent(ScarecrowIds.SUPPRESSION_ID));
        holder.addComponent(uuidType, UUIDComponent.randomUUID());
        store.addEntity(holder, AddReason.SPAWN);
    }

    void removeAt(@Nonnull Store<EntityStore> store, @Nonnull Vector3dc position) {
        store.forEachChunk(
                suppressorQuery,
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
                    for (int index = 0; index < chunk.size(); index++) {
                        TransformComponent transform = chunk.getComponent(index, transformType);
                        SpawnSuppressionComponent suppression = chunk.getComponent(index, suppressionType);
                        if (matches(transform, suppression, position)) {
                            Ref<EntityStore> reference = chunk.getReferenceTo(index);
                            commandBuffer.removeEntity(reference, RemoveReason.REMOVE);
                        }
                    }
                }
        );
    }

    private boolean hasAt(@Nonnull Store<EntityStore> store, @Nonnull Vector3dc position) {
        boolean[] found = {false};
        store.forEachChunk(
                suppressorQuery,
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> ignored) -> {
                    for (int index = 0; index < chunk.size() && !found[0]; index++) {
                        found[0] = matches(
                                chunk.getComponent(index, transformType),
                                chunk.getComponent(index, suppressionType),
                                position
                        );
                    }
                }
        );
        return found[0];
    }

    private static boolean matches(
            TransformComponent transform,
            SpawnSuppressionComponent suppression,
            Vector3dc position
    ) {
        return transform != null
                && suppression != null
                && ScarecrowIds.SUPPRESSION_ID.equals(suppression.getSpawnSuppression())
                && transform.getPosition().distanceSquared(position) < POSITION_EPSILON_SQUARED;
    }

    private static Vector3d suppressorPosition(Vector3i blockPosition) {
        return new Vector3d(blockPosition.x + 0.5, blockPosition.y + 0.5, blockPosition.z + 0.5);
    }

    private static BlockType blockTypeAt(World world, Vector3i blockPosition) {
        WorldChunk chunk = world.getChunkIfInMemory(
                ChunkUtil.indexChunkFromBlock(blockPosition.x, blockPosition.z)
        );
        return chunk != null
                ? chunk.getBlockType(blockPosition.x, blockPosition.y, blockPosition.z)
                : null;
    }

    private static Store<EntityStore> entityStore(World world) {
        return world.getEntityStore() != null ? world.getEntityStore().getStore() : null;
    }
}
