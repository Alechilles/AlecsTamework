package com.alechilles.alecstamework.npc.movement;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.group.EntityGroup;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.physics.util.PhysicsMath;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.flock.FlockMembership;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.movement.Steering;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerWalk;
import com.hypixel.hytale.server.npc.movement.controllers.ProbeMoveData;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Holds a walking flock member in a loose, horizontal slot behind its native flock leader. */
public final class BodyMotionTameworkGroundFormation extends TameworkBodyMotionBase {
    private static final double EPSILON = 1.0E-6;
    private static final double PROBE_INTERVAL_SECONDS = 0.25;
    private static final double PROBE_LOOKAHEAD = 1.5;
    private static final double MIN_PROBE_TRAVEL = 0.5;

    private final double spacing;
    private final double tightness;
    private final double relativeSpeed;
    private final ProbeMoveData probeMoveData = new ProbeMoveData();
    private final Vector3d lastLeaderPosition = new Vector3d();
    private final Vector3d leaderVelocity = new Vector3d();
    private final Vector3d leaderHeading = new Vector3d();
    private final Vector3d sampledHeading = new Vector3d();
    private final Vector3d desiredOffset = new Vector3d();
    private final Vector3d formationOffset = new Vector3d();
    private final Vector3d targetPosition = new Vector3d();
    private final Vector3d translation = new Vector3d();
    private final Vector3d cachedDirection = new Vector3d();
    private final Vector3d leaderDirection = new Vector3d();
    private final Vector3d probeDirection = new Vector3d();

    @Nullable
    private Ref<EntityStore> trackedLeaderRef;
    private boolean hasLeaderPosition;
    private boolean hasFormationOffset;
    private boolean hasCachedDirection;
    private boolean hasProbedDirection;
    private double looseDriftSeconds;
    private double probeCooldown;

    BodyMotionTameworkGroundFormation(@Nonnull BuilderBodyMotionTameworkGroundFormation builder,
                                      @Nonnull BuilderSupport support) {
        super(builder);
        spacing = builder.getSpacing(support);
        tightness = builder.getTightness(support);
        relativeSpeed = builder.getRelativeSpeed(support);
    }

    @Override
    public void activate(@Nonnull Ref<EntityStore> ref, @Nonnull Role role,
                         @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        reset();
    }

    @Override
    public void deactivate(@Nonnull Ref<EntityStore> ref, @Nonnull Role role,
                           @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        reset();
    }

    @Override
    public boolean computeSteering(@Nonnull Ref<EntityStore> ref, @Nonnull Role role,
                                   @Nullable InfoProvider sensorInfo, double dt,
                                   @Nonnull Steering desiredSteering,
                                   @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        desiredSteering.clear();
        if (!Double.isFinite(dt) || dt < 0.0 || !(role.getActiveMotionController() instanceof MotionControllerWalk walk)) {
            reset();
            return false;
        }
        EntityGroup group = resolveGroup(ref, componentAccessor);
        Ref<EntityStore> leaderRef = resolveLeader(ref, group);
        TransformComponent self = componentAccessor.getComponent(ref, TransformComponent.getComponentType());
        TransformComponent leader = leaderRef == null ? null
                : componentAccessor.getComponent(leaderRef, TransformComponent.getComponentType());
        if (group == null || leaderRef == null || self == null || leader == null
                || !isFinite(self.getPosition()) || !isFinite(leader.getPosition())) {
            reset();
            return false;
        }
        if (!leaderRef.equals(trackedLeaderRef)) {
            reset();
            trackedLeaderRef = leaderRef;
        }

        sampleLeaderMotion(leader, dt);
        looseDriftSeconds += dt;
        resolveGroundTarget(resolveFollowerIndex(group, ref, leaderRef), spacing, looseDriftSeconds,
                leader.getPosition(), leaderHeading, self.getPosition().y, targetPosition);
        desiredOffset.set(targetPosition).sub(leader.getPosition());
        desiredOffset.y = 0.0;
        if (!hasFormationOffset) {
            formationOffset.set(desiredOffset);
            hasFormationOffset = true;
        } else {
            FlightFormationSteering.smoothOffset(formationOffset, desiredOffset,
                    Math.min(spacing * 0.75, walk.getMaximumSpeed() * 0.35), dt, formationOffset);
            formationOffset.y = 0.0;
        }
        targetPosition.set(leader.getPosition()).add(formationOffset);
        targetPosition.y = self.getPosition().y;
        resolveGroundTranslation(self.getPosition(), targetPosition, leaderVelocity,
                walk.getMaximumSpeed(), relativeSpeed, tightness, dt, translation);
        double speedScale = horizontalLength(translation);
        if (speedScale <= EPSILON) {
            return false;
        }

        probeCooldown -= dt;
        if (!hasProbedDirection || probeCooldown <= 0.0) {
            hasCachedDirection = selectWalkableDirection(ref, self.getPosition(), walk, componentAccessor);
            hasProbedDirection = true;
            probeCooldown = PROBE_INTERVAL_SECONDS;
        }
        if (!hasCachedDirection) {
            return false;
        }
        probeDirection.set(cachedDirection).mul(Math.min(1.0, speedScale));
        desiredSteering.setTranslation(probeDirection);
        desiredSteering.setYaw(PhysicsMath.headingFromDirection(cachedDirection.x, cachedDirection.z));
        desiredSteering.setRelativeTurnSpeed(1.0);
        return true;
    }

    private boolean selectWalkableDirection(@Nonnull Ref<EntityStore> ref, @Nonnull Vector3d position,
                                            @Nonnull MotionControllerWalk walk,
                                            @Nonnull ComponentAccessor<EntityStore> accessor) {
        if (canWalk(ref, position, translation, walk, accessor)) {
            return normalizeHorizontal(translation, cachedDirection);
        }
        if (normalizeHorizontal(leaderHeading, leaderDirection)
                && canWalk(ref, position, leaderDirection, walk, accessor)) {
            cachedDirection.set(leaderDirection);
            return true;
        }
        cachedDirection.zero();
        return false;
    }

    @Nonnull
    static Vector3d resolveGroundTarget(int followerIndex, double spacing, double driftSeconds,
                                        @Nonnull Vector3d leaderPosition, @Nonnull Vector3d leaderHeading,
                                        double selfY, @Nonnull Vector3d output) {
        FlightFormationSteering.resolveTarget(BuilderBodyMotionTameworkFlightFormation.Formation.LOOSE,
                followerIndex, spacing, driftSeconds, leaderPosition, leaderHeading, output);
        output.y = selfY;
        return output;
    }

    @Nonnull
    static Vector3d resolveGroundTranslation(@Nonnull Vector3d selfPosition,
                                             @Nonnull Vector3d targetPosition,
                                             @Nonnull Vector3d leaderVelocity,
                                             double maximumSpeed, double relativeSpeed, double tightness,
                                             double dt, @Nonnull Vector3d output) {
        leaderDirectionForGround(leaderVelocity, output);
        FlightFormationSteering.resolveTranslation(selfPosition, targetPosition, output,
                maximumSpeed, relativeSpeed, tightness, dt, output);
        output.y = 0.0;
        return output;
    }

    private static void leaderDirectionForGround(@Nonnull Vector3d leaderVelocity, @Nonnull Vector3d output) {
        output.set(leaderVelocity);
        output.y = 0.0;
    }

    private boolean canWalk(@Nonnull Ref<EntityStore> ref, @Nonnull Vector3d position,
                            @Nonnull Vector3d direction, @Nonnull MotionControllerWalk walk,
                            @Nonnull ComponentAccessor<EntityStore> accessor) {
        if (!normalizeHorizontal(direction, probeDirection)) {
            return false;
        }
        probeDirection.mul(PROBE_LOOKAHEAD);
        return walk.probeMove(ref, position, probeDirection, probeMoveData, accessor) >= MIN_PROBE_TRAVEL;
    }

    @Nullable
    private static EntityGroup resolveGroup(@Nonnull Ref<EntityStore> ref,
                                            @Nonnull ComponentAccessor<EntityStore> accessor) {
        ComponentType<EntityStore, FlockMembership> membershipType = FlockMembership.getComponentType();
        FlockMembership membership = membershipType == null ? null : accessor.getComponent(ref, membershipType);
        Ref<EntityStore> flockRef = membership == null ? null : membership.getFlockRef();
        EntityGroup group = flockRef == null || !flockRef.isValid() ? null
                : accessor.getComponent(flockRef, EntityGroup.getComponentType());
        return group == null || group.isDissolved() ? null : group;
    }

    @Nullable
    private static Ref<EntityStore> resolveLeader(@Nonnull Ref<EntityStore> self, @Nullable EntityGroup group) {
        Ref<EntityStore> leader = group == null ? null : group.getLeaderRef();
        return leader == null || !leader.isValid() || leader.equals(self) ? null : leader;
    }

    private void sampleLeaderMotion(@Nonnull TransformComponent leader, double dt) {
        Vector3d position = leader.getPosition();
        BodyMotionTameworkFlightFormation.resolveHeadingFromYaw(leader.getRotation().yaw(), sampledHeading);
        leaderVelocity.zero();
        if (hasLeaderPosition && dt > EPSILON) {
            leaderVelocity.set(position).sub(lastLeaderPosition).div(dt);
            leaderVelocity.y = 0.0;
            if (leaderVelocity.lengthSquared() > EPSILON * EPSILON) {
                sampledHeading.set(leaderVelocity);
            }
        }
        FlightFormationSteering.smoothHeading(leaderHeading, sampledHeading, dt, leaderHeading);
        lastLeaderPosition.set(position);
        hasLeaderPosition = true;
    }

    private static int resolveFollowerIndex(@Nonnull EntityGroup group, @Nonnull Ref<EntityStore> self,
                                            @Nonnull Ref<EntityStore> leader) {
        List<Ref<EntityStore>> members = group.getMemberList();
        int index = 0;
        for (Ref<EntityStore> member : members) {
            if (!member.isValid() || member.equals(leader)) continue;
            if (member.equals(self)) return index;
            index++;
        }
        return index;
    }

    private static boolean normalizeHorizontal(@Nonnull Vector3d source, @Nonnull Vector3d output) {
        double length = horizontalLength(source);
        if (!Double.isFinite(length) || length <= EPSILON) return false;
        output.set(source.x / length, 0.0, source.z / length);
        return true;
    }

    private static double horizontalLength(@Nonnull Vector3d vector) {
        return Math.hypot(vector.x, vector.z);
    }

    private static boolean isFinite(@Nonnull Vector3d position) {
        return Double.isFinite(position.x) && Double.isFinite(position.y) && Double.isFinite(position.z);
    }

    private void reset() {
        trackedLeaderRef = null;
        hasLeaderPosition = false;
        hasFormationOffset = false;
        hasCachedDirection = false;
        hasProbedDirection = false;
        looseDriftSeconds = 0.0;
        probeCooldown = 0.0;
        lastLeaderPosition.zero();
        leaderVelocity.zero();
        leaderHeading.zero();
        formationOffset.zero();
        cachedDirection.zero();
    }
}
