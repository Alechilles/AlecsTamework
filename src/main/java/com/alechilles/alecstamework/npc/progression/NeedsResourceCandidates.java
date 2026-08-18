package com.alechilles.alecstamework.npc.progression;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Immutable ranked block-coordinate results for needs-resource searches.
 *
 * <p>The result contains only primitive coordinates and scalar metadata. It can
 * therefore be shared by several NPCs without retaining ECS or world state.
 */
final class NeedsResourceCandidates {
    static final int MAX_CANDIDATES = 16;
    private static final double DISTANCE_EPSILON = 0.000001;

    private NeedsResourceCandidates() {
    }

    /**
     * One ranked block coordinate and the radius used when approaching it.
     */
    record Candidate(int x, int y, int z, double approachRadius) {
    }

    /**
     * Immutable ranked candidates and the source metadata from one search.
     */
    record Snapshot(@Nonnull List<Candidate> candidates,
                    boolean foundSource,
                    boolean sourceInConsumeRange,
                    long ttlMs) {
        Snapshot {
            Objects.requireNonNull(candidates, "candidates");
            int candidateCount = Math.min(MAX_CANDIDATES, candidates.size());
            candidates = List.copyOf(candidates.subList(0, candidateCount));
        }

        /**
         * Selects the first ranked candidate that is in range and accepted by
         * the NPC-specific predicate.
         *
         * <p>The cache stores candidates in rank order. This method only
         * applies the current NPC's range and rejection checks.
         */
        @Nullable
        Candidate select(@Nullable Vector3d origin,
                         double radius,
                         int verticalRadius,
                         @Nonnull Predicate<Candidate> accept) {
            if (!isFinitePosition(origin)
                    || !Double.isFinite(radius)
                    || radius <= 0.0) {
                return null;
            }
            Objects.requireNonNull(accept, "accept");
            double radiusSquared = radius * radius;
            double originBlockY = Math.floor(origin.y);
            int clampedVerticalRadius = Math.max(0, verticalRadius);
            for (Candidate candidate : candidates) {
                double dx = candidate.x() - origin.x;
                double dz = candidate.z() - origin.z;
                if ((dx * dx) + (dz * dz) > radiusSquared + DISTANCE_EPSILON) {
                    continue;
                }
                if (Math.abs(candidate.y() - originBlockY) > clampedVerticalRadius) {
                    continue;
                }
                if (accept.test(candidate)) {
                    return candidate;
                }
            }
            return null;
        }

        boolean hasCandidates() {
            return !candidates.isEmpty();
        }

        boolean hasTarget() {
            return hasCandidates();
        }

        boolean foundConsumableSource() {
            return foundSource;
        }

        boolean foundConsumableSourceInConsumeRange() {
            return sourceInConsumeRange;
        }
    }

    private static boolean isFinitePosition(@Nullable Vector3d position) {
        return position != null
                && Double.isFinite(position.x)
                && Double.isFinite(position.y)
                && Double.isFinite(position.z);
    }
}
