package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MotionControllerTameworkMountedGlideTest {

    @Test
    void mountedClientSpeedUsesActiveGlideSpeed() {
        TameworkMountedGlideComponent glide = new TameworkMountedGlideComponent();
        glide.setGlideSpeed(18.0);

        assertEquals(18.0, MountedGlideControllerSupport.resolveMountedClientSpeed(glide, 10.0), 0.0001);
    }

    @Test
    void mountedClientSpeedFallsBackWhenStateIsUnset() {
        TameworkMountedGlideComponent glide = new TameworkMountedGlideComponent();

        assertEquals(10.0, MountedGlideControllerSupport.resolveMountedClientSpeed(glide, 10.0), 0.0001);
    }

    @Test
    void mountedSpeedLimitUsesActiveGlideSpeed() {
        assertEquals(32.0, MountedGlideControllerSupport.resolveMountedSpeedLimit(true, 32.0, 10.0), 0.0001);
    }

    @Test
    void mountedSpeedLimitFallsBackWhenNotRidden() {
        assertEquals(10.0, MountedGlideControllerSupport.resolveMountedSpeedLimit(false, 32.0, 10.0), 0.0001);
    }

    @Test
    void mountedSpeedLimitFallsBackWhenSpeedUnset() {
        assertEquals(10.0, MountedGlideControllerSupport.resolveMountedSpeedLimit(true, 0.0, 10.0), 0.0001);
    }
}
