package com.alechilles.alecstamework.npc.movement;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.movement.Steering;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerWalk;
import com.hypixel.hytale.server.npc.movement.controllers.RailStepConfig;
import com.hypixel.hytale.server.npc.movement.controllers.RailStepResult;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Owns one NPC's leap inside its world-thread body-motion callback. */
public final class BodyMotionTameworkLeap extends TameworkBodyMotionBase {
    private final Arc arc;
    private final Vector3d target = new Vector3d();
    private final Vector3d delta = new Vector3d();
    private final Vector3d zero = new Vector3d();
    private final RailStepConfig collisionConfig = new RailStepConfig();
    private final RailStepResult collisionResult = new RailStepResult();
    private boolean started;

    BodyMotionTameworkLeap(BuilderBodyMotionTameworkLeap builder, double duration, double height,
                           double stopDistance) {
        super(builder);
        arc = new Arc(duration, height, stopDistance);
    }

    @Override
    public void activate(@Nonnull Ref<EntityStore> ref, @Nonnull Role role,
                         @Nonnull ComponentAccessor<EntityStore> accessor) {
        started = false;
    }

    @Override
    public void deactivate(@Nonnull Ref<EntityStore> ref, @Nonnull Role role,
                           @Nonnull ComponentAccessor<EntityStore> accessor) {
        if (started && role.getActiveMotionController() != null) {
            role.getActiveMotionController().clearExternalForces();
        }
        started = false;
    }

    @Override
    public boolean computeSteering(@Nonnull Ref<EntityStore> ref, @Nonnull Role role,
                                   @Nullable InfoProvider sensorInfo, double dt,
                                   @Nonnull Steering steering,
                                   @Nonnull ComponentAccessor<EntityStore> accessor) {
        steering.clear();
        if (!(role.getActiveMotionController() instanceof MotionControllerWalk controller)) {
            return false;
        }
        TransformComponent transform = accessor.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            return false;
        }
        if (!started) {
            if (sensorInfo == null || !sensorInfo.hasPosition() || sensorInfo.getPositionProvider() == null
                    || !sensorInfo.getPositionProvider().providePosition(target)
                    || !arc.start(transform.getPosition(), target)) {
                return false;
            }
            started = true;
            controller.clearExternalForces();
            // Walk clears its grounded flag here. Rail steps then own movement until arrival.
            controller.setVelocity(zero, null, false);
        }
        if (arc.finished() || !Double.isFinite(dt) || dt <= 0) {
            return true;
        }
        arc.advance(dt, delta).sub(transform.getPosition());
        // Native movement performs block sweeps, updates the real entity, and suppresses
        // the ordinary walk/gravity step for this tick. No teleport or separate tick system.
        controller.applyRailStep(ref, role, delta, collisionConfig, collisionResult, accessor);
        if (collisionResult.obstructed) {
            arc.stop();
        }
        if (arc.finished()) {
            controller.clearExternalForces();
        }
        return true;
    }

    /** Captured endpoint and elapsed flight time, independent of live target movement. */
    static final class Arc {
        private final double duration;
        private final double height;
        private final double stopDistance;
        private final Vector3d start = new Vector3d();
        private final Vector3d end = new Vector3d();
        private double elapsed;

        Arc(double duration, double height) {
            this(duration, height, 0);
        }

        Arc(double duration, double height, double stopDistance) {
            this.duration = duration;
            this.height = height;
            this.stopDistance = stopDistance;
        }

        boolean start(Vector3dc from, Vector3dc to) {
            if (!from.isFinite() || !to.isFinite()) {
                return false;
            }
            start.set(from);
            end.set(to);
            if (stopDistance > 0) {
                // Land short of the target along the approach line, never behind takeoff.
                double dx = to.x() - from.x();
                double dz = to.z() - from.z();
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                double travel = Math.max(0, horizontal - stopDistance);
                double scale = horizontal > 0 ? travel / horizontal : 0;
                end.x = from.x() + dx * scale;
                end.z = from.z() + dz * scale;
            }
            elapsed = 0;
            return true;
        }

        Vector3d advance(double dt, Vector3d output) {
            elapsed = Math.min(duration, elapsed + dt);
            double progress = elapsed / duration;
            output.set(start).lerp(end, progress);
            output.y += 4 * height * progress * (1 - progress);
            return output;
        }

        boolean finished() {
            return elapsed >= duration;
        }

        void stop() {
            elapsed = duration;
        }
    }
}
