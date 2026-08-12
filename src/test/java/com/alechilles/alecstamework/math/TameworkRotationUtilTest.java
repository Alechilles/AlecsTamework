package com.alechilles.alecstamework.math;

import com.hypixel.hytale.math.vector.Rotation3f;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests shared rotation conversion behavior. */
class TameworkRotationUtilTest {

    @Test
    void lookAtReturnsExpectedHorizontalRotation() {
        Rotation3f rotation = TameworkRotationUtil.lookAt(new Vector3d(1.0, 0.0, 0.0));

        assertEquals(0.0F, rotation.pitch(), 0.000001F);
        assertEquals((float) (-Math.PI / 2.0), rotation.yaw(), 0.000001F);
        assertEquals(0.0F, rotation.roll(), 0.000001F);
    }
}
