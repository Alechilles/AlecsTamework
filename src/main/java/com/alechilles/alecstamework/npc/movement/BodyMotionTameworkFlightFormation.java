package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.group.EntityGroup;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.physics.util.PhysicsMath;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.flock.FlockMembership;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.movement.Steering;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerFly;
import com.hypixel.hytale.server.npc.movement.controllers.ProbeMoveData;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Holds an autonomous flying flock member in a stable slot behind its native flock leader. */
public final class BodyMotionTameworkFlightFormation extends TameworkBodyMotionBase {
    private static final double EPSILON = 1.0E-6;

    private final BuilderBodyMotionTameworkFlightFormation.Formation formation;
    private final double spacing;
    private final double tightness;
    private final double relativeSpeed;
    private final FlyingObstacleAvoidance obstacleAvoidance = new FlyingObstacleAvoidance();
    private final ProbeMoveData obstacleProbeData = new ProbeMoveData();
    private final Vector3d lastLeaderPosition = new Vector3d();
    private final Vector3d leaderVelocity = new Vector3d();
    private final Vector3d leaderHeading = new Vector3d();
    private final Vector3d sampledHeading = new Vector3d();
    private final Vector3d targetPosition = new Vector3d();
    private final Vector3d formationOffset = new Vector3d();
    private final Vector3d translation = new Vector3d();
    private final Vector3d obstacleProbeOrigin = new Vector3d();
    private final FlyingObstacleAvoidance.Probe obstacleProbe = this::probeObstacle;

    @Nullable
    private Ref<EntityStore> trackedLeaderRef;
    @Nullable
    private Ref<EntityStore> obstacleProbeRef;
    @Nullable
    private MotionControllerFly obstacleProbeController;
    @Nullable
    private ComponentAccessor<EntityStore> obstacleProbeAccessor;
    private boolean hasLeaderPosition;
    private boolean hasFormationOffset;
    private double looseDriftSeconds;

    BodyMotionTameworkFlightFormation(@Nonnull BuilderBodyMotionTameworkFlightFormation builder,
                                      @Nonnull BuilderSupport support) {
        super(builder);
        formation = builder.getFormation(support);
        spacing = builder.getSpacing(support);
        tightness = builder.getTightness(support);
        relativeSpeed = builder.getRelativeSpeed(support);
    }

    @Override
    public void activate(@Nonnull Ref<EntityStore> ref,
                         @Nonnull Role role,
                         @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        resetLeaderTracking();
        obstacleAvoidance.reset();
    }

    @Override
    public void deactivate(@Nonnull Ref<EntityStore> ref,
                           @Nonnull Role role,
                           @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        resetLeaderTracking();
        obstacleAvoidance.reset();
    }

    @Override
    public boolean computeSteering(@Nonnull Ref<EntityStore> ref,
                                   @Nonnull Role role,
                                   @Nullable InfoProvider sensorInfo,
                                   double dt,
                                   @Nonnull Steering desiredSteering,
                                   @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        desiredSteering.clear();
        if (formation == BuilderBodyMotionTameworkFlightFormation.Formation.NONE) {
            resetLeaderTracking();
            obstacleAvoidance.reset();
            return false;
        }

        Ref<EntityStore> leaderRef = findFlyingLeader(ref, role, componentAccessor);
        if (leaderRef == null) {
            resetLeaderTracking();
            obstacleAvoidance.reset();
            return false;
        }

        EntityGroup group = resolveGroup(ref, componentAccessor);
        TransformComponent selfTransform = componentAccessor.getComponent(ref, TransformComponent.getComponentType());
        TransformComponent leaderTransform = componentAccessor.getComponent(
                leaderRef, TransformComponent.getComponentType());
        MotionController active = role.getActiveMotionController();
        if (group == null || selfTransform == null || leaderTransform == null
                || !(active instanceof MotionControllerTameworkFormationFly fly)) {
            resetLeaderTracking();
            obstacleAvoidance.reset();
            return false;
        }

        if (!leaderRef.equals(trackedLeaderRef)) {
            resetLeaderTracking();
            trackedLeaderRef = leaderRef;
        }
        sampleLeaderMotion(leaderTransform, dt);
        looseDriftSeconds += Math.max(0.0, dt);
        int followerIndex = resolveFollowerIndex(group, ref, leaderRef);
        FlightFormationSteering.resolveTarget(
                formation, followerIndex, spacing, looseDriftSeconds,
                leaderTransform.getPosition(), leaderHeading, targetPosition);
        targetPosition.sub(leaderTransform.getPosition());
        if (!hasFormationOffset) {
            formationOffset.set(targetPosition);
            hasFormationOffset = true;
        } else {
            FlightFormationSteering.smoothOffset(
                    formationOffset, targetPosition,
                    Math.min(spacing * 0.75, fly.getSteeringSpeedScale() * 0.35), dt, formationOffset);
        }
        targetPosition.set(leaderTransform.getPosition()).add(formationOffset);
        FlightFormationSteering.resolveTranslation(
                selfTransform.getPosition(), targetPosition, leaderVelocity,
                fly.getSteeringSpeedScale(), relativeSpeed, tightness, dt, translation);

        bindObstacleProbe(ref, selfTransform.getPosition(), fly, componentAccessor);
        obstacleAvoidance.beginUpdate(dt);
        try {
            obstacleAvoidance.adjust(
                    translation, leaderHeading, fly.getMaximumSpeed(), fly.getCurrentTurnRadius(),
                    obstacleProbe, translation);
            desiredSteering.setTranslation(translation);
            if (translation.x * translation.x + translation.z * translation.z > EPSILON * EPSILON) {
                desiredSteering.setYaw(PhysicsMath.headingFromDirection(translation.x, translation.z));
            } else {
                desiredSteering.setYaw(PhysicsMath.headingFromDirection(leaderHeading.x, leaderHeading.z));
            }
            desiredSteering.setRelativeTurnSpeed(1.0);
            return true;
        } finally {
            clearObstacleProbeContext();
        }
    }

    /**
     * Resolves the native flock leader only when both this follower and its leader are autonomous airborne fliers.
     * This is shared with the instruction sensor so selection and steering use the same eligibility rules.
     */
    @Nullable
    public static Ref<EntityStore> findFlyingLeader(@Nonnull Ref<EntityStore> ref,
                                                     @Nonnull Role role,
                                                     @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        if (!(role.getActiveMotionController() instanceof MotionControllerTameworkFormationFly)
                || !isAutonomousFlying(role)
                || hasRiderMount(ref, componentAccessor)) {
            return null;
        }
        ComponentType<EntityStore, FlockMembership> membershipType = FlockMembership.getComponentType();
        FlockMembership membership = membershipType == null ? null : componentAccessor.getComponent(ref, membershipType);
        Ref<EntityStore> flockRef = membership == null ? null : membership.getFlockRef();
        if (flockRef == null || !flockRef.isValid()) {
            return null;
        }
        EntityGroup group = componentAccessor.getComponent(flockRef, EntityGroup.getComponentType());
        Ref<EntityStore> leaderRef = group == null || group.isDissolved() ? null : group.getLeaderRef();
        if (leaderRef == null || !leaderRef.isValid() || leaderRef.equals(ref) || hasRiderMount(leaderRef, componentAccessor)) {
            return null;
        }
        NPCEntity leaderNpc = componentAccessor.getComponent(leaderRef, NPCEntity.getComponentType());
        Role leaderRole = leaderNpc == null ? null : leaderNpc.getRole();
        if (leaderRole == null || !isAutonomousFlying(leaderRole)
                || componentAccessor.getComponent(leaderRef, TransformComponent.getComponentType()) == null) {
            return null;
        }
        return leaderRef;
    }

    @Nullable
    private static EntityGroup resolveGroup(@Nonnull Ref<EntityStore> ref,
                                            @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        ComponentType<EntityStore, FlockMembership> membershipType = FlockMembership.getComponentType();
        FlockMembership membership = membershipType == null ? null : componentAccessor.getComponent(ref, membershipType);
        Ref<EntityStore> flockRef = membership == null ? null : membership.getFlockRef();
        if (flockRef == null || !flockRef.isValid()) {
            return null;
        }
        EntityGroup group = componentAccessor.getComponent(flockRef, EntityGroup.getComponentType());
        return group == null || group.isDissolved() ? null : group;
    }

    private static boolean isAutonomousFlying(@Nonnull Role role) {
        MotionController controller = role.getActiveMotionController();
        return controller instanceof MotionControllerFly fly && !fly.onGround();
    }

    private static boolean hasRiderMount(@Nonnull Ref<EntityStore> ref,
                                         @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        ComponentType<EntityStore, TameworkRideMountComponent> tameworkRideType =
                TameworkRideMountComponent.getComponentType();
        if (tameworkRideType != null && componentAccessor.getComponent(ref, tameworkRideType) != null) {
            return true;
        }
        ComponentType<EntityStore, NPCMountComponent> nativeMountType = NPCMountComponent.getComponentType();
        return nativeMountType != null && componentAccessor.getComponent(ref, nativeMountType) != null;
    }

    private void sampleLeaderMotion(@Nonnull TransformComponent leaderTransform, double dt) {
        Vector3d position = leaderTransform.getPosition();
        resolveHeadingFromYaw(leaderTransform.getRotation().yaw(), sampledHeading);
        leaderVelocity.zero();
        if (hasLeaderPosition && dt > EPSILON) {
            leaderVelocity.set(position).sub(lastLeaderPosition).div(dt);
            if (leaderVelocity.x * leaderVelocity.x + leaderVelocity.z * leaderVelocity.z > EPSILON * EPSILON) {
                sampledHeading.set(leaderVelocity.x, 0.0, leaderVelocity.z);
            }
        }
        FlightFormationSteering.smoothHeading(leaderHeading, sampledHeading, dt, leaderHeading);
        lastLeaderPosition.set(position);
        hasLeaderPosition = true;
    }

    private static int resolveFollowerIndex(@Nonnull EntityGroup group,
                                            @Nonnull Ref<EntityStore> selfRef,
                                            @Nonnull Ref<EntityStore> leaderRef) {
        List<Ref<EntityStore>> members = group.getMemberList();
        int followerIndex = 0;
        for (int i = 0; i < members.size(); i++) {
            Ref<EntityStore> member = members.get(i);
            if (!member.isValid() || member.equals(leaderRef)) {
                continue;
            }
            if (member.equals(selfRef)) {
                return followerIndex;
            }
            followerIndex++;
        }
        return followerIndex;
    }

    private void bindObstacleProbe(@Nonnull Ref<EntityStore> ref,
                                   @Nonnull Vector3d selfPosition,
                                   @Nonnull MotionControllerFly fly,
                                   @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        obstacleProbeRef = ref;
        obstacleProbeOrigin.set(selfPosition);
        obstacleProbeController = fly;
        obstacleProbeAccessor = componentAccessor;
    }

    private double probeObstacle(@Nonnull Vector3d direction) {
        Ref<EntityStore> ref = obstacleProbeRef;
        MotionControllerFly fly = obstacleProbeController;
        ComponentAccessor<EntityStore> componentAccessor = obstacleProbeAccessor;
        return ref == null || fly == null || componentAccessor == null
                ? 0.0 : fly.probeMove(ref, obstacleProbeOrigin, direction, obstacleProbeData, componentAccessor);
    }

    private void clearObstacleProbeContext() {
        obstacleProbeRef = null;
        obstacleProbeController = null;
        obstacleProbeAccessor = null;
    }

    private void resetLeaderTracking() {
        trackedLeaderRef = null;
        hasLeaderPosition = false;
        hasFormationOffset = false;
        formationOffset.zero();
        looseDriftSeconds = 0.0;
        lastLeaderPosition.zero();
        leaderVelocity.zero();
        leaderHeading.zero();
        sampledHeading.zero();
    }

    @Nonnull
    static Vector3d resolveHeadingFromYaw(float yaw, @Nonnull Vector3d output) {
        return output.set(-Math.sin(yaw), 0.0, -Math.cos(yaw));
    }
}
