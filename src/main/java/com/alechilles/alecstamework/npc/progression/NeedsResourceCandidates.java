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
public final class NeedsResourceCandidates {
    public static final int MAX_CANDIDATES = 16;
    private static final double DISTANCE_EPSILON = 0.000001;

    private NeedsResourceCandidates() {
    }

    /**
     * One ranked block coordinate and the radius used when approaching it.
     */
    public record Candidate(int x, int y, int z, double approachRadius) {
    }

    /**
     * Immutable ranked candidates and the source metadata from one search.
     */
    public record Snapshot(@Nonnull List<Candidate> candidates,
                           boolean foundSource,
                           boolean sourceInConsumeRange,
                           long ttlMs) {
        public Snapshot {
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
        public Candidate select(@Nullable Vector3d origin,
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
                double dx = (candidate.x() + 0.5) - origin.x;
                double dz = (candidate.z() + 0.5) - origin.z;
                if ((dx * dx) + (dz * dz) > radiusSquared + DISTANCE_EPSILON) {
                    continue;
                }
                if (Math.abs(Math.floor(candidate.y() + 0.5) - originBlockY) > clampedVerticalRadius) {
                    continue;
                }
                if (accept.test(candidate)) {
                    return candidate;
                }
            }
            return null;
        }

        public boolean hasCandidates() {
            return !candidates.isEmpty();
        }

        public boolean hasTarget() {
            return hasCandidates();
        }

        public boolean foundConsumableSource() {
            return foundSource;
        }

        public boolean foundConsumableSourceInConsumeRange() {
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
