package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.movement.NativeSwimPhysics;
import com.alechilles.alecstamework.npc.movement.NativeSwimRiderComponent;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.Interactions;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.modules.physics.systems.IVelocityModifyingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import org.joml.Vector3d;

/** Simulates only opted-in native riders. Native mounting still owns NPC and rider attachment. */
public final class NativeSwimSystem extends EntityTickingSystem<EntityStore> implements IVelocityModifyingSystem {
    private final ComponentType<EntityStore, NativeSwimRiderComponent> type = NativeSwimRiderComponent.getComponentType();
    private final Query<EntityStore> query = Query.and(type, Player.getComponentType(), UUIDComponent.getComponentType());

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
                     @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
        var riderRef = chunk.getReferenceTo(index);
        var rider = chunk.getComponent(index, type);
        var world = store.getExternalData().getWorld();
        var mountRef = rider.mountUuid == null ? null : world.getEntityRef(rider.mountUuid);
        var mount = mountRef == null || !mountRef.isValid() ? null
                : buffer.getComponent(mountRef, NPCMountComponent.getComponentType());
        var riderId = chunk.getComponent(index, UUIDComponent.getComponentType()).getUuid();
        if (rider.settings == null || mount == null || mount.getOwnerPlayerRef() == null
                || !riderId.equals(mount.getOwnerPlayerRef().getUuid())) {
            if (rider.boost) logBoost("mount_ended", rider.speed, rider.speed, rider.cooldown);
            var existing = buffer.getComponent(riderRef, Interactions.getComponentType());
            var restored = rider.restoreAbility(existing);
            if (restored != existing) buffer.putComponent(riderRef, Interactions.getComponentType(), restored);
            buffer.removeComponent(riderRef, type);
            return;
        }
        var movement = buffer.getComponent(mountRef, MovementStatesComponent.getComponentType());
        var states = movement == null ? null : movement.getMovementStates();
        var riderMovement = buffer.getComponent(riderRef, MovementStatesComponent.getComponentType());
        var riderStates = riderMovement == null ? null : riderMovement.getMovementStates();
        boolean inWater = (states != null && (states.inFluid || states.swimming))
                || (riderStates != null && (riderStates.inFluid || riderStates.swimming));
        if (!inWater) {
            if (rider.boost) logBoost("outside_water", rider.speed, rider.speed, rider.cooldown);
            rider.speed = 0;
            rider.boost = false;
            rider.cooldown = Math.max(0, rider.cooldown - dt);
            buffer.putComponent(riderRef, type, rider);
            return;
        }
        var velocity = buffer.getComponent(riderRef, Velocity.getComponentType());
        var head = buffer.getComponent(riderRef, HeadRotation.getComponentType());
        var transform = buffer.getComponent(mountRef, TransformComponent.getComponentType());
        if (velocity == null || transform == null) {
            if (rider.boost) {
                logBoost("missing_movement_components", rider.speed, rider.speed, rider.cooldown);
                rider.boost = false;
                buffer.putComponent(riderRef, type, rider);
            }
            return;
        }
        double forward = System.currentTimeMillis() - rider.lastInputMs <= 500 ? rider.forward : 0;
        var step = NativeSwimPhysics.advance(rider.speed, rider.cooldown, forward, rider.boost, dt, rider.settings);
        if (rider.boost) logBoost("processed", rider.speed, step.speed(), step.boostCooldownSeconds());
        rider.speed = step.speed();
        rider.cooldown = step.boostCooldownSeconds();
        rider.boost = false;
        double yaw = head == null ? transform.getRotation().yaw() : head.getRotation().yaw();
        // Native jump still owns ascent. Crouch supplies the missing downward swim control.
        double verticalCorrection = rider.isDescending(System.currentTimeMillis())
                ? Math.min(0, -5.0 - velocity.getY()) : 0;
        velocity.addInstruction(new Vector3d(-Math.sin(yaw) * rider.speed - velocity.getX(), verticalCorrection,
                -Math.cos(yaw) * rider.speed - velocity.getZ()), null, ChangeVelocityType.Add);
        buffer.putComponent(riderRef, type, rider);
    }

    @Override @Nonnull public Query<EntityStore> getQuery() { return query; }

    private static void logBoost(String result, double before, double after, double cooldown) {
        var plugin = Tamework.getInstance();
        if (plugin != null && plugin.isDebugRideEnabled()) {
            plugin.getLogger().atInfo().log("Tamework native swim boost: result=%s speed=%s->%s cooldown=%s",
                    result, before, after, cooldown);
        }
    }
}
