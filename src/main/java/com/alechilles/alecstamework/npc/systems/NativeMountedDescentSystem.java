package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.avatarflight.AvatarFlightSourceComponent;
import com.alechilles.alecstamework.config.assets.TwMountedDescentConfig;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import com.alechilles.alecstamework.npc.movement.NativeMountMovementSettingsService;
import com.alechilles.alecstamework.npc.movement.NativeMountedDescentPhysics;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.modules.physics.systems.IVelocityModifyingSystem;
import com.hypixel.hytale.server.core.modules.physics.util.PhysicsConstants;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Applies configured vertical physics to descending riders of native NPC mounts.
 */
public final class NativeMountedDescentSystem
        extends EntityTickingSystem<EntityStore>
        implements IVelocityModifyingSystem {
    private final ComponentType<EntityStore, NPCMountComponent> nativeMountType;
    private final ComponentType<EntityStore, Velocity> velocityType;
    private final ComponentType<EntityStore, MovementStatesComponent> movementStatesType;
    private final ComponentType<EntityStore, TameworkMountedGlideComponent> mountedGlideType;
    private final ComponentType<EntityStore, AvatarFlightSourceComponent> avatarFlightSourceType;
    private final Query<EntityStore> query;
    private final NativeMountMovementSettingsService movementSettings = new NativeMountMovementSettingsService();

    public NativeMountedDescentSystem(
            @Nonnull ComponentType<EntityStore, NPCMountComponent> nativeMountType,
            @Nonnull ComponentType<EntityStore, Velocity> velocityType,
            @Nonnull ComponentType<EntityStore, MovementStatesComponent> movementStatesType,
            @Nullable ComponentType<EntityStore, TameworkMountedGlideComponent> mountedGlideType,
            @Nullable ComponentType<EntityStore, AvatarFlightSourceComponent> avatarFlightSourceType) {
        this.nativeMountType = nativeMountType;
        this.velocityType = velocityType;
        this.movementStatesType = movementStatesType;
        this.mountedGlideType = mountedGlideType;
        this.avatarFlightSourceType = avatarFlightSourceType;
        this.query = Query.and(nativeMountType);
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> chunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Ref<EntityStore> mountRef = chunk.getReferenceTo(index);
        NPCMountComponent nativeMount = chunk.getComponent(index, nativeMountType);
        if (mountRef == null || nativeMount == null) {
            return;
        }

        Ref<EntityStore> riderRef = NativeMountMovementSettingsService.resolveMountedRiderRef(nativeMount, store);
        if (riderRef == null) {
            return;
        }
        if (hasComponent(mountRef, store, mountedGlideType)
                || hasComponent(mountRef, store, avatarFlightSourceType)) {
            return;
        }

        Velocity velocity = commandBuffer.getComponent(riderRef, velocityType);
        NativeMountedDescentPhysics.Settings settings = resolveSettings(mountRef, nativeMount, store);
        boolean riderOnGround = isOnGround(riderRef, commandBuffer);
        boolean mountOnGround = isOnGround(mountRef, commandBuffer);
        double observedVerticalVelocity = velocity == null ? 0.0 : velocity.getY();
        if (!shouldApply(riderOnGround, mountOnGround, observedVerticalVelocity, false, settings)) {
            return;
        }
        double correction = verticalCorrection(observedVerticalVelocity, settings, dt);
        if (correction > 0.0) {
            velocity.addInstruction(new Vector3d(0.0, correction, 0.0), null, ChangeVelocityType.Add);
        }
    }

    static boolean shouldApply(boolean riderOnGround,
                               boolean mountOnGround,
                               double verticalVelocity,
                               boolean excludedByAnotherController,
                               @Nullable NativeMountedDescentPhysics.Settings settings) {
        return !riderOnGround
                && !mountOnGround
                && verticalVelocity < 0.0
                && !excludedByAnotherController
                && settings != null
                && settings.isValid();
    }

    static double verticalCorrection(double observedVerticalVelocity,
                                     @Nonnull NativeMountedDescentPhysics.Settings settings,
                                     double dt) {
        if (observedVerticalVelocity >= 0.0 || !settings.isValid() || !Double.isFinite(dt) || dt <= 0.0) {
            return 0.0;
        }
        if (observedVerticalVelocity < -settings.maxDownwardSpeed()) {
            return -settings.maxDownwardSpeed() - observedVerticalVelocity;
        }
        double multiplier = Math.min(1.0, settings.fallAccelerationMultiplier());
        return PhysicsConstants.GRAVITY_ACCELERATION * (1.0 - multiplier) * dt;
    }

    @Nullable
    private NativeMountedDescentPhysics.Settings resolveSettings(@Nonnull Ref<EntityStore> mountRef,
                                                                 @Nonnull NPCMountComponent nativeMount,
                                                                 @Nonnull Store<EntityStore> store) {
        String sourceRoleId = NativeMountMovementSettingsService.resolveManagedRoleId(mountRef, store);
        String movementConfigId = movementSettings.resolveMountedMovementConfigId(
                sourceRoleId,
                NativeMountMovementSettingsService.resolveMountedSourceRoleScopes(nativeMount)
        );
        return TwMountedDescentConfig.resolveForMovementConfigId(movementConfigId).orElse(null);
    }

    private boolean isOnGround(@Nonnull Ref<EntityStore> ref,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        MovementStatesComponent component = commandBuffer.getComponent(ref, movementStatesType);
        MovementStates movementStates = component == null ? null : component.getMovementStates();
        return movementStates != null && movementStates.onGround;
    }

    private static <T extends Component<EntityStore>> boolean hasComponent(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nullable ComponentType<EntityStore, T> componentType) {
        return componentType != null && store.getComponent(ref, componentType) != null;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }
}
