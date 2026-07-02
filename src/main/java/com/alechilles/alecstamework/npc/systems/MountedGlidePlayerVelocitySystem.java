package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.config.assets.TwMountedGlideConfig;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import com.alechilles.alecstamework.npc.movement.MountedGlidePhysics;
import com.alechilles.alecstamework.npc.movement.MountedGlidePhysicsState;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
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
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.modules.physics.systems.IVelocityModifyingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Drives mounted glide by applying the computed glide velocity to the native mount rider through
 * {@code Velocity.addInstruction}.
 */
public final class MountedGlidePlayerVelocitySystem
        extends EntityTickingSystem<EntityStore>
        implements IVelocityModifyingSystem {
    private static final TwMountedGlideConfig DEFAULT_CONFIG = new TwMountedGlideConfig();

    private final ComponentType<EntityStore, TameworkMountedGlideComponent> mountComponentType;
    private final ComponentType<EntityStore, NPCMountComponent> nativeMountComponentType;
    private final ComponentType<EntityStore, TransformComponent> transformComponentType;
    private final ComponentType<EntityStore, Velocity> velocityComponentType;
    private final ComponentType<EntityStore, MovementStatesComponent> movementStatesComponentType;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, MountedGlideInputCaptureSystem.class)
    );

    public MountedGlidePlayerVelocitySystem(
            @Nonnull ComponentType<EntityStore, TameworkMountedGlideComponent> mountComponentType,
            @Nonnull ComponentType<EntityStore, NPCMountComponent> nativeMountComponentType,
            @Nonnull ComponentType<EntityStore, TransformComponent> transformComponentType,
            @Nullable ComponentType<EntityStore, Velocity> velocityComponentType,
            @Nonnull ComponentType<EntityStore, MovementStatesComponent> movementStatesComponentType) {
        this.mountComponentType = mountComponentType;
        this.nativeMountComponentType = nativeMountComponentType;
        this.transformComponentType = transformComponentType;
        this.velocityComponentType = velocityComponentType == null ? Velocity.getComponentType() : velocityComponentType;
        this.movementStatesComponentType = movementStatesComponentType;
        this.query = Query.and(mountComponentType, nativeMountComponentType, transformComponentType);
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Ref<EntityStore> mountRef = archetypeChunk.getReferenceTo(index);
        TameworkMountedGlideComponent mount = archetypeChunk.getComponent(index, mountComponentType);
        NPCMountComponent nativeMount = archetypeChunk.getComponent(index, nativeMountComponentType);
        TransformComponent mountTransform = archetypeChunk.getComponent(index, transformComponentType);
        if (mountRef == null || mount == null || nativeMount == null || mountTransform == null) {
            return;
        }

        Ref<EntityStore> riderRef = resolveRiderRef(nativeMount, store);
        if (riderRef == null) {
            return;
        }
        TwMountedGlideConfig config = resolveConfig(mount);
        boolean riderOnGround = isRiderOnGround(riderRef, commandBuffer);
        boolean mountOnGround = isMountOnGround(mountRef, commandBuffer);
        if (!mount.isFlightActive()) {
            if (!shouldActivateFlight(mount, mountOnGround)) {
                return;
            }
            mount.setFlightActive(true);
        }
        if (shouldReturnToGroundMode(mount, riderOnGround)) {
            mount.setFlightActive(false);
            mount.initializePhysicsState(config);
            commandBuffer.putComponent(mountRef, mountComponentType, mount);
            return;
        }
        Velocity velocity = commandBuffer.getComponent(riderRef, velocityComponentType);
        if (velocity == null) {
            return;
        }

        MountedGlidePhysicsState state = mount.toPhysicsState();
        MountedGlidePhysics.Input input = new MountedGlidePhysics.Input(
                resolvePitchRadians(mount),
                mount.getForwardIntent(),
                mount.getStrafeIntent(),
                mount.isJumpHeld(),
                mount.isSprinting(),
                mount.isCrouching()
        );
        MountedGlidePhysics.Output output = MountedGlidePhysics.update(state, config, input, dt);
        mount.applyPhysicsState(state);
        commandBuffer.putComponent(mountRef, mountComponentType, mount);

        double yawRadians = resolveYawRadians(mount, mountTransform);
        double forwardX = -Math.sin(yawRadians);
        double forwardZ = -Math.cos(yawRadians);
        Vector3d velocityVector = new Vector3d(
                forwardX * output.forwardSpeed(),
                output.verticalVelocity(),
                forwardZ * output.forwardSpeed()
        );
        velocity.addInstruction(velocityVector, null, ChangeVelocityType.Set);
    }

    static boolean shouldActivateFlight(@Nonnull TameworkMountedGlideComponent mount, boolean mountOnGround) {
        return mount.isFlightActive() || !mountOnGround || mount.shouldRequestFlap();
    }

    static boolean resolveMountOnGroundForActivation(@Nullable Boolean mountOnGround) {
        return mountOnGround == null || mountOnGround;
    }

    static boolean shouldReturnToGroundMode(@Nonnull TameworkMountedGlideComponent mount, boolean riderOnGround) {
        return mount.isFlightActive() && riderOnGround && !mount.isJumpHeld();
    }

    @Nullable
    private Ref<EntityStore> resolveRiderRef(@Nonnull NPCMountComponent nativeMount,
                                             @Nonnull Store<EntityStore> store) {
        if (nativeMount.getOwnerPlayerRef() == null) {
            return null;
        }
        Ref<EntityStore> riderRef = nativeMount.getOwnerPlayerRef().getReference();
        if (riderRef == null || !riderRef.isValid() || riderRef.getStore() != store) {
            return null;
        }
        return riderRef;
    }

    private boolean isRiderOnGround(@Nonnull Ref<EntityStore> riderRef,
                                    @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Boolean onGround = isOnGround(riderRef, commandBuffer);
        return onGround != null && onGround;
    }

    private boolean isMountOnGround(@Nonnull Ref<EntityStore> mountRef,
                                    @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Boolean onGround = isOnGround(mountRef, commandBuffer);
        return resolveMountOnGroundForActivation(onGround);
    }

    @Nullable
    private Boolean isOnGround(@Nonnull Ref<EntityStore> ref,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        MovementStatesComponent movementStatesComponent =
                commandBuffer.getComponent(ref, movementStatesComponentType);
        if (movementStatesComponent == null) {
            return null;
        }
        MovementStates states = movementStatesComponent.getMovementStates();
        return states == null ? null : states.onGround;
    }

    @Nonnull
    private TwMountedGlideConfig resolveConfig(@Nonnull TameworkMountedGlideComponent mount) {
        if (!mount.getConfigId().isBlank()) {
            var assetMap = TwMountedGlideConfig.getAssetMap();
            if (assetMap != null && assetMap.getAssetMap() != null) {
                TwMountedGlideConfig byId = assetMap.getAssetMap().get(mount.getConfigId());
                if (byId != null) {
                    return byId;
                }
            }
        }
        return DEFAULT_CONFIG;
    }

    private double resolvePitchRadians(@Nonnull TameworkMountedGlideComponent mount) {
        return mount.hasLookRotation() ? Math.toRadians(mount.getLookPitchDegrees()) : 0.0;
    }

    private double resolveYawRadians(@Nonnull TameworkMountedGlideComponent mount,
                                     @Nonnull TransformComponent mountTransform) {
        if (mount.hasLookRotation()) {
            return Math.toRadians(mount.getLookYawDegrees());
        }
        Rotation3f rotation = mountTransform.getRotation();
        return rotation == null ? 0.0 : rotation.yaw();
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
