package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.alechilles.alecstamework.npc.components.TameworkRideRiderComponent;
import com.alechilles.alecstamework.npc.movement.MotionControllerTameworkFly;
import com.alechilles.alecstamework.npc.movement.MotionControllerTameworkRideWalk;
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
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.systems.SteeringSystem;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Keeps the rider's client camera attached to the Tamework-controlled mount without moving the real player every tick.
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
        double clientSpeedModifier = resolveClientSpeedModifier(mountRef, mount, store);
        if (!rider.isClientCameraApplied() || shouldUpdateCameraSpeed(rider, clientSpeedModifier)) {
            commandBuffer.run(bufferStore -> {
                if (!riderRef.isValid() || !mountRef.isValid()) {
                    return;
                }
                TameworkRideRiderComponent currentRider = bufferStore.getComponent(riderRef, rideRiderComponentType);
                TameworkRideMountComponent currentMount = bufferStore.getComponent(mountRef, rideMountComponentType);
                if (currentRider == null || currentMount == null) {
                    return;
                }
                double currentClientSpeedModifier = resolveClientSpeedModifier(mountRef, currentMount, bufferStore);
                boolean updated = currentRider.isClientCameraApplied()
                        ? MountedRideClientAttachment.updateCamera(bufferStore, riderRef, currentClientSpeedModifier)
                        : MountedRideClientAttachment.attach(
                                bufferStore,
                                riderRef,
                                mountRef,
                                currentMount,
                                currentClientSpeedModifier
                        );
                if (updated) {
                    currentRider.setClientCameraApplied(true);
                    currentRider.setClientSpeedModifier(currentClientSpeedModifier);
                    bufferStore.putComponent(riderRef, rideRiderComponentType, currentRider);
                }
            });
        }
        maybeLogDebug(riderTransform, mountTransform, mount, clientSpeedModifier);
    }

    @Nullable
    private Ref<EntityStore> resolveMountRef(@Nonnull TameworkRideRiderComponent rider,
                                             @Nullable MountedComponent mounted,
                                             @Nonnull Store<EntityStore> store) {
        String mountUuid = rider.getMountUuid();
        if (!mountUuid.isBlank()) {
            try {
                return store.getExternalData().getWorld().getEntityRef(UUID.fromString(mountUuid));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return mounted != null && mounted.getMountedToEntity() != null && mounted.getMountedToEntity().isValid()
                ? mounted.getMountedToEntity()
                : null;
    }

    private boolean shouldUpdateCameraSpeed(@Nonnull TameworkRideRiderComponent rider, double speedModifier) {
        double previous = rider.getClientSpeedModifier();
        return previous <= 0.0 || Math.abs(previous - speedModifier) > 0.01;
    }

    private double resolveClientSpeedModifier(@Nonnull Ref<EntityStore> mountRef,
                                              @Nonnull TameworkRideMountComponent mount,
                                              @Nonnull Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(mountRef, NPCEntity.getComponentType());
        Role role = npc == null ? null : npc.getRole();
        MotionController active = role == null ? null : role.getActiveMotionController();
        if (active instanceof MotionControllerTameworkFly fly) {
            return fly.getMountedClientSpeed(mount.isRiderSprinting());
        }
        if (active instanceof MotionControllerTameworkRideWalk walk) {
            return walk.getMountedClientSpeed(mount.isRiderSprinting());
        }
        return MountedRideClientAttachment.DEFAULT_RIDE_INPUT_SPEED_MODIFIER;
    }

    private void maybeLogDebug(@Nonnull TransformComponent riderTransform,
                               @Nonnull TransformComponent mountTransform,
                               @Nonnull TameworkRideMountComponent mount,
                               double clientSpeedModifier) {
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
                "TameworkRide debug: riderFollow riderPos=%s/%s/%s mountPos=%s/%s/%s anchor=%s/%s/%s clientSpeed=%s",
                riderPosition.x,
                riderPosition.y,
                riderPosition.z,
                mountPosition.x,
                mountPosition.y,
                mountPosition.z,
                mount.getAnchorX(),
                mount.getAnchorY(),
                mount.getAnchorZ(),
                clientSpeedModifier
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
