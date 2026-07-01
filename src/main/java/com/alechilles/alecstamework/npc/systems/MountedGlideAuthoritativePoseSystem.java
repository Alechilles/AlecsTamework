package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.system.TransformSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.systems.RoleSystems;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Records the final server-authoritative glide NPC pose after NPC movement has advanced it.
 */
public final class MountedGlideAuthoritativePoseSystem extends EntityTickingSystem<EntityStore> {
    private final ComponentType<EntityStore, TameworkMountedGlideComponent> mountComponentType;
    private final ComponentType<EntityStore, TransformComponent> transformComponentType;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, RoleSystems.PostBehaviourSupportTickSystem.class),
            new SystemDependency<>(Order.BEFORE, TransformSystems.EntityTrackerUpdate.class)
    );

    public MountedGlideAuthoritativePoseSystem(
            @Nonnull ComponentType<EntityStore, TameworkMountedGlideComponent> mountComponentType,
            @Nonnull ComponentType<EntityStore, TransformComponent> transformComponentType) {
        this.mountComponentType = mountComponentType;
        this.transformComponentType = transformComponentType;
        this.query = Query.and(mountComponentType, transformComponentType);
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        TameworkMountedGlideComponent mount = archetypeChunk.getComponent(index, mountComponentType);
        if (mount == null || mount.getRiderUuid().isBlank()) {
            return;
        }
        Ref<EntityStore> mountRef = archetypeChunk.getReferenceTo(index);
        TransformComponent transform = commandBuffer.getComponent(mountRef, transformComponentType);
        if (transform == null || transform.getPosition() == null || transform.getRotation() == null) {
            return;
        }
        mount.captureAuthoritativePose(
                transform.getPosition().x,
                transform.getPosition().y,
                transform.getPosition().z,
                transform.getRotation().yaw(),
                transform.getRotation().pitch(),
                transform.getRotation().roll()
        );
        commandBuffer.putComponent(mountRef, mountComponentType, mount);
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }
}
