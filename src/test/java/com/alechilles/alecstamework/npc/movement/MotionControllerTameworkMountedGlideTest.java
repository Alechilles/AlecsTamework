package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotionControllerTameworkMountedGlideTest {

    @Test
    void mountedClientSpeedUsesActiveGlideSpeed() {
        TameworkMountedGlideComponent glide = new TameworkMountedGlideComponent();
        glide.setFlightActive(true);
        glide.setGlideSpeed(18.0);

        assertEquals(18.0, MountedGlideControllerSupport.resolveMountedClientSpeed(glide, 10.0), 0.0001);
    }

    @Test
    void mountedClientSpeedFallsBackWhenFlightInactive() {
        TameworkMountedGlideComponent glide = new TameworkMountedGlideComponent();
        glide.setFlightActive(false);
        glide.setGlideSpeed(18.0);

        assertEquals(10.0, MountedGlideControllerSupport.resolveMountedClientSpeed(glide, 10.0), 0.0001);
    }

    @Test
    void flyControllerOnlyRunsForActiveFlightSession() {
        assertFalse(MountedGlideControllerSupport.shouldRunFlyController(null));

        TameworkMountedGlideComponent glide = new TameworkMountedGlideComponent();
        glide.setFlightActive(false);

        assertFalse(MountedGlideControllerSupport.shouldRunFlyController(glide));

        glide.setFlightActive(true);

        assertTrue(MountedGlideControllerSupport.shouldRunFlyController(glide));
    }

    @Test
    void mountedClientSpeedFallsBackWhenStateIsUnset() {
        TameworkMountedGlideComponent glide = new TameworkMountedGlideComponent();
        glide.setFlightActive(true);

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

    @Test
    void inactiveControllerPathDoesNotDelegateToFlyMovement() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/movement/MotionControllerTameworkMountedGlide.java"
        ));

        assertTrue(source.contains("if (!shouldRunFlyController(glide))"));
        assertTrue(source.contains("translation.set(0.0)"));
        assertTrue(source.contains("return dt"));
        assertTrue(source.indexOf("if (!shouldRunFlyController(glide))")
                < source.indexOf("double remaining = super.computeMove"));
        assertTrue(source.indexOf("if (!shouldRunFlyController(glide))",
                source.indexOf("protected double executeMove"))
                < source.indexOf("double remaining = super.executeMove"));
        assertTrue(source.indexOf("if (!shouldRunFlyController(glide))",
                source.indexOf("public void updateMovementState"))
                < source.indexOf("super.updateMovementState"));
    }
}
