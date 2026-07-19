package com.alechilles.alecstamework.vfx.projectile;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.NonSerialized;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.modules.entity.DespawnComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.time.TimeResource;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Builds lightweight replicated model entities that carry attached particles but no combat behavior. */
public final class HomingVisualProjectileSpawner {
    public static final int DEFAULT_WORLD_CAP = 256;

    private HomingVisualProjectileSpawner() {
    }

    @Nonnull
    public static SpawnResult spawn(@Nonnull Store<EntityStore> store,
                                    @Nonnull Vector3d origin,
                                    @Nonnull UUID destinationUuid,
                                    @Nonnull HomingVisualProjectileSpec spec,
                                    @Nullable UUID ownerUuid,
                                    @Nullable UUID sourceUuid,
                                    long sessionGeneration) {
        Holder<EntityStore> holder = createHolder(
                store.getExternalData() == null ? null : store.getExternalData().getWorld().getName(),
                store.getExternalData() == null ? -1 : store.getExternalData().takeNextNetworkId(),
                store.getResource(TimeResource.getResourceType()),
                origin,
                destinationUuid,
                spec,
                ownerUuid,
                sourceUuid,
                sessionGeneration
        );
        if (holder == null) {
            return failureFor(spec);
        }
        store.addEntity(holder, AddReason.SPAWN);
        return SpawnResult.SPAWNED;
    }

    @Nonnull
    public static SpawnResult spawn(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                                    @Nonnull Vector3d origin,
                                    @Nonnull UUID destinationUuid,
                                    @Nonnull HomingVisualProjectileSpec spec,
                                    @Nullable UUID ownerUuid,
                                    @Nullable UUID sourceUuid,
                                    long sessionGeneration) {
        Holder<EntityStore> holder = createHolder(
                commandBuffer.getExternalData() == null ? null : commandBuffer.getExternalData().getWorld().getName(),
                commandBuffer.getExternalData() == null ? -1 : commandBuffer.getExternalData().takeNextNetworkId(),
                commandBuffer.getResource(TimeResource.getResourceType()),
                origin,
                destinationUuid,
                spec,
                ownerUuid,
                sourceUuid,
                sessionGeneration
        );
        if (holder == null) {
            return failureFor(spec);
        }
        commandBuffer.addEntity(holder, AddReason.SPAWN);
        return SpawnResult.SPAWNED;
    }

    public static int countForSession(@Nonnull Store<EntityStore> store,
                                      @Nonnull UUID ownerUuid,
                                      long sessionGeneration) {
        ComponentType<EntityStore, HomingVisualProjectileComponent> type = componentType();
        if (type == null || sessionGeneration <= 0L) {
            return 0;
        }
        int[] count = {0};
        String owner = ownerUuid.toString();
        store.forEachChunk(type, (ArchetypeChunk<EntityStore> chunk,
                                  CommandBuffer<EntityStore> commandBuffer) -> {
            for (int index = 0; index < chunk.size(); index++) {
                HomingVisualProjectileComponent component = chunk.getComponent(index, type);
                if (component != null && component.getSessionGeneration() == sessionGeneration
                        && owner.equals(component.getOwnerUuid())) {
                    count[0]++;
                }
            }
        });
        return count[0];
    }

    public static int countInWorld(@Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, HomingVisualProjectileComponent> type = componentType();
        if (type == null) {
            return 0;
        }
        int[] count = {0};
        store.forEachChunk(type, (ArchetypeChunk<EntityStore> chunk,
                                  CommandBuffer<EntityStore> commandBuffer) -> count[0] += chunk.size());
        return count[0];
    }

    @Nullable
    private static Holder<EntityStore> createHolder(@Nullable String worldName,
                                                     int networkId,
                                                     @Nonnull TimeResource timeResource,
                                                     @Nonnull Vector3d origin,
                                                     @Nonnull UUID destinationUuid,
                                                     @Nonnull HomingVisualProjectileSpec spec,
                                                     @Nullable UUID ownerUuid,
                                                     @Nullable UUID sourceUuid,
                                                     long sessionGeneration) {
        ComponentType<EntityStore, HomingVisualProjectileComponent> type = componentType();
        if (type == null || networkId < 0 || worldName == null || worldName.isBlank()
                || !finite(origin) || !spec.isValid()) {
            return null;
        }
        ModelAsset modelAsset = ModelAsset.getAssetMap().getAsset(spec.modelId());
        if (modelAsset == null) {
            return null;
        }

        Model model = Model.createStaticScaledModel(modelAsset, 1.0F);
        Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
        holder.addComponent(NetworkId.getComponentType(), new NetworkId(networkId));
        holder.addComponent(EntityStore.REGISTRY.getNonSerializedComponentType(), NonSerialized.get());
        holder.addComponent(TransformComponent.getComponentType(),
                new TransformComponent(new Vector3d(origin), new Rotation3f()));
        holder.addComponent(ModelComponent.getComponentType(), new ModelComponent(model));
        holder.addComponent(
                DespawnComponent.getComponentType(),
                DespawnComponent.despawnInSeconds(timeResource, (float) spec.lifetimeSeconds())
        );
        holder.addComponent(type, new HomingVisualProjectileComponent(
                destinationUuid.toString(),
                spec,
                ownerUuid == null ? null : ownerUuid.toString(),
                sourceUuid == null ? null : sourceUuid.toString(),
                worldName,
                sessionGeneration
        ));
        holder.ensureComponent(EntityTrackerSystems.Visible.getComponentType());
        return holder;
    }

    @Nonnull
    private static SpawnResult failureFor(@Nonnull HomingVisualProjectileSpec spec) {
        return spec.isValid() && ModelAsset.getAssetMap().getAsset(spec.modelId()) == null
                ? SpawnResult.INVALID_MODEL
                : SpawnResult.INVALID_CONFIGURATION;
    }

    @Nullable
    private static ComponentType<EntityStore, HomingVisualProjectileComponent> componentType() {
        Tamework plugin = Tamework.getInstance();
        return plugin == null ? null : plugin.getHomingVisualProjectileComponentType();
    }

    private static boolean finite(Vector3d value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    public enum SpawnResult {
        SPAWNED,
        CAPPED,
        INVALID_MODEL,
        INVALID_CONFIGURATION
    }
}
