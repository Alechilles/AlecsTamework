package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.alechilles.alecstamework.npc.movement.CompanionFollowFlockService;
import com.alechilles.alecstamework.npc.sensorinfo.TameworkTargetPositionInfo;
import com.alechilles.alecstamework.npc.sensorinfo.TameworkTargetPositionInfoProvider;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkFollowFormation;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerFly;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerWalk;
import com.hypixel.hytale.server.npc.movement.controllers.ProbeMoveData;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.UUID;
import javax.annotation.Nonnull;
import org.joml.Vector3d;

/** Supplies a point, leaving the owner target intact for commands, watching and teleport recovery. */
public final class SensorTameworkFollowFormation extends TameworkSensorBase {
    private final int targetSlot;
    private final double range;
    private final double spacing;
    private final double gap;
    private final double altitude;
    private final TameworkTargetPositionInfo position = new TameworkTargetPositionInfo();
    private final InfoProvider info = new TameworkTargetPositionInfoProvider(null, position);
    private final Vector3d target = new Vector3d();
    private final Vector3d probeDirection = new Vector3d();
    private final ProbeMoveData probe = new ProbeMoveData();
    private UUID masterId;
    private double probeRemaining;
    private boolean accessible = true;

    public SensorTameworkFollowFormation(BuilderSensorTameworkFollowFormation builder, int targetSlot,
                                         double range, double spacing, double altitude, double gap) {
        super(builder);
        this.targetSlot = targetSlot;
        this.range = range;
        this.spacing = spacing;
        this.gap = gap;
        this.altitude = altitude;
    }

    @Override public boolean matches(@Nonnull Ref<EntityStore> self, @Nonnull Role role, double dt,
                                     @Nonnull Store<EntityStore> store) {
        position.clear();
        var marked = NpcSupportAccess.markedEntity(role, self, store);
        var master = marked == null ? null : marked.getMarkedEntityRef(targetSlot);
        var controller = role.getActiveMotionController();
        if (master == null || !master.isValid()
                || !(controller instanceof MotionControllerWalk || controller instanceof MotionControllerFly)) return false;
        var rideType = TameworkRideMountComponent.getComponentType();
        if ((rideType != null && store.getComponent(self, rideType) != null)
                || store.getComponent(self, NPCMountComponent.getComponentType()) != null) return false;
        var selfTransform = store.getComponent(self, TransformComponent.getComponentType());
        var masterTransform = store.getComponent(master, TransformComponent.getComponentType());
        var identity = store.getComponent(master, UUIDComponent.getComponentType());
        if (selfTransform == null || masterTransform == null || identity == null) return false;
        boolean flying = controller instanceof MotionControllerFly;
        var bounds = store.getComponent(self, BoundingBox.getComponentType());
        // Spacing remains a fallback for NPCs without a usable hitbox. Actual animals
        // reserve their own half-width/depth plus a separate edge-to-edge comfort gap.
        double radius = Math.max(0.1, (spacing - gap) * 0.5);
        if (bounds != null) {
            var box = bounds.getBoundingBox();
            double dimension = Math.max(box.width(), box.depth());
            if (Double.isFinite(dimension) && dimension > 0) radius = dimension * 0.5;
        }
        var slot = CompanionFollowFlockService.get().request(self, master, targetSlot,
                flying, radius, gap, range, altitude, store);
        if (slot == null || selfTransform.getPosition().distanceSquared(masterTransform.getPosition()) > range * range) return false;
        if (!identity.getUuid().equals(masterId)) {
            masterId = identity.getUuid();
            probeRemaining = 0;
        }
        double height = flying ? altitude + (slot.index() % 3) * 1.5 : 0;
        target.set(slot.x(), flying ? masterTransform.getPosition().y + height
                : selfTransform.getPosition().y, slot.z());
        if (controller instanceof MotionControllerWalk walk) {
            probeRemaining -= dt;
            if (probeRemaining <= 0) {
                probeRemaining = 0.25;
                probeDirection.set(target).sub(selfTransform.getPosition());
                probeDirection.y = 0;
                double distance = probeDirection.length();
                if (distance > 0.25) {
                    double lookahead = Math.min(distance, 3);
                    probeDirection.mul(lookahead / distance);
                    accessible = walk.probeMove(self, selfTransform.getPosition(), probeDirection, probe, store)
                            >= lookahead * 0.9;
                } else accessible = true;
            }
            // A slot is optional: existing direct follow and recovery take over at walls/gates.
            if (!accessible) return false;
        }
        position.setTarget(target.x, target.y, target.z);
        return true;
    }

    @Override public InfoProvider getSensorInfo() { return info; }
}
