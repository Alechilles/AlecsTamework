package com.alechilles.alecstamework.npc.progression;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Selects bounded nearby stand targets for NPC need resources using the active motion controller's
 * accessible-position projection.
 */
final class NeedsResourceStandTargetSelector {

    static final int MAX_HORIZONTAL_OFFSET = 2;
    static final double MIN_ADJACENT_DISTANCE = Math.sqrt(2.0) + 0.000001;

    private static final double SOURCE_CENTER_OFFSET = 0.5;
    private static final double STAND_POSITION_Y_OFFSET = 0.05;
    private static final double PROJECT_VERTICAL_RADIUS = 2.0;
    private static final double SCORE_EPSILON = 0.000001;
    private static final int[] HEIGHT_OFFSETS = {0, 1, -1};
    private static final CandidateOffset[] CANDIDATE_OFFSETS = buildCandidateOffsets();

    private final Vector3d scratchCandidate = new Vector3d();

    @Nullable
    CandidateProjector createProjector(@Nullable Role role, @Nullable Store<EntityStore> store) {
        if (role == null || store == null) {
            return null;
        }
        MotionController motionController = role.getActiveMotionController();
        if (motionController == null) {
            return null;
        }
        ComponentAccessor<EntityStore> componentAccessor = store;
        return (candidate, minY, maxY) -> {
            try {
                return motionController.translateToAccessiblePosition(
                    candidate,
                    null,
                    minY,
                    maxY,
                    componentAccessor
                );
            } catch (RuntimeException ignored) {
                return false;
            }
        };
    }

    @Nullable
    Vector3d findNearestProjectedTarget(
        int sourceX,
        int sourceY,
        int sourceZ,
        @Nonnull Vector3d npcPosition,
        double maxDistance,
        boolean includeSourceBlock,
        @Nonnull CandidateProjector projector
    ) {
        double effectiveMaxDistance = Math.min(Math.max(0.0, maxDistance), MAX_HORIZONTAL_OFFSET);
        double maxDistanceSquared = effectiveMaxDistance * effectiveMaxDistance;
        double bestScore = Double.MAX_VALUE;
        double bestX = 0.0;
        double bestY = 0.0;
        double bestZ = 0.0;
        boolean found = false;

        for (CandidateOffset offset : CANDIDATE_OFFSETS) {
            if (!includeSourceBlock && offset.dx == 0 && offset.dz == 0) {
                continue;
            }
            if (offset.horizontalDistanceSquared > maxDistanceSquared + SCORE_EPSILON) {
                continue;
            }

            scratchCandidate.set(
                sourceX + offset.dx + SOURCE_CENTER_OFFSET,
                sourceY + offset.dy + STAND_POSITION_Y_OFFSET,
                sourceZ + offset.dz + SOURCE_CENTER_OFFSET
            );
            double minY = scratchCandidate.y - PROJECT_VERTICAL_RADIUS;
            double maxY = scratchCandidate.y + PROJECT_VERTICAL_RADIUS;
            if (!projector.project(scratchCandidate, minY, maxY)) {
                continue;
            }
            if (!isFinite(scratchCandidate)) {
                continue;
            }

            double score = scratchCandidate.distanceSquared(npcPosition);
            if (!found || score + SCORE_EPSILON < bestScore) {
                bestScore = score;
                bestX = scratchCandidate.x;
                bestY = scratchCandidate.y;
                bestZ = scratchCandidate.z;
                found = true;
            }
        }

        return found ? new Vector3d(bestX, bestY, bestZ) : null;
    }

    static int maxCandidateCount() {
        return CANDIDATE_OFFSETS.length;
    }

    static boolean hasDiagonalAdjacentCandidate() {
        for (CandidateOffset offset : CANDIDATE_OFFSETS) {
            if (Math.abs(offset.dx) == 1 && Math.abs(offset.dz) == 1 && offset.dy == 0) {
                return true;
            }
        }
        return false;
    }

    private static CandidateOffset[] buildCandidateOffsets() {
        List<CandidateOffset> offsets = new ArrayList<>();
        for (int dx = -MAX_HORIZONTAL_OFFSET; dx <= MAX_HORIZONTAL_OFFSET; dx++) {
            for (int dz = -MAX_HORIZONTAL_OFFSET; dz <= MAX_HORIZONTAL_OFFSET; dz++) {
                int horizontalDistanceSquared = dx * dx + dz * dz;
                if (horizontalDistanceSquared > MAX_HORIZONTAL_OFFSET * MAX_HORIZONTAL_OFFSET) {
                    continue;
                }
                for (int dy : HEIGHT_OFFSETS) {
                    offsets.add(new CandidateOffset(dx, dy, dz, horizontalDistanceSquared));
                }
            }
        }
        offsets.sort(Comparator
            .comparingInt((CandidateOffset offset) -> offset.horizontalDistanceSquared)
            .thenComparingInt(offset -> Math.abs(offset.dy))
            .thenComparingInt(offset -> offset.dy)
            .thenComparingInt(offset -> offset.dx)
            .thenComparingInt(offset -> offset.dz));
        return offsets.toArray(new CandidateOffset[0]);
    }

    private static boolean isFinite(Vector3d vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    @FunctionalInterface
    interface CandidateProjector {
        boolean project(@Nonnull Vector3d candidate, double minY, double maxY);
    }

    private static final class CandidateOffset {
        private final int dx;
        private final int dy;
        private final int dz;
        private final int horizontalDistanceSquared;

        private CandidateOffset(int dx, int dy, int dz, int horizontalDistanceSquared) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.horizontalDistanceSquared = horizontalDistanceSquared;
        }
    }
}
