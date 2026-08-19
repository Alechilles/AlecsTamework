package com.alechilles.alecstamework.npc.progression;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Owns scalar continuation state for one bounded reachable-block source scan.
 *
 * <p>The session retains no ECS or world object. It only advances a scalar cursor and the
 * bounded authority selector between passes.</p>
 */
final class ReachableBlockScanSession {
    private final ReachableBlockSourceCache.SearchBounds bounds;
    private final ReachableBlockAuthoritySelector selector;
    private int cursorX;
    private int cursorY;
    private int cursorZ;
    private boolean complete;

    ReachableBlockScanSession(@Nonnull ReachableBlockSourceCache.SearchBounds bounds,
                              int originX,
                              int originY,
                              int originZ,
                              double horizontalRange,
                              int verticalRadius) {
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        this.selector = new ReachableBlockAuthoritySelector(
                bounds,
                originX,
                originY,
                originZ,
                horizontalRange,
                verticalRadius
        );
        this.cursorX = bounds.minX();
        this.cursorY = bounds.minY();
        this.cursorZ = bounds.minZ();
    }

    @Nonnull
    ReachableBlockSourceCache.ScanSlice nextSlice() {
        return new ReachableBlockSourceCache.ScanSlice(
                bounds,
                cursorX,
                cursorY,
                cursorZ,
                ReachableBlockSourceCache.MAX_BLOCK_PROBES_PER_PASS
        );
    }

    void accept(@Nonnull ReachableBlockSourceCache.ScanResult result) {
        Objects.requireNonNull(result, "result");
        if (result.probes() > ReachableBlockSourceCache.MAX_BLOCK_PROBES_PER_PASS
                || result.matches().size() > ReachableBlockSourceCache.MAX_SELECTOR_OFFERS_PER_PASS) {
            throw new IllegalArgumentException("cold scan pass exceeded its work budget");
        }
        for (ReachableBlockSourceCache.SourceCoordinate coordinate : result.matches()) {
            selector.offer(coordinate);
        }
        if (result.probes() <= 0) {
            complete = true;
            return;
        }
        advance(result.probes());
    }

    void markComplete() {
        complete = true;
    }

    boolean complete() {
        return complete;
    }

    @Nonnull
    ReachableBlockSourceCache.Snapshot snapshot() {
        return new ReachableBlockSourceCache.Snapshot(selector.finish());
    }

    private void advance(int probes) {
        int remaining = probes;
        while (remaining-- > 0 && !complete) {
            if (cursorX == bounds.maxX()
                    && cursorY == bounds.maxY()
                    && cursorZ == bounds.maxZ()) {
                complete = true;
                continue;
            }
            if (cursorZ < bounds.maxZ()) {
                cursorZ++;
            } else if (cursorX < bounds.maxX()) {
                cursorX++;
                cursorZ = bounds.minZ();
            } else if (cursorY < bounds.maxY()) {
                cursorY++;
                cursorX = bounds.minX();
                cursorZ = bounds.minZ();
            } else {
                complete = true;
            }
        }
    }
}

/**
 * Keeps the best source coordinate for each of the 64 block authorities in one area cell.
 */
final class ReachableBlockAuthoritySelector {
    private static final double RANGE_EPSILON = 0.000001;
    private final ReachableBlockSourceCache.SearchBounds bounds;
    private final int originX;
    private final int originY;
    private final int originZ;
    private final double horizontalRange;
    private final int verticalRadius;
    private final int[] representativeXs =
            new int[ReachableBlockSourceCache.MAX_SOURCE_CANDIDATES];
    private final int[] representativeYs =
            new int[ReachableBlockSourceCache.MAX_SOURCE_CANDIDATES];
    private final int[] representativeZs =
            new int[ReachableBlockSourceCache.MAX_SOURCE_CANDIDATES];
    private final boolean[] representativePresent =
            new boolean[ReachableBlockSourceCache.MAX_SOURCE_CANDIDATES];
    private final double[] distances =
            new double[ReachableBlockSourceCache.MAX_SOURCE_CANDIDATES];

    ReachableBlockAuthoritySelector(@Nonnull ReachableBlockSourceCache.SearchBounds bounds,
                                    int originX,
                                    int originY,
                                    int originZ,
                                    double horizontalRange,
                                    int verticalRadius) {
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        if (!Double.isFinite(horizontalRange) || horizontalRange <= 0.0) {
            throw new IllegalArgumentException("horizontalRange must be finite and positive");
        }
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.horizontalRange = horizontalRange;
        this.verticalRadius = Math.max(0, verticalRadius);
    }

    void offer(@Nonnull ReachableBlockSourceCache.SourceCoordinate coordinate) {
        Objects.requireNonNull(coordinate, "coordinate");
        if (coordinate.x() < bounds.minX()
                || coordinate.x() > bounds.maxX()
                || coordinate.y() < bounds.minY()
                || coordinate.y() > bounds.maxY()
                || coordinate.z() < bounds.minZ()
                || coordinate.z() > bounds.maxZ()) {
            return;
        }
        for (int xOffset = 0; xOffset < ReachableBlockSourceCache.AREA_CELL_SIZE_BLOCKS; xOffset++) {
            for (int yOffset = 0; yOffset < ReachableBlockSourceCache.AREA_CELL_SIZE_BLOCKS; yOffset++) {
                for (int zOffset = 0; zOffset < ReachableBlockSourceCache.AREA_CELL_SIZE_BLOCKS; zOffset++) {
                    int authorityX = originX + xOffset;
                    int authorityY = originY + yOffset;
                    int authorityZ = originZ + zOffset;
                    if (!ReachableBlockSourceCache.isSourceInRangeForAuthority(
                            coordinate,
                            authorityX,
                            authorityY,
                            authorityZ,
                            horizontalRange,
                            verticalRadius
                    )) {
                        continue;
                    }
                    int index = authorityIndex(xOffset, yOffset, zOffset);
                    double distance = distanceSquared(
                            coordinate,
                            authorityX,
                            authorityY,
                            authorityZ
                    );
                    if (!representativePresent[index]
                            || isBetter(
                            coordinate,
                            distance,
                            representativeXs[index],
                            representativeYs[index],
                            representativeZs[index],
                            distances[index]
                    )) {
                        representativeXs[index] = coordinate.x();
                        representativeYs[index] = coordinate.y();
                        representativeZs[index] = coordinate.z();
                        representativePresent[index] = true;
                        distances[index] = distance;
                    }
                }
            }
        }
    }

    @Nonnull
    List<ReachableBlockSourceCache.SourceCoordinate> finish() {
        LinkedHashSet<ReachableBlockSourceCache.SourceCoordinate> selected =
                new LinkedHashSet<>(ReachableBlockSourceCache.MAX_SOURCE_CANDIDATES);
        for (int index = 0; index < ReachableBlockSourceCache.MAX_SOURCE_CANDIDATES; index++) {
            if (representativePresent[index]) {
                selected.add(new ReachableBlockSourceCache.SourceCoordinate(
                        representativeXs[index],
                        representativeYs[index],
                        representativeZs[index]
                ));
            }
        }
        return List.copyOf(selected);
    }

    private static int authorityIndex(int xOffset, int yOffset, int zOffset) {
        return (xOffset * ReachableBlockSourceCache.AREA_CELL_SIZE_BLOCKS
                * ReachableBlockSourceCache.AREA_CELL_SIZE_BLOCKS)
                + (yOffset * ReachableBlockSourceCache.AREA_CELL_SIZE_BLOCKS)
                + zOffset;
    }

    private static double distanceSquared(
            @Nonnull ReachableBlockSourceCache.SourceCoordinate source,
            int authorityX,
            int authorityY,
            int authorityZ) {
        double dx = source.x() - authorityX;
        double dy = source.y() - authorityY;
        double dz = source.z() - authorityZ;
        return (dx * dx) + (dy * dy) + (dz * dz);
    }

    private static boolean isBetter(
            @Nonnull ReachableBlockSourceCache.SourceCoordinate candidate,
            double candidateDistance,
            int currentX,
            int currentY,
            int currentZ,
            double currentDistance) {
        if (candidateDistance < currentDistance - RANGE_EPSILON) {
            return true;
        }
        if (Math.abs(candidateDistance - currentDistance) > RANGE_EPSILON) {
            return false;
        }
        if (candidate.x() != currentX) {
            return candidate.x() < currentX;
        }
        if (candidate.y() != currentY) {
            return candidate.y() < currentY;
        }
        return candidate.z() < currentZ;
    }
}
