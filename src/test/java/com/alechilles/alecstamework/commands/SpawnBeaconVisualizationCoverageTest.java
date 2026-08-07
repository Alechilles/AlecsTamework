package com.alechilles.alecstamework.commands;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SpawnBeaconVisualizationCoverageTest {

    @Test
    void sourceRemainsCoveredWhenAnyActiveViewerIncludesIt() {
        Vector3d source = new Vector3d(10.0, 0.0, 0.0);
        List<SpawnBeaconVisualizationCoverage.ViewerRange> viewers = List.of(
                new SpawnBeaconVisualizationCoverage.ViewerRange(
                        new Vector3d(100.0, 0.0, 0.0), 20.0
                ),
                new SpawnBeaconVisualizationCoverage.ViewerRange(
                        new Vector3d(0.0, 0.0, 0.0), 16.0
                )
        );

        assertTrue(SpawnBeaconVisualizationCoverage.isCovered(source, viewers));
        assertFalse(SpawnBeaconVisualizationCoverage.isCovered(source, viewers.subList(0, 1)));
    }
}
