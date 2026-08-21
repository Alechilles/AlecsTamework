package com.alechilles.alecstamework.items;

import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression coverage for active-highlight helper placement. */
class CommandActiveNpcHighlightProxyServiceTest {

    /** Protects the mount-crash fix that replaced client-side mounting with world positioning. */
    @Test
    void worldPositionIncludesNpcHeadOffset() {
        Vector3d result = CommandActiveNpcHighlightProxyService.worldPosition(
                new Vector3d(10.0, 20.0, 30.0),
                new Vector3f(0.25f, 2.5f, -0.5f)
        );

        assertEquals(new Vector3d(10.25, 22.5, 29.5), result);
    }
}
