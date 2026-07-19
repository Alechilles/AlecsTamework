package com.alechilles.alecstamework.vfx.projectile;

import com.alechilles.alecstamework.items.CaptureChannelAnchorResolver;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Retargets harmless visual projectile entities to their destination anchor every world tick. */
public final class HomingVisualProjectileSystem extends TickingSystem<EntityStore> {
    private final ComponentType<EntityStore, HomingVisualProjectileComponent> projectileType;
    private final ComponentType<EntityStore, TransformComponent> transformType;
    private final Query<EntityStore> query;

    public HomingVisualProjectileSystem(
            @Nonnull ComponentType<EntityStore, HomingVisualProjectileComponent> projectileType,
            @Nonnull ComponentType<EntityStore, TransformComponent> transformType) {
        this.projectileType = projectileType;
        this.transformType = transformType;
        this.query = Query.and(projectileType, transformType);
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        World world = store.getExternalData() == null ? null : store.getExternalData().getWorld();
        if (world == null) {
            return;
        }
        store.forEachChunk(query, (ArchetypeChunk<EntityStore> chunk,
                                   CommandBuffer<EntityStore> commandBuffer) -> {
            for (int index = 0; index < chunk.size(); index++) {
                Ref<EntityStore> projectileRef = chunk.getReferenceTo(index);
                HomingVisualProjectileComponent projectile = chunk.getComponent(index, projectileType);
                TransformComponent transform = chunk.getComponent(index, transformType);
                if (projectileRef == null || !projectileRef.isValid()
                        || projectile == null || transform == null
                        || shouldRemove(projectile, world)) {
                    remove(projectileRef, commandBuffer);
                    continue;
                }

                Ref<EntityStore> destinationRef = resolveDestination(projectile, world);
                Vector3d destination = destinationRef == null
                        ? null
                        : CaptureChannelAnchorResolver.resolve(
                                destinationRef,
                                projectile.getDestinationAnchor(),
                                store
                        );
                HomingVisualProjectileMotion.Step step = HomingVisualProjectileMotion.step(
                        transform.getPosition(),
                        destination,
                        projectile.getLastDirection(),
                        projectile.getSpeed(),
                        projectile.getTurnRateDegreesPerSecond(),
                        projectile.getArrivalRadius(),
                        dt
                );
                double remaining = projectile.getRemainingLifetimeSeconds() - Math.max(0.0D, dt);
                if (!step.valid() || step.arrived() || !Double.isFinite(remaining) || remaining <= 0.0D) {
                    remove(projectileRef, commandBuffer);
                    continue;
                }

                projectile.setRemainingLifetimeSeconds(remaining);
                projectile.setLastDirection(step.direction());
                commandBuffer.putComponent(projectileRef, projectileType, projectile);
                commandBuffer.putComponent(
                        projectileRef,
                        transformType,
                        new TransformComponent(step.position(), transform.getRotation())
                );
            }
        });
    }

    private static boolean shouldRemove(@Nonnull HomingVisualProjectileComponent projectile,
                                        @Nonnull World world) {
        if (projectile.getDestinationUuid().isBlank()
                || projectile.getRemainingLifetimeSeconds() <= 0.0D
                || !world.getName().equals(projectile.getWorldName())) {
            return true;
        }
        return projectile.isSessionBound()
                && !HomingVisualProjectileSessionRegistry.isActive(
                        projectile.getWorldName(),
                        projectile.getOwnerUuid(),
                        projectile.getSessionGeneration()
                );
    }

    @Nullable
    private static Ref<EntityStore> resolveDestination(@Nonnull HomingVisualProjectileComponent projectile,
                                                       @Nonnull World world) {
        try {
            Ref<EntityStore> ref = world.getEntityRef(UUID.fromString(projectile.getDestinationUuid()));
            return ref != null && ref.isValid() ? ref : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void remove(@Nullable Ref<EntityStore> ref,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (ref != null && ref.isValid()) {
            commandBuffer.removeEntity(ref, RemoveReason.REMOVE);
        }
    }
}
