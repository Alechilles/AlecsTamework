package com.alechilles.alecstamework.commands;

import org.joml.Vector3d;

import java.util.Collection;

/**
 * Evaluates whether a loaded spawn beacon is covered by any active debug viewer.
 */
final class SpawnBeaconVisualizationCoverage {
    private SpawnBeaconVisualizationCoverage() {
    }

    static boolean isCovered(Vector3d sourcePosition, Collection<ViewerRange> viewers) {
        for (ViewerRange viewer : viewers) {
            double radius = viewer.radius();
            if (sourcePosition.distanceSquared(viewer.position()) <= radius * radius) {
                return true;
            }
        }
        return false;
    }

    record ViewerRange(Vector3d position, double radius) {
    }
}
