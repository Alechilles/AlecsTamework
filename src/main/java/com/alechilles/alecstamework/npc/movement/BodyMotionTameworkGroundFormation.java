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
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerWalk;
import com.hypixel.hytale.server.npc.movement.controllers.ProbeMoveData;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Steady herd travel with tolerant follower slots and bounded local obstacle recovery. */
public final class BodyMotionTameworkGroundFormation extends TameworkBodyMotionBase {
    private static final double EPSILON = 1.0E-6;
    private static final double PROBE_INTERVAL_SECONDS = 0.25;
    private static final double PROBE_LOOKAHEAD = 3.0;
    // A partial move ends at an obstacle; it is not a clear direction for continued travel.
    private static final double MIN_PROBE_TRAVEL = PROBE_LOOKAHEAD - 0.05;
    private static final double TURN_RATE = Math.toRadians(45);
    private static final double WALK_ALIGNMENT = Math.cos(Math.toRadians(15));
    private static final double DETOUR_ALIGNMENT = Math.cos(Math.toRadians(2));
    private static final double[] DETOUR_ANGLES = {15, -15, 30, -30, 45, -45, 90, -90, 135, -135, 180};

    private final double spacing;
    private final double tightness;
    private final double slotTolerance;
    private final double relativeSpeed;
    private final boolean lead;
    private final double homeRange;
    private final ProbeMoveData probeMoveData = new ProbeMoveData();
    private final Vector3d lastLeaderPosition = new Vector3d();
    private final Vector3d measuredLeaderVelocity = new Vector3d();
    private boolean hasLeaderVelocity;
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
    private final Vector3d travelHeading = new Vector3d();
    private final Vector3d steeringHeading = new Vector3d();
    private final Vector3d detourDirection = new Vector3d();
    private final Vector3d progressPosition = new Vector3d();

    @Nullable
    private Ref<EntityStore> trackedLeaderRef;
    private boolean hasLeaderPosition;
    private boolean hasFormationOffset;
    private boolean hasCachedDirection;
    private boolean hasProbedDirection;
    private double looseDriftSeconds;
    private double probeCooldown;
    private double detourSeconds;
    private double progressSeconds;
    private int detourIndex;
    private boolean requestedMovement;
    private boolean needsRecovery;

    BodyMotionTameworkGroundFormation(@Nonnull BuilderBodyMotionTameworkGroundFormation builder,
                                      @Nonnull BuilderSupport support) {
        super(builder);
        spacing = builder.getSpacing(support);
        tightness = builder.getTightness(support);
        double configuredTolerance = builder.getSlotTolerance(support);
        slotTolerance = configuredTolerance > 0 ? configuredTolerance : spacing * 0.3;
        relativeSpeed = builder.getRelativeSpeed(support);
        lead = builder.isLead(support);
        homeRange = builder.getHomeRange(support);
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
        TransformComponent self = componentAccessor.getComponent(ref, TransformComponent.getComponentType());
        if (self == null || !isFinite(self.getPosition())) {
            reset();
            return false;
        }
        if (lead) {
            if (travelHeading.lengthSquared() < EPSILON) {
                BodyMotionTameworkFlightFormation.resolveHeadingFromYaw(self.getRotation().yaw(), travelHeading);
                if (homeRange > 0) {
                    NPCEntity npc = componentAccessor.getComponent(ref, NPCEntity.getComponentType());
                    if (npc != null) biasHeadingTowardHome(self.getPosition(), npc.getLeashPoint(), homeRange, travelHeading);
                }
            }
            leaderHeading.set(travelHeading);
            translation.set(travelHeading).mul(relativeSpeed);
            return steer(ref, self, walk, dt, desiredSteering, componentAccessor);
        }
        EntityGroup group = resolveGroup(ref, componentAccessor);
        Ref<EntityStore> leaderRef = resolveLeader(ref, group);
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
                walk.getMaximumSpeed(), relativeSpeed, tightness, slotTolerance, dt, translation);
        return steer(ref, self, walk, dt, desiredSteering, componentAccessor);
    }

    private boolean steer(Ref<EntityStore> ref, TransformComponent self, MotionControllerWalk walk,
                          double dt, Steering desiredSteering, ComponentAccessor<EntityStore> accessor) {
        if (steeringHeading.lengthSquared() < EPSILON) {
            BodyMotionTameworkFlightFormation.resolveHeadingFromYaw(self.getRotation().yaw(), steeringHeading);
        }
        boolean stalled = false;
        if (requestedMovement) {
            progressSeconds += dt;
            if (progressSeconds >= 1.0) {
                double dx = self.getPosition().x - progressPosition.x;
                double dz = self.getPosition().z - progressPosition.z;
                stalled = dx * dx + dz * dz < 0.15 * 0.15;
                progressSeconds = 0;
                progressPosition.set(self.getPosition());
            }
        } else {
            progressSeconds = 0;
            progressPosition.set(self.getPosition());
        }
        requestedMovement = false;
        needsRecovery |= stalled;
        probeCooldown -= dt;
        detourSeconds = Math.max(0, detourSeconds - dt);
        double speedScale = horizontalLength(translation);
        if (speedScale <= EPSILON) {
            hasProbedDirection = false;
            detourSeconds = 0;
            needsRecovery = false;
            return false;
        }

        if (!hasProbedDirection || probeCooldown <= 0.0) {
            hasCachedDirection = selectWalkableDirection(ref, self.getPosition(), walk, accessor, needsRecovery);
            needsRecovery = false;
            hasProbedDirection = true;
            probeCooldown = PROBE_INTERVAL_SECONDS;
        }
        if (!hasCachedDirection) {
            return false;
        }
        turnToward(steeringHeading, cachedDirection, TURN_RATE * dt, steeringHeading);
        desiredSteering.setYaw(PhysicsMath.headingFromDirection(steeringHeading.x, steeringHeading.z));
        desiredSteering.setRelativeTurnSpeed(1.0);
        // Turn in place for a substantial detour; ordinary small corrections keep walking.
        double alignment = detourDirection.lengthSquared() > EPSILON ? DETOUR_ALIGNMENT : WALK_ALIGNMENT;
        if (steeringHeading.dot(cachedDirection) >= alignment) {
            probeDirection.set(steeringHeading).mul(Math.min(1.0, speedScale));
            desiredSteering.setTranslation(probeDirection);
            requestedMovement = speedScale * walk.getMaximumSpeed() >= 0.25;
        }
        return true;
    }

    private boolean selectWalkableDirection(@Nonnull Ref<EntityStore> ref, @Nonnull Vector3d position,
                                            @Nonnull MotionControllerWalk walk,
                                            @Nonnull ComponentAccessor<EntityStore> accessor,
                                            boolean stalled) {
        // Shallow turns are preventive; an actual stall still needs a decisive escape turn.
        if (stalled) detourIndex = Math.max(detourIndex, 4);
        int probes = 0;
        if (detourSeconds > 0 && !stalled) {
            probes++;
            if (canWalk(ref, position, detourDirection, walk, accessor)) {
                cachedDirection.set(detourDirection);
                return true;
            }
        }
        boolean retryDetour = detourSeconds <= 0 && detourDirection.lengthSquared() > EPSILON;
        detourSeconds = 0;
        if (!stalled) {
            probes++;
            if (canWalk(ref, position, translation, walk, accessor)) {
                detourDirection.zero();
                detourIndex = 0;
                return normalizeHorizontal(translation, cachedDirection);
            }
        }
        if (!stalled && retryDetour && probes < 3) {
            probes++;
            if (canWalk(ref, position, detourDirection, walk, accessor)) {
                cachedDirection.set(detourDirection);
                detourSeconds = 2;
                return true;
            }
        }
        detourDirection.zero();
        // Explore a small fan over successive checks, rather than retrying the same blocked line.
        while (probes++ < 3) {
            double angle = Math.toRadians(DETOUR_ANGLES[detourIndex++ % DETOUR_ANGLES.length]);
            double sin = Math.sin(angle);
            double cos = Math.cos(angle);
            leaderDirection.set(leaderHeading.x * cos - leaderHeading.z * sin, 0,
                    leaderHeading.x * sin + leaderHeading.z * cos);
            if (canWalk(ref, position, leaderDirection, walk, accessor)) {
                normalizeHorizontal(leaderDirection, cachedDirection);
                detourDirection.set(cachedDirection);
                detourSeconds = 2;
                return true;
            }
        }
        cachedDirection.zero();
        return false;
    }

    // Choose once per activation so the boundary never produces mid-journey course corrections.
    static void biasHeadingTowardHome(Vector3d position, Vector3d home, double range, Vector3d heading) {
        if (range <= 0 || !isFinite(home)) return;
        double dx = home.x - position.x;
        double dz = home.z - position.z;
        double distance = Math.hypot(dx, dz);
        if (distance >= range && distance > EPSILON) heading.set(dx / distance, 0, dz / distance);
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
                                             double tolerance, double dt, @Nonnull Vector3d output) {
        if (!Double.isFinite(maximumSpeed) || maximumSpeed <= EPSILON) return output.zero();
        double dx = targetPosition.x - selfPosition.x;
        double dz = targetPosition.z - selfPosition.z;
        double distance = Math.hypot(dx, dz);
        double response = distance > tolerance ? (distance - tolerance) / distance * 1.5 * tightness : 0;
        double correctionCap = maximumSpeed * relativeSpeed;
        if (distance > EPSILON) {
            response = Math.min(response, correctionCap / distance);
            if (dt > EPSILON) response = Math.min(response, 1 / dt);
        }
        double leaderSpeed = Math.min(maximumSpeed, Math.hypot(leaderVelocity.x, leaderVelocity.z));
        if (leaderSpeed > 0.05) {
            double length = Math.hypot(leaderVelocity.x, leaderVelocity.z);
            double fx = leaderVelocity.x / length;
            double fz = leaderVelocity.z / length;
            double forward = Math.max(0, leaderSpeed + (dx * fx + dz * fz) * response);
            double lateral = (-dx * fz + dz * fx) * response;
            double lateralCap = forward * Math.tan(Math.toRadians(20));
            lateral = Math.max(-lateralCap, Math.min(lateralCap, lateral));
            output.set(fx * forward - fz * lateral, 0, fz * forward + fx * lateral);
        } else {
            output.set(dx * response, 0, dz * response);
        }
        double speed = output.length();
        if (speed > maximumSpeed) output.mul(maximumSpeed / speed);
        return output.div(maximumSpeed);
    }

    static Vector3d turnToward(Vector3d current, Vector3d target, double maximumTurn, Vector3d output) {
        double angle = Math.atan2(current.z, current.x);
        double difference = Math.atan2(target.z, target.x) - angle;
        double turn = Math.atan2(Math.sin(difference), Math.cos(difference));
        angle += Math.max(-maximumTurn, Math.min(maximumTurn, turn));
        return output.set(Math.cos(angle), 0, Math.sin(angle));
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
        if (!hasLeaderPosition) {
            BodyMotionTameworkFlightFormation.resolveHeadingFromYaw(leader.getRotation().yaw(), sampledHeading);
            leaderHeading.set(sampledHeading);
        } else if (dt > EPSILON) {
            measuredLeaderVelocity.set(position).sub(lastLeaderPosition).div(dt);
            measuredLeaderVelocity.y = 0;
            if (!hasLeaderVelocity) {
                leaderVelocity.set(measuredLeaderVelocity);
                hasLeaderVelocity = true;
            } else {
                smoothGroundLeaderVelocity(leaderVelocity, measuredLeaderVelocity, dt);
            }
            // Use the same filtered movement for both slot orientation and forward matching.
            if (leaderVelocity.lengthSquared() > 0.05 * 0.05) {
                FlightFormationSteering.smoothHeading(leaderHeading, leaderVelocity, dt, leaderHeading);
            }
        }
        lastLeaderPosition.set(position);
        hasLeaderPosition = true;
    }

    static void smoothGroundLeaderVelocity(Vector3d current, Vector3d measured, double dt) {
        double blend = -Math.expm1(-dt / 1.5);
        current.lerp(measured, blend);
        current.y = 0;
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
        hasLeaderVelocity = false;
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
        detourDirection.zero();
        travelHeading.zero();
        steeringHeading.zero();
        detourSeconds = 0;
        detourIndex = 0;
        progressSeconds = 0;
        requestedMovement = false;
        needsRecovery = false;
    }
}
