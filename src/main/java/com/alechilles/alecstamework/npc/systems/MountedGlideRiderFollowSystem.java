package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideRiderComponent;
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
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.systems.SteeringSystem;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Keeps mounted-glide rider clients attached to the gliding mount's custom camera anchor.
 */
public final class MountedGlideRiderFollowSystem extends EntityTickingSystem<EntityStore> {
    private final ComponentType<EntityStore, MountedComponent> mountedComponentType;
    private final ComponentType<EntityStore, TameworkMountedGlideRiderComponent> riderComponentType;
    private final ComponentType<EntityStore, TameworkMountedGlideComponent> mountComponentType;
    private final ComponentType<EntityStore, TransformComponent> transformComponentType;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, SteeringSystem.class),
            new SystemDependency<>(Order.BEFORE, MountedGlideCleanupSystem.class)
    );
    private long lastDebugMs;

    public MountedGlideRiderFollowSystem(
            @Nonnull ComponentType<EntityStore, MountedComponent> mountedComponentType,
            @Nonnull ComponentType<EntityStore, TameworkMountedGlideRiderComponent> riderComponentType,
            @Nonnull ComponentType<EntityStore, TameworkMountedGlideComponent> mountComponentType,
            @Nonnull ComponentType<EntityStore, TransformComponent> transformComponentType) {
        this.mountedComponentType = mountedComponentType;
        this.riderComponentType = riderComponentType;
        this.mountComponentType = mountComponentType;
        this.transformComponentType = transformComponentType;
        this.query = Query.and(riderComponentType, transformComponentType, Player.getComponentType());
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Ref<EntityStore> riderRef = archetypeChunk.getReferenceTo(index);
        TameworkMountedGlideRiderComponent rider = archetypeChunk.getComponent(index, riderComponentType);
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
        TameworkMountedGlideComponent mount = store.getComponent(mountRef, mountComponentType);
        TransformComponent mountTransform = store.getComponent(mountRef, transformComponentType);
        if (mount == null || mountTransform == null) {
            return;
        }
        double clientSpeedModifier = resolveClientSpeedModifier(mount);
        if (!rider.isClientCameraApplied() || shouldUpdateCameraSpeed(rider, clientSpeedModifier)) {
            commandBuffer.run(bufferStore -> refreshClientAttachment(
                    riderRef,
                    mountRef,
                    clientSpeedModifier,
                    bufferStore
            ));
        }
        maybeLogDebug(riderTransform, mountTransform, mount, clientSpeedModifier);
    }

    private void refreshClientAttachment(@Nonnull Ref<EntityStore> riderRef,
                                         @Nonnull Ref<EntityStore> mountRef,
                                         double clientSpeedModifier,
                                         @Nonnull Store<EntityStore> bufferStore) {
        if (!riderRef.isValid() || !mountRef.isValid()) {
            return;
        }
        TameworkMountedGlideRiderComponent currentRider = bufferStore.getComponent(riderRef, riderComponentType);
        TameworkMountedGlideComponent currentMount = bufferStore.getComponent(mountRef, mountComponentType);
        if (currentRider == null || currentMount == null) {
            return;
        }
        double currentClientSpeedModifier = resolveClientSpeedModifier(currentMount);
        boolean updated = currentRider.isClientCameraApplied()
                ? MountedRideClientAttachment.updateCamera(
                        bufferStore,
                        riderRef,
                        mountRef,
                        currentMount,
                        currentClientSpeedModifier
                )
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
            bufferStore.putComponent(riderRef, riderComponentType, currentRider);
        }
    }

    @Nullable
    private Ref<EntityStore> resolveMountRef(@Nonnull TameworkMountedGlideRiderComponent rider,
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

    private boolean shouldUpdateCameraSpeed(@Nonnull TameworkMountedGlideRiderComponent rider, double speedModifier) {
        double previous = rider.getClientSpeedModifier();
        return previous <= 0.0 || Math.abs(previous - speedModifier) > 0.01;
    }

    private double resolveClientSpeedModifier(@Nonnull TameworkMountedGlideComponent mount) {
        return mount.getGlideSpeed() > 0.0
                ? mount.getGlideSpeed()
                : MountedRideClientAttachment.DEFAULT_RIDE_INPUT_SPEED_MODIFIER;
    }

    private void maybeLogDebug(@Nonnull TransformComponent riderTransform,
                               @Nonnull TransformComponent mountTransform,
                               @Nonnull TameworkMountedGlideComponent mount,
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
                "TameworkMountedGlide debug: riderFollow riderPos=%s/%s/%s mountPos=%s/%s/%s anchor=%s/%s/%s clientSpeed=%s",
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
