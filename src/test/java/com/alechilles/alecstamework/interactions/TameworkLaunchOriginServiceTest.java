package com.alechilles.alecstamework.interactions;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TameworkLaunchOriginServiceTest {

    @Test
    void applyOffsetKeepsZeroOffsetAtSourcePosition() {
        Vector3d source = new Vector3d(10.0, 20.0, 30.0);

        Vector3d result = TameworkLaunchOriginService.applyOffset(source, 0.0F, 0.0, 0.0, 0.0);

        assertEquals(10.0, result.x, 0.000001);
        assertEquals(20.0, result.y, 0.000001);
        assertEquals(30.0, result.z, 0.000001);
    }

    @Test
    void applyOffsetRotatesHorizontalOffsetBySourceYaw() {
        Vector3d source = new Vector3d(10.0, 20.0, 30.0);

        Vector3d result = TameworkLaunchOriginService.applyOffset(
                source,
                (float) (Math.PI / 2.0),
                2.0,
                -1.5,
                4.0
        );

        assertEquals(14.0, result.x, 0.000001);
        assertEquals(18.5, result.y, 0.000001);
        assertEquals(28.0, result.z, 0.000001);
    }
}
