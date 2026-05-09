package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.alechilles.alecstamework.npc.components.TameworkRideRiderComponent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
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
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.systems.SteeringSystem;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Keeps a rider physically attached to the Tamework-controlled mount without using vanilla mount controllers.
 */
public final class MountedRideRiderFollowSystem extends EntityTickingSystem<EntityStore> {
    private final ComponentType<EntityStore, MountedComponent> mountedComponentType;
    private final ComponentType<EntityStore, TameworkRideRiderComponent> rideRiderComponentType;
    private final ComponentType<EntityStore, TameworkRideMountComponent> rideMountComponentType;
    private final ComponentType<EntityStore, TransformComponent> transformComponentType;
    private long lastDebugMs;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, SteeringSystem.class),
            new SystemDependency<>(Order.BEFORE, MountedRideCleanupSystem.class)
    );

    public MountedRideRiderFollowSystem(
            @Nonnull ComponentType<EntityStore, MountedComponent> mountedComponentType,
            @Nonnull ComponentType<EntityStore, TameworkRideRiderComponent> rideRiderComponentType,
            @Nonnull ComponentType<EntityStore, TameworkRideMountComponent> rideMountComponentType,
            @Nonnull ComponentType<EntityStore, TransformComponent> transformComponentType) {
        this.mountedComponentType = mountedComponentType;
        this.rideRiderComponentType = rideRiderComponentType;
        this.rideMountComponentType = rideMountComponentType;
        this.transformComponentType = transformComponentType;
        this.query = Query.and(rideRiderComponentType, transformComponentType, Player.getComponentType());
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Ref<EntityStore> riderRef = archetypeChunk.getReferenceTo(index);
        TameworkRideRiderComponent rider = archetypeChunk.getComponent(index, rideRiderComponentType);
        TransformComponent riderTransform = archetypeChunk.getComponent(index, transformComponentType);
        Player player = archetypeChunk.getComponent(index, Player.getComponentType());
        if (riderRef == null || rider == null || riderTransform == null || player == null) {
            return;
        }
        MountedComponent mounted = archetypeChunk.getComponent(index, mountedComponentType);
        Ref<EntityStore> mountRef = resolveMountRef(rider, mounted, store);
        if (mountRef == null || !mountRef.isValid()) {
            return;
        }
        TameworkRideMountComponent mount = store.getComponent(mountRef, rideMountComponentType);
        TransformComponent mountTransform = store.getComponent(mountRef, transformComponentType);
        if (mount == null || mountTransform == null) {
            return;
        }
        Vector3d mountPosition = mountTransform.getPosition();
        var mountRotation = mountTransform.getRotation();
        if (mountRotation != null) {
            mount.captureAuthoritativePose(
                    mountPosition.x,
                    mountPosition.y,
                    mountPosition.z,
                    mountRotation.getYaw(),
                    mountRotation.getPitch(),
                    mountRotation.getRoll()
            );
            commandBuffer.putComponent(mountRef, rideMountComponentType, mount);
        }
        double yaw = mountRotation == null ? 0.0 : mountRotation.getYaw();
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        double localX = mount.getAnchorX();
        double localZ = mount.getAnchorZ();
        double worldX = localX * cos - localZ * sin;
        double worldZ = localX * sin + localZ * cos;
        double riderX = mountPosition.x + worldX;
        double riderY = mountPosition.y + mount.getAnchorY();
        double riderZ = mountPosition.z + worldZ;
        if (!rider.isClientCameraApplied() && MountedRideClientAttachment.attach(store, riderRef, mountRef, mount)) {
            rider.setClientCameraApplied(true);
            commandBuffer.putComponent(riderRef, rideRiderComponentType, rider);
        }
        player.moveTo(riderRef, riderX, riderY, riderZ, commandBuffer);
        maybeLogDebug(riderTransform, mountTransform, mount);
    }

    @Nullable
    private Ref<EntityStore> resolveMountRef(@Nonnull TameworkRideRiderComponent rider,
                                             @Nullable MountedComponent mounted,
                                             @Nonnull Store<EntityStore> store) {
        if (mounted != null && mounted.getMountedToEntity() != null && mounted.getMountedToEntity().isValid()) {
            return mounted.getMountedToEntity();
        }
        String mountUuid = rider.getMountUuid();
        if (mountUuid.isBlank()) {
            return null;
        }
        try {
            return store.getExternalData().getWorld().getEntityRef(UUID.fromString(mountUuid));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void maybeLogDebug(@Nonnull TransformComponent riderTransform,
                               @Nonnull TransformComponent mountTransform,
                               @Nonnull TameworkRideMountComponent mount) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugRideEnabled() || instance.getLogger() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastDebugMs < 1000) {
            return;
        }
        lastDebugMs = now;
        Vector3d riderPosition = riderTransform.getPosition();
        Vector3d mountPosition = mountTransform.getPosition();
        instance.getLogger().at(Level.INFO).log(
                "TameworkRide debug: riderFollow riderPos=%s/%s/%s mountPos=%s/%s/%s anchor=%s/%s/%s",
                riderPosition.x,
                riderPosition.y,
                riderPosition.z,
                mountPosition.x,
                mountPosition.y,
                mountPosition.z,
                mount.getAnchorX(),
                mount.getAnchorY(),
                mount.getAnchorZ()
        );
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
