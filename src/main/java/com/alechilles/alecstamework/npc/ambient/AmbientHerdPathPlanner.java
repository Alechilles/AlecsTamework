package com.alechilles.alecstamework.npc.ambient;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.movement.constraints.RelaxedConstraint;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import com.hypixel.hytale.server.npc.movement.controllers.ProbeMoveData;
import com.hypixel.hytale.server.npc.navigation.AStarBase;
import com.hypixel.hytale.server.npc.navigation.AStarEvaluator;
import com.hypixel.hytale.server.npc.navigation.AStarNode;
import com.hypixel.hytale.server.npc.navigation.AStarNodePoolProviderSimple;
import com.hypixel.hytale.server.npc.navigation.AStarWithTarget;
import com.hypixel.hytale.server.npc.role.Role;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/** One bounded, leader-only A* route job. A job never owns an ECS reference, store, or role. */
public final class AmbientHerdPathPlanner {
    private static final int EXPANSIONS_PER_STEP = 16;
    private static final int MAX_ROUTE_VERTICES = 64;

    public SearchJob newJob(AmbientHerdPoint start, AmbientHerdPoint target) {
        return new SearchJob(start, target);
    }

    /** World-thread-only final approach probe for one assigned member; no pathfinder is created. */
    public ValidationStatus validateLocalSegment(
            Ref<EntityStore> memberRef,
            Role role,
            Store<EntityStore> store,
            AmbientHerdPoint from,
            AmbientHerdPoint to,
            AmbientHerdWorkBudget budget,
            UUID worldId,
            long nowMillis) {
        if (memberRef == null
                || !memberRef.isValid()
                || role == null
                || store == null
                || from == null
                || to == null
                || budget == null
                || worldId == null) return ValidationStatus.BLOCKED;
        if (!budget.claim(worldId, AmbientHerdWorkBudget.Work.MOVEMENT_PROBE, 1, nowMillis))
            return ValidationStatus.PENDING;
        MotionController controller = role.getActiveMotionController();
        if (controller == null) return ValidationStatus.BLOCKED;
        ProbeMoveData probe = new ProbeMoveData();
        probe.setRelaxedConstraints(EnumSet.noneOf(RelaxedConstraint.class));
        Vector3d start = vector(from);
        Vector3d direction = vector(to).sub(start);
        if (direction.lengthSquared() <= .000001) return ValidationStatus.CLEAR;
        controller.probeMove(memberRef, start, direction, probe, store);
        return probe.probePosition.distanceSquared(vector(to)) <= .25
                ? ValidationStatus.CLEAR
                : ValidationStatus.BLOCKED;
    }

    public StepResult step(
            SearchJob job,
            Ref<EntityStore> leaderRef,
            Role role,
            Store<EntityStore> store,
            AmbientHerdWorkBudget budget,
            UUID worldId,
            long nowMillis) {
        if (job == null || job.closed) return StepResult.miss();
        if (leaderRef == null
                || !leaderRef.isValid()
                || role == null
                || store == null
                || budget == null
                || worldId == null) {
            clear(job);
            return StepResult.miss();
        }
        MotionController controller = role.getActiveMotionController();
        TransformComponent transform =
                store.getComponent(leaderRef, TransformComponent.getComponentType());
        BoundingBox boundingBox = store.getComponent(leaderRef, BoundingBox.getComponentType());
        AStarNodePoolProviderSimple pool =
                store.getResource(AStarNodePoolProviderSimple.getResourceType());
        if (controller == null
                || transform == null
                || transform.getPosition() == null
                || boundingBox == null
                || boundingBox.getBoundingBox() == null
                || pool == null) {
            clear(job);
            return StepResult.miss();
        }
        if (!job.started) {
            if (!budget.claim(
                    worldId, AmbientHerdWorkBudget.Work.ASTAR_INITIALIZATION, 1, nowMillis))
                return StepResult.pending();
            job.evaluator = new GoalEvaluator(job.target, boundingBox.getBoundingBox());
            job.probe = new ProbeMoveData();
            job.probe.setRelaxedConstraints(EnumSet.noneOf(RelaxedConstraint.class));
            AStarBase.Progress progress =
                    job.search.initComputePath(
                            leaderRef,
                            vector(job.start),
                            vector(job.target),
                            job.evaluator,
                            controller,
                            job.probe,
                            pool,
                            store);
            job.started = true;
            return finish(job, progress);
        }
        if (!budget.claim(
                worldId,
                AmbientHerdWorkBudget.Work.ASTAR_EXPANSION,
                EXPANSIONS_PER_STEP,
                nowMillis)) return StepResult.pending();
        return finish(
                job,
                job.search.computePath(
                        leaderRef, controller, job.probe, EXPANSIONS_PER_STEP, store));
    }

    public void clear(SearchJob job) {
        if (job != null && !job.closed) {
            job.search.clearPath();
            job.closed = true;
            job.evaluator = null;
            job.probe = null;
        }
    }

    private StepResult finish(SearchJob job, AStarBase.Progress progress) {
        if (progress == AStarBase.Progress.COMPUTING) return StepResult.pending();
        if (progress != AStarBase.Progress.ACCOMPLISHED) {
            clear(job);
            return StepResult.miss();
        }
        List<AmbientHerdPoint> route = new ArrayList<>();
        for (AStarNode node = job.search.getPath(); node != null; node = node.getNextPathNode()) {
            if (route.size() >= MAX_ROUTE_VERTICES || node.getPosition() == null) {
                clear(job);
                return StepResult.miss();
            }
            Vector3d point = node.getPosition();
            if (!Double.isFinite(point.x)
                    || !Double.isFinite(point.y)
                    || !Double.isFinite(point.z)) {
                clear(job);
                return StepResult.miss();
            }
            route.add(new AmbientHerdPoint(point.x, point.y, point.z));
        }
        clear(job);
        return route.isEmpty() ? StepResult.miss() : StepResult.complete(route);
    }

    private static Vector3d vector(AmbientHerdPoint point) {
        return new Vector3d(point.x(), point.y(), point.z());
    }

    public enum PathStatus {
        PENDING,
        COMPLETE,
        MISS
    }

    public enum ValidationStatus {
        PENDING,
        CLEAR,
        BLOCKED
    }

    public record StepResult(PathStatus status, List<AmbientHerdPoint> route) {
        static StepResult pending() {
            return new StepResult(PathStatus.PENDING, List.of());
        }

        static StepResult miss() {
            return new StepResult(PathStatus.MISS, List.of());
        }

        static StepResult complete(List<AmbientHerdPoint> route) {
            return new StepResult(PathStatus.COMPLETE, List.copyOf(route));
        }
    }

    public static final class SearchJob {
        private final AmbientHerdPoint start, target;
        private final AStarWithTarget search = configuredSearch();
        private boolean started, closed;
        private GoalEvaluator evaluator;
        private ProbeMoveData probe;

        private SearchJob(AmbientHerdPoint start, AmbientHerdPoint target) {
            if (start == null || target == null)
                throw new IllegalArgumentException("start and target are required");
            this.start = start;
            this.target = target;
        }

        private static AStarWithTarget configuredSearch() {
            AStarWithTarget search = new AStarWithTarget();
            search.setCanMoveDiagonal(true);
            search.setOptimizedBuildPath(false);
            search.setMaxPathLength(128);
            search.setOpenNodesLimit(128);
            search.setTotalNodesLimit(512);
            return search;
        }
    }

    private static final class GoalEvaluator implements AStarEvaluator {
        private static final double HEIGHT_TOLERANCE = 1.0, STOP_DISTANCE_SQUARED = 2.25;
        private final Vector3d target;
        private final Box body;

        private GoalEvaluator(AmbientHerdPoint target, Box body) {
            this.target = vector(target);
            this.body = new Box(body);
        }

        @Override
        public boolean isGoalReached(
                Ref<EntityStore> ref,
                AStarBase path,
                AStarNode node,
                MotionController controller,
                ComponentAccessor<EntityStore> accessor) {
            Vector3d position = node.getPosition();
            if (position == null || Math.abs(target.y - position.y) > HEIGHT_TOLERANCE)
                return false;
            return Double.isFinite(controller.waypointDistanceSquared(position, target))
                    && controller.waypointDistanceSquared(position, target) <= STOP_DISTANCE_SQUARED
                    && body.containsPosition(
                            target.x - position.x, target.y - position.y, target.z - position.z);
        }

        @Override
        public float estimateToGoal(
                AStarBase path, Vector3d position, MotionController controller) {
            return (float) Math.sqrt(controller.waypointDistanceSquared(position, target));
        }
    }
}
