package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.compat.HytaleSpatialAccess;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
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
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Holds an autonomous flying flock member in a stable slot behind its native flock leader. */
public final class BodyMotionTameworkFlightFormation extends TameworkBodyMotionBase {
    private static final double EPSILON = 1.0E-6;

    private final BuilderBodyMotionTameworkFlightFormation.Formation formation;
    private final BoidFlightSteering boid = new BoidFlightSteering();
    private final Vector3d boidNeighborHeading = new Vector3d();
    private final Vector3d boidDesired = new Vector3d();
    private final Vector3d boidSmoothed = new Vector3d();
    private final java.util.ArrayList<Ref<EntityStore>> boidNeighbors = new java.util.ArrayList<>(12);
    private final double[] boidDistances = new double[12];
    private double boidRefreshRemaining;
    private boolean hasBoidSample;
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
    private final Vector3d previousFormationOffset = new Vector3d();
    private final Vector3d slotVelocity = new Vector3d();
    private final Vector3d translation = new Vector3d();
    private final Vector3d obstacleProbeOrigin = new Vector3d();
    private final FlyingObstacleAvoidance.Probe obstacleProbe = this::probeObstacle;

    @Nullable
    private Ref<EntityStore> trackedLeaderRef;
    @Nullable
    private NativeFormationSlots.GroupKey formationSlotGroup;
    @Nullable
    private Ref<EntityStore> obstacleProbeRef;
    @Nullable
    private MotionControllerFly obstacleProbeController;
    @Nullable
    private ComponentAccessor<EntityStore> obstacleProbeAccessor;
    private boolean hasLeaderPosition;
    private boolean hasFormationOffset;
    private int lastFormationSlot = -1;
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
        if (formation == BuilderBodyMotionTameworkFlightFormation.Formation.BOID) {
            computeBoid(ref, selfTransform.getPosition(), leaderTransform.getPosition(), fly, dt, componentAccessor);
        } else {
            int followerIndex = 0;
            long nowMillis = System.currentTimeMillis();
            UUIDComponent identity = componentAccessor.getComponent(ref, UUIDComponent.getComponentType());
            UUID memberId = identity == null ? null : identity.getUuid();
            NativeFormationSlots.GroupKey slotGroup = NativeFormationSlots.get().resolveGroup(
                    formationSlotGroup, ref, leaderRef, "Flight", formation.name(), spacing, componentAccessor);
            if (slotGroup != null && memberId != null) {
                int assignedSlot = NativeFormationSlots.get().claim(
                        ref, slotGroup, memberId, followerIndex, nowMillis);
                if (assignedSlot != lastFormationSlot) {
                    if (lastFormationSlot >= 0 && hasFormationOffset && isOrganicFormation()) {
                        // Start a traded slot's smooth approach at the bird, not its former distant target.
                        formationOffset.set(selfTransform.getPosition()).sub(leaderTransform.getPosition());
                    } else {
                        hasFormationOffset = false;
                    }
                }
                formationSlotGroup = slotGroup;
                lastFormationSlot = assignedSlot;
                followerIndex = assignedSlot;
            } else {
                followerIndex = resolveFollowerIndex(group, ref, leaderRef);
                formationSlotGroup = null;
                if (lastFormationSlot >= 0) {
                    hasFormationOffset = false;
                }
                lastFormationSlot = -1;
            }
            FlightFormationSteering.resolveTarget(
                    formation, followerIndex, spacing,
                    isOrganicFormation() ? System.nanoTime() * 1.0E-9 : looseDriftSeconds,
                    leaderTransform.getPosition(), leaderHeading, targetPosition);
            if (formationSlotGroup != null && memberId != null) {
                NativeFormationSlots.get().report(ref, formationSlotGroup, memberId,
                        selfTransform.getPosition(), targetPosition, spacing, nowMillis);
            }
            targetPosition.sub(leaderTransform.getPosition());
            slotVelocity.set(leaderVelocity);
            if (!hasFormationOffset) {
                formationOffset.set(targetPosition);
                hasFormationOffset = true;
            } else {
                previousFormationOffset.set(formationOffset);
                FlightFormationSteering.smoothOffset(
                        formationOffset, targetPosition,
                        Math.min(spacing * 0.75, fly.getSteeringSpeedScale() * 0.35), dt, formationOffset);
                FlightFormationSteering.resolveSlotVelocity(
                        previousFormationOffset, formationOffset, leaderVelocity, dt, slotVelocity);
            }
            targetPosition.set(leaderTransform.getPosition()).add(formationOffset);
            FlightFormationSteering.resolveTranslation(
                    selfTransform.getPosition(), targetPosition, slotVelocity,
                    fly.getSteeringSpeedScale(), relativeSpeed, tightness, dt, translation);

        }

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

    private boolean isOrganicFormation() {
        return formation == BuilderBodyMotionTameworkFlightFormation.Formation.CLUSTER
                || formation == BuilderBodyMotionTameworkFlightFormation.Formation.LOOSE;
    }

    /** World-thread samples at 5 Hz; keeps only 12 nearest airborne flock mates.
     * The spatial query is radius limited, but dense query results still cost O(k) to examine.
     * No component or neighbor reference survives the sample. Normal steering runs every tick.
     */
    private void computeBoid(Ref<EntityStore> ref, Vector3d self, Vector3d leader,
                             MotionControllerTameworkFormationFly fly, double dt,
                             ComponentAccessor<EntityStore> accessor) {
        boidRefreshRemaining -= Math.max(0.0, dt);
        if (!hasBoidSample || boidRefreshRemaining <= 0.0) {
            boid.reset();
            FlockMembership membership = accessor.getComponent(ref, FlockMembership.getComponentType());
            SpatialResource<Ref<EntityStore>, EntityStore> spatial =
                    accessor.getResource(EntityModule.get().getEntitySpatialResourceType());
            if (spatial != null && membership != null) {
                List<Ref<EntityStore>> nearby = SpatialResource.getThreadLocalReferenceList();
                HytaleSpatialAccess.collect(spatial.getSpatialStructure(), self, spacing * 4.0, nearby);
                try {
                    for (Ref<EntityStore> candidate : nearby) {
                        if (!candidate.isValid() || candidate.equals(ref)) continue;
                        FlockMembership other = accessor.getComponent(candidate, FlockMembership.getComponentType());
                        if (other == null || !membership.getFlockRef().equals(other.getFlockRef())) continue;
                        NPCEntity npc = accessor.getComponent(candidate, NPCEntity.getComponentType());
                        if (npc == null || npc.getRole() == null || !isAutonomousFlying(npc.getRole())
                                || hasRiderMount(candidate, accessor)) continue;
                        TransformComponent transform = accessor.getComponent(candidate, TransformComponent.getComponentType());
                        if (transform == null) continue;
                        double distance = self.distanceSquared(transform.getPosition());
                        if (!Double.isFinite(distance) || distance > spacing * spacing * 16.0) continue;
                        int at = 0;
                        while (at < boidNeighbors.size() && boidDistances[at] <= distance) at++;
                        if (at >= 12) continue;
                        if (boidNeighbors.size() == 12) boidNeighbors.remove(11);
                        boidNeighbors.add(at, candidate);
                        for (int j = boidNeighbors.size() - 1; j > at; j--) boidDistances[j] = boidDistances[j - 1];
                        boidDistances[at] = distance;
                    }
                    for (Ref<EntityStore> candidate : boidNeighbors) {
                        TransformComponent transform = accessor.getComponent(candidate, TransformComponent.getComponentType());
                        // Engine Velocity does not represent NPC motion; use facing for alignment.
                        resolveHeadingFromYaw(transform.getRotation().yaw(), boidNeighborHeading)
                                .mul(leaderVelocity.length());
                        double overlapDirection = 1.0;
                        if (self.distanceSquared(transform.getPosition()) <= EPSILON * EPSILON) {
                            UUIDComponent selfId = accessor.getComponent(ref, UUIDComponent.getComponentType());
                            UUIDComponent otherId = accessor.getComponent(candidate, UUIDComponent.getComponentType());
                            if (selfId != null && otherId != null) {
                                overlapDirection = selfId.getUuid().compareTo(otherId.getUuid()) < 0 ? -1.0 : 1.0;
                            }
                        }
                        boid.addNeighbor(self, transform.getPosition(), boidNeighborHeading, spacing, overlapDirection);
                    }
                } finally {
                    boidNeighbors.clear();
                    nearby.clear();
                }
            }
            boid.resolve(self, leader, leaderVelocity, spacing, tightness,
                    fly.getSteeringSpeedScale(), relativeSpeed, boidDesired);
            if (!hasBoidSample) {
                boidSmoothed.set(leaderVelocity).div(Math.max(EPSILON, fly.getSteeringSpeedScale()));
                UUIDComponent identity = accessor.getComponent(ref, UUIDComponent.getComponentType());
                int phase = identity == null ? 0 : Math.floorMod(identity.getUuid().hashCode(), 100);
                boidRefreshRemaining = 0.1 + phase * 0.001;
            } else {
                boidRefreshRemaining = 0.2;
            }
            hasBoidSample = true;
        }
        boidSmoothed.lerp(boidDesired, 1.0 - Math.exp(-4.0 * Math.max(0.0, dt)));
        translation.set(boidSmoothed);
        if (translation.lengthSquared() > 1.0) translation.normalize();
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
        hasBoidSample = false;
        boidRefreshRemaining = 0.0;
        boidNeighbors.clear();
        boidDesired.zero();
        boidSmoothed.zero();
        trackedLeaderRef = null;
        formationSlotGroup = null;
        hasLeaderPosition = false;
        hasFormationOffset = false;
        lastFormationSlot = -1;
        formationOffset.zero();
        previousFormationOffset.zero();
        slotVelocity.zero();
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
