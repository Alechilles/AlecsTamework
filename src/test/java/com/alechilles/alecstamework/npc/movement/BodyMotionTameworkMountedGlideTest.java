package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BodyMotionTameworkMountedGlideTest {

    @Test
    void pitchSnapshotConvertsDegreesToRadians() {
        TameworkMountedGlideComponent glide = new TameworkMountedGlideComponent();
        glide.captureLookRotation(90.0f, 30.0f, 0.0f, 10L);

        assertEquals(Math.toRadians(30.0), BodyMotionTameworkMountedGlide.resolvePitchRadians(glide), 0.0001);
        assertEquals(Math.toRadians(90.0), BodyMotionTameworkMountedGlide.resolveYawRadians(glide, 0.0f), 0.0001);
    }

    @Test
    void yawAndForwardSpeedCreateHorizontalTranslation() {
        MountedGlidePhysics.Output output = new MountedGlidePhysics.Output(
                12.0,
                -1.0,
                false,
                false,
                false,
                false
        );

        Vector3d translation = BodyMotionTameworkMountedGlide.resolveTranslation(
                Math.toRadians(90.0),
                1.0,
                0.0,
                output
        );

        assertTrue(translation.x < -0.9);
        assertTrue(translation.y < 0.0);
        assertEquals(0.0, translation.z, 0.0001);
    }

    @Test
    void groundedPassiveSinkIsClampedBeforeFlyCollision() {
        Vector3d translation = new Vector3d(0.5, -0.05, 0.0);

        BodyMotionTameworkMountedGlide.clampGroundedSink(translation, true);

        assertEquals(0.5, translation.x, 0.0001);
        assertEquals(0.0, translation.y, 0.0001);
    }

    @Test
    void airbornePassiveSinkIsPreserved() {
        Vector3d translation = new Vector3d(0.5, -0.05, 0.0);

        BodyMotionTameworkMountedGlide.clampGroundedSink(translation, false);

        assertEquals(-0.05, translation.y, 0.0001);
    }

    @Test
    void groundedLiftIsPreservedForFlaps() {
        Vector3d translation = new Vector3d(0.5, 0.25, 0.0);

        BodyMotionTameworkMountedGlide.clampGroundedSink(translation, true);

        assertEquals(0.25, translation.y, 0.0001);
    }

    @Test
    void heldJumpRemainsCooldownGatedFlapRequest() {
        TameworkMountedGlideComponent glide = new TameworkMountedGlideComponent();
        glide.captureControls(true, false, false, 10L);

        assertTrue(glide.shouldRequestFlap());

        glide.setFlapCooldownRemainingSeconds(0.4);

        assertTrue(glide.isJumpHeld());
        assertEquals(0.4, glide.getFlapCooldownRemainingSeconds(), 0.0001);
    }

    @Test
    void heldJumpRequestsGroundedFlyControllerTakeoff() {
        TameworkMountedGlideComponent glide = new TameworkMountedGlideComponent();

        assertFalse(BodyMotionTameworkMountedGlide.shouldTakeOffFromGround(glide, true));

        glide.captureControls(true, false, false, 10L);

        assertTrue(BodyMotionTameworkMountedGlide.shouldTakeOffFromGround(glide, true));
        assertFalse(BodyMotionTameworkMountedGlide.shouldTakeOffFromGround(glide, false));
    }

    @Test
    void mountedGlideBodyMotionOwnsActiveController() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/movement/BodyMotionTameworkMountedGlide.java"
        ));

        assertTrue(source.contains("ensureGlideController(ref, role, glide, componentAccessor)"));
        assertTrue(source.contains("role.setActiveMotionController(ref, npc, controller, componentAccessor)"));
        assertTrue(source.contains("maybeTakeOff(ref, glide, config, active, componentAccessor)"));
    }
}
