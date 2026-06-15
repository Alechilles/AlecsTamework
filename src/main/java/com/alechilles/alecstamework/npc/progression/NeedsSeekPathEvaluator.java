package com.alechilles.alecstamework.npc.progression;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.movement.constraints.RelaxedConstraint;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import com.hypixel.hytale.server.npc.movement.controllers.ProbeMoveData;
import com.hypixel.hytale.server.npc.navigation.AStarBase;
import com.hypixel.hytale.server.npc.navigation.AStarEvaluator;
import com.hypixel.hytale.server.npc.navigation.AStarNode;
import java.util.EnumSet;
import javax.annotation.Nonnull;
import org.joml.Vector3d;

/**
 * Evaluates bounded A* preflight for needs seek targets. The default mode verifies that a path
 * can get close enough to the selected stand target, leaving exact final approach recovery to the
 * runtime seek/stall flow where temporary crowding is handled better.
 */
final class NeedsSeekPathEvaluator implements AStarEvaluator {
    private static final double HEIGHT_RANGE_MIN = -1.0;
    private static final double HEIGHT_RANGE_MAX = 1.0;
    private static final double EPSILON = 0.000001;

    private final Vector3d target;
    private final Box selfBoundingBox;
    private final double stopDistanceSquared;
    private final boolean requireDirectFinalReach;
    private final ProbeMoveData reachProbe = new ProbeMoveData();
    private final Vector3d scratchDirection = new Vector3d();

    NeedsSeekPathEvaluator(@Nonnull Vector3d target,
                           @Nonnull Box selfBoundingBox,
                           double stopDistance) {
        this(target, selfBoundingBox, stopDistance, false);
    }

    NeedsSeekPathEvaluator(@Nonnull Vector3d target,
                           @Nonnull Box selfBoundingBox,
                           double stopDistance,
                           boolean requireDirectFinalReach) {
        this.target = new Vector3d(target);
        this.selfBoundingBox = new Box(selfBoundingBox);
        double effectiveStopDistance = Double.isFinite(stopDistance) && stopDistance > 0.0
                ? stopDistance
                : 2.0;
        this.stopDistanceSquared = effectiveStopDistance * effectiveStopDistance;
        this.requireDirectFinalReach = requireDirectFinalReach;
        this.reachProbe.setRelaxedConstraints(EnumSet.noneOf(RelaxedConstraint.class));
    }

    @Override
    public boolean isGoalReached(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull AStarBase aStarBase,
                                 @Nonnull AStarNode node,
                                 @Nonnull MotionController motionController,
                                 @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        Vector3d position = node.getPosition();
        if (position == null || !isFinite(position)) {
            return false;
        }
        if (!isWithinPreflightGoal(
                target.y - position.y,
                motionController.waypointDistanceSquared(position, target),
                stopDistanceSquared
        )) {
            return false;
        }
        if (!requireDirectFinalReach) {
            return true;
        }
        return canReachTarget(ref, position, motionController, componentAccessor);
    }

    @Override
    public float estimateToGoal(@Nonnull AStarBase aStarBase,
                                @Nonnull Vector3d fromPosition,
                                @Nonnull MotionController motionController) {
        return (float) Math.sqrt(motionController.waypointDistanceSquared(fromPosition, target));
    }

    private boolean canReachTarget(@Nonnull Ref<EntityStore> ref,
                                   @Nonnull Vector3d position,
                                   @Nonnull MotionController motionController,
                                   @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        if (containsTarget(position)) {
            return true;
        }
        scratchDirection.set(target).sub(position);
        if (scratchDirection.lengthSquared() <= EPSILON) {
            return false;
        }
        motionController.probeMove(ref, position, scratchDirection, reachProbe, componentAccessor);
        return containsTarget(reachProbe.probePosition);
    }

    private boolean containsTarget(@Nonnull Vector3d entityPosition) {
        return selfBoundingBox.containsPosition(
                target.x - entityPosition.x,
                target.y - entityPosition.y,
                target.z - entityPosition.z
        );
    }

    static boolean isWithinPreflightGoal(double heightDelta,
                                         double waypointDistanceSquared,
                                         double stopDistanceSquared) {
        return Double.isFinite(heightDelta)
                && Double.isFinite(waypointDistanceSquared)
                && Double.isFinite(stopDistanceSquared)
                && heightDelta >= HEIGHT_RANGE_MIN
                && heightDelta <= HEIGHT_RANGE_MAX
                && waypointDistanceSquared <= stopDistanceSquared + EPSILON;
    }

    private static boolean isFinite(@Nonnull Vector3d vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}
