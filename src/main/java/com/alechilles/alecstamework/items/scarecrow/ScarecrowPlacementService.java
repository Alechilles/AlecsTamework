package com.alechilles.alecstamework.items.scarecrow;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.modules.collision.WorldUtil;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entity.component.PropComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.Interactions;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.spawning.assets.spawnsuppression.SpawnSuppression;
import com.hypixel.hytale.server.spawning.suppression.component.SpawnSuppressionComponent;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Validates scarecrow placement and assembles the complete native suppressor entity. */
public final class ScarecrowPlacementService {
    private static final double SURFACE_OFFSET = 1.01;
    private static final float BLOCK_ENTITY_SCALE = 2.0f;

    private ScarecrowPlacementService() {
    }

    /** Prepares a holder only after the item, suppression asset, surface, and placement cell are valid. */
    @Nonnull
    public static Preparation prepare(
            @Nullable World world,
            int blockX,
            int blockY,
            int blockZ,
            @Nonnull Vector3d actorPosition
    ) {
        Objects.requireNonNull(actorPosition, "actorPosition");
        if (!hasValidAssets()) {
            return new Preparation(Status.INVALID_ASSET, null);
        }
        WorldChunk chunk = world != null
                ? world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(blockX, blockZ))
                : null;
        if (chunk == null) {
            return new Preparation(Status.UNAVAILABLE, null);
        }
        if (!isSolid(chunk, blockX, blockY, blockZ)) {
            return new Preparation(Status.INVALID_SURFACE, null);
        }
        if (!isOpen(chunk, blockX, blockY + 1, blockZ)) {
            return new Preparation(Status.OCCUPIED, null);
        }
        Placement placement = plan(blockX, blockY, blockZ, actorPosition);
        if (hasScarecrowAt(world, placement.position())) {
            return new Preparation(Status.OCCUPIED, null);
        }
        return new Preparation(Status.SUCCESS, createHolder(placement));
    }

    @Nonnull
    static Placement plan(int blockX, int blockY, int blockZ, @Nonnull Vector3d actorPosition) {
        Vector3d position = new Vector3d(blockX + 0.5, blockY + SURFACE_OFFSET, blockZ + 0.5);
        Vector3d relative = new Vector3d(actorPosition).sub(position);
        relative.y = 0.0;
        Rotation3f rotation = relative.lengthSquared() > 0.000001
                ? Rotation3f.lookAt(relative)
                : new Rotation3f();
        return new Placement(position, rotation);
    }

    @Nonnull
    static EntityComponents buildComponents(@Nonnull Placement placement) {
        Objects.requireNonNull(placement, "placement");
        Interactions interactions = new Interactions(
                Map.of(InteractionType.Use, ScarecrowIds.COLLECT_ROOT_INTERACTION_ID)
        );
        interactions.setInteractionHint(ScarecrowIds.REMOVE_INTERACTION_HINT);
        return new EntityComponents(
                new BlockEntity(ScarecrowIds.ITEM_ID),
                new TransformComponent(placement.position(), placement.rotation()),
                new EntityScaleComponent(BLOCK_ENTITY_SCALE),
                PropComponent.get(),
                interactions,
                new SpawnSuppressionComponent(ScarecrowIds.SUPPRESSION_ID),
                UUIDComponent.randomUUID()
        );
    }

    @Nonnull
    private static Holder<EntityStore> createHolder(@Nonnull Placement placement) {
        EntityComponents components = buildComponents(placement);
        Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
        holder.addComponent(BlockEntity.getComponentType(), components.blockEntity());
        holder.addComponent(TransformComponent.getComponentType(), components.transform());
        holder.addComponent(EntityScaleComponent.getComponentType(), components.scale());
        holder.addComponent(PropComponent.getComponentType(), components.prop());
        holder.addComponent(Interactions.getComponentType(), components.interactions());
        holder.addComponent(SpawnSuppressionComponent.getComponentType(), components.suppression());
        holder.addComponent(UUIDComponent.getComponentType(), components.uuid());
        return holder;
    }

    private static boolean hasValidAssets() {
        Item item = Item.getAssetMap().getAsset(ScarecrowIds.ITEM_ID);
        if (item == null || !item.hasBlockType() || item.getBlockId() == null) {
            return false;
        }
        BlockType blockType = BlockType.getAssetMap().getAsset(item.getBlockId());
        return blockType != null
                && blockType != BlockType.UNKNOWN
                && SpawnSuppression.getAssetMap().getAsset(ScarecrowIds.SUPPRESSION_ID) != null;
    }

    private static boolean isOpen(WorldChunk chunk, int blockX, int blockY, int blockZ) {
        return chunk.getBlock(blockX, blockY, blockZ) == 0
                && chunk.getFluidId(blockX, blockY, blockZ) == 0;
    }

    private static boolean isSolid(WorldChunk chunk, int blockX, int blockY, int blockZ) {
        int blockId = chunk.getBlock(blockX, blockY, blockZ);
        if (blockId == 0) {
            return false;
        }
        BlockType blockType = BlockType.getAssetMap().getAsset(blockId);
        return blockType != null
                && blockType != BlockType.UNKNOWN
                && WorldUtil.isSolidOnlyBlock(blockType, chunk.getFluidId(blockX, blockY, blockZ));
    }

    private static boolean hasScarecrowAt(@Nonnull World world, @Nonnull Vector3d position) {
        if (world.getEntityStore() == null) {
            return false;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (store == null) {
            return false;
        }
        boolean[] found = {false};
        store.forEachChunk(
                Query.and(TransformComponent.getComponentType(), SpawnSuppressionComponent.getComponentType()),
                (ArchetypeChunk<EntityStore> archetypeChunk, CommandBuffer<EntityStore> ignored) -> {
                    for (int index = 0; index < archetypeChunk.size() && !found[0]; index++) {
                        SpawnSuppressionComponent suppression = archetypeChunk.getComponent(
                                index,
                                SpawnSuppressionComponent.getComponentType()
                        );
                        if (suppression == null
                                || !ScarecrowIds.SUPPRESSION_ID.equals(suppression.getSpawnSuppression())) {
                            continue;
                        }
                        TransformComponent transform = archetypeChunk.getComponent(
                                index,
                                TransformComponent.getComponentType()
                        );
                        found[0] = transform != null && transform.getPosition().distanceSquared(position) < 0.01;
                    }
                }
        );
        return found[0];
    }

    /** Placement transform for a centered scarecrow facing its placer. */
    record Placement(Vector3d position, Rotation3f rotation) {
    }

    /** Component bundle applied to the entity holder before it enters the world. */
    record EntityComponents(
            BlockEntity blockEntity,
            TransformComponent transform,
            EntityScaleComponent scale,
            PropComponent prop,
            Interactions interactions,
            SpawnSuppressionComponent suppression,
            UUIDComponent uuid
    ) {
    }

    /** Result of validating and preparing one placement. */
    public record Preparation(@Nonnull Status status, @Nullable Holder<EntityStore> holder) {
        public boolean succeeded() {
            return status == Status.SUCCESS && holder != null;
        }
    }

    /** Player-relevant placement outcomes. */
    public enum Status {
        SUCCESS,
        INVALID_ASSET,
        INVALID_SURFACE,
        OCCUPIED,
        UNAVAILABLE
    }
}
