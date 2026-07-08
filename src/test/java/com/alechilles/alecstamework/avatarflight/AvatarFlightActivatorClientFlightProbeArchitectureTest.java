package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AvatarFlightActivatorClientFlightProbeArchitectureTest {
    private static final Path SOURCE = Path.of(
            "src",
            "main",
            "java",
            "com",
            "alechilles",
            "alecstamework",
            "avatarflight",
            "AvatarFlightActivator.java"
    );

    @Test
    void enablingAvatarFlightDoesNotEnableClientFlightProbe() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String enableBody = methodBody(source, "public Result enable", "public Result disable");

        assertFalse(enableBody.contains("AvatarFlightClientFlightProbe.enable("),
                "native client flight overrides avatar-flight motion, so it must stay a standalone debug probe");
        assertFalse(enableBody.contains("AvatarFlightActivationCapability"),
                "normal avatar flight must not borrow native canFly for jump/double-jump activation");
    }

    @Test
    void disablingAvatarFlightDoesNotOwnStandaloneClientFlightProbe() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String disableBody = methodBody(source, "public Result disable", "public void onPlayerDisconnect");

        assertFalse(disableBody.contains("AvatarFlightClientFlightProbe.disable("),
                "avatar flight disable should not silently disable a manually enabled flightprobe session");
        assertFalse(disableBody.contains("AvatarFlightActivationCapability"),
                "normal avatar flight should not own or restore native canFly activation state");
    }

    @Test
    void disablingAvatarFlightResetsVisualPoseAndSavedFlyingState() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String disableBody = methodBody(source, "public Result disable", "public void onPlayerDisconnect");

        assertTrue(disableBody.contains("restoreClientFlyingState(store, ref)"),
                "disable must send the owner client back to non-flying animation state");
        assertTrue(disableBody.contains("resetVisualPose(store, ref)"),
                "disable must clear any pitch/roll left on the transformed player pose");
    }

    @Test
    void disablingAvatarFlightClearsForcedAnimationSlots() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String disableBody = methodBody(source, "public Result disable", "public void onPlayerDisconnect");

        assertTrue(disableBody.contains("clearForcedAnimations(store, ref)"),
                "disabling avatar flight must clear any forced transformed-player and overlay animations");
        assertTrue(source.contains("AnimationUtils.stopAnimation(ref, AnimationSlot.Movement, true, store)"),
                "cleanup must send the stop packet to the player itself as well as other viewers");
        assertTrue(source.contains("AnimationUtils.stopAnimation(ref, AnimationSlot.Action, true, store)"),
                "cleanup must clear item/combat overlay animations from the transformed player");
        assertTrue(source.contains("AnimationUtils.stopAnimation(ref, AnimationSlot.Status, true, store)"),
                "cleanup must clear status overlay animations from the transformed player");
        assertTrue(source.contains("AnimationUtils.stopAnimation(ref, AnimationSlot.Emote, true, store)"),
                "cleanup must clear player emotes from the transformed player");
    }

    @Test
    void disconnectCleanupDoesNotTouchThreadBoundPlayerComponents() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String disconnectBody = source.substring(source.indexOf("public void onPlayerDisconnect"));

        assertFalse(disconnectBody.contains("AvatarFlightClientFlightProbe.disable("),
                "disconnect events can run off the world thread and must not resolve movement components");
        assertTrue(disconnectBody.contains("AvatarFlightClientFlightProbe.clear(playerUuid)"),
                "disconnect cleanup should still clear probe bookkeeping");
        assertFalse(disconnectBody.contains("AvatarFlightActivationCapability"),
                "disconnect cleanup should not manage removed double-jump activation bookkeeping");
    }

    private static String methodBody(String source, String startNeedle, String endNeedle) {
        int start = source.indexOf(startNeedle);
        int end = source.indexOf(endNeedle, start + startNeedle.length());
        assertTrue(start >= 0, "missing method start: " + startNeedle);
        assertTrue(end > start, "missing method end marker: " + endNeedle);
        return source.substring(start, end);
    }
}
