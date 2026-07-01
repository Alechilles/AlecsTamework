package com.alechilles.alecstamework.npc.systems;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MountedGlideInputCaptureArchitectureTest {

    @Test
    void mountedGlideInputStackDoesNotDependOnLegacyRideComponents() throws IOException {
        String source = readGlideInputStack();

        assertFalse(source.contains("TameworkRideMountComponent"));
        assertFalse(source.contains("TameworkRideRiderComponent"));
    }

    @Test
    void mountedGlideInputStackAvoidsUnsafePlayerComponentAccess() throws IOException {
        String source = readGlideInputStack();

        assertFalse(source.contains("PlayerRef.getComponent("));
        assertFalse(source.contains("getComponent(Player.getComponentType())"));
    }

    @Test
    void mountedGlideInputStackDoesNotConsumeDropOrMouseButtons() throws IOException {
        String source = readGlideInputStack();

        assertFalse(source.contains("DropItem"));
        assertFalse(source.contains("DropItemStack"));
        assertFalse(source.contains("DropItemEvent"));
        assertFalse(source.contains("MouseInteraction"));
    }

    @Test
    void mountedGlideUsesNativeNpcMountComponentInsteadOfMountedComponent() throws IOException {
        String interaction = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/actions/InteractionMountEffects.java"
        ));
        String method = extractMethodSource(
                interaction,
                "private boolean applyTameworkMountedGlideMount",
                "private String resolveGlideMovementConfigId"
        );

        assertTrue(method.contains("NPCMountComponent.getComponentType()"));
        assertTrue(method.contains("createdMount.setOwnerPlayerRef(playerRefComponent)"));
        assertTrue(method.contains("createdMount.setAnchor(anchorX, anchorY, anchorZ)"));
        assertTrue(method.contains("RoleChangeSystem.requestRoleChange"));
        assertFalse(method.contains("new MountedComponent("));
        assertFalse(method.contains("MountController.Minecart"));
    }

    @Test
    void mountedGlideCapturesNativeMountedInputBeforeVanillaAppliesMovement() throws IOException {
        String inputCapture = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideInputCaptureSystem.java"
        ));

        assertTrue(inputCapture.contains("PlayerInput"));
        assertTrue(inputCapture.contains("MountSystems.HandleMountInput.class"));
        assertTrue(inputCapture.contains("PlayerSystems.ProcessPlayerInput.class"));
        assertTrue(inputCapture.contains("MovementStatesComponent"));
        assertTrue(inputCapture.contains("HeadRotation"));
        assertTrue(inputCapture.contains("Order.BEFORE, RoleSystems.BehaviourTickSystem.class"));
        assertTrue(inputCapture.contains("Query.and(playerInputComponentType, riderComponentType)"));
        assertTrue(inputCapture.contains("PlayerInput.WishMovement"));
        assertTrue(inputCapture.contains("mount.captureMovementIntent(wishZ * scale, wishX * scale, now)"));
        assertTrue(inputCapture.contains("states.jumping || states.swimJumping"));
        assertTrue(inputCapture.contains("states.sprinting || states.running"));
        assertTrue(inputCapture.contains("states.crouching || states.forcedCrouching"));
        assertTrue(inputCapture.contains("Math.toDegrees"));
        assertFalse(inputCapture.contains("playerInput.setMountId(0)"));
        assertFalse(inputCapture.contains("queue.clear()"));
    }

    @Test
    void mountedGlideIsolatesNativeMountMovementBeforeNpcBehaviour() throws IOException {
        String isolation = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideNativeInputIsolationSystem.java"
        ));
        String state = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideStateSystem.java"
        ));
        String controller = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/movement/MotionControllerTameworkMountedGlide.java"
        ));
        String capture = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideAuthoritativePoseSystem.java"
        ));
        String component = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/components/TameworkMountedGlideComponent.java"
        ));
        String interaction = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/actions/InteractionMountEffects.java"
        ));
        String plugin = Files.readString(Path.of("src/main/java/com/alechilles/alecstamework/Tamework.java"));

        assertTrue(isolation.contains("Order.AFTER, MountSystems.HandleMountInput.class"));
        assertTrue(isolation.contains("Order.BEFORE, RoleSystems.PreBehaviourSupportTickSystem.class"));
        assertTrue(isolation.contains("transform.getPosition().x = mount.getAuthoritativeX()"));
        assertTrue(state.contains("Order.AFTER, AvoidanceSystem.class"));
        assertTrue(state.contains("Order.AFTER, RoleSystems.BehaviourTickSystem.class"));
        assertTrue(state.contains("Order.BEFORE, SteeringSystem.class"));
        assertTrue(state.contains("support.setState(mountRef, state"));
        assertTrue(state.contains("role.setActiveMotionController(mountRef, npc, controller, commandBuffer)"));
        assertTrue(state.contains("BodyMotionTameworkMountedGlide.applyGlideSteering"));
        assertTrue(state.contains("TameworkGlide debug: stateSystem"));
        assertTrue(controller.contains("public boolean canSteer"));
        assertTrue(controller.contains("glide == null"));
        assertTrue(controller.contains("!isForcePushed()"));
        assertTrue(controller.contains("effectHorizontalSpeedMultiplier != 0.0"));
        assertFalse(controller.contains("moveProbe.isInAir()"));
        assertTrue(controller.contains("TameworkGlide debug: controller"));
        assertTrue(capture.contains("Order.AFTER, RoleSystems.PostBehaviourSupportTickSystem.class"));
        assertTrue(capture.contains("Order.BEFORE, TransformSystems.EntityTrackerUpdate.class"));
        assertTrue(capture.contains("mount.captureAuthoritativePose"));
        assertTrue(component.contains("\"HasAuthoritativePose\""));
        assertTrue(component.contains("\"AuthoritativeX\""));
        assertTrue(interaction.contains("glideMount.captureAuthoritativePose"));
        assertTrue(plugin.contains("new MountedGlideNativeInputIsolationSystem"));
        assertTrue(plugin.contains("new MountedGlideStateSystem"));
        assertTrue(plugin.contains("new MountedGlideAuthoritativePoseSystem"));
    }

    @Test
    void mountedGlideCleanupTracksNativeDismountState() throws IOException {
        String cleanup = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideCleanupSystem.java"
        ));

        assertTrue(cleanup.contains("bufferStore.tryRemoveComponent(riderRef, mountedComponentType)"));
        assertTrue(cleanup.contains("bufferStore.ensureAndGetComponent(mountRef, Interactable.getComponentType())"));
        assertTrue(cleanup.contains("riderMountedToMount"));
        assertFalse(cleanup.contains("MountPlugin.checkDismountNpc"));
        assertFalse(cleanup.contains("npcMountComponentType"));
    }

    @Test
    void mountedGlideDismountPacketRemovesNativeAttachment() throws IOException {
        String handler = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/network/MountedRidePacketHandler.java"
        ));

        assertTrue(handler.contains("getMountedGlideRiderComponentType()"));
        assertTrue(handler.contains("getMountedGlideComponentType()"));
        assertTrue(handler.contains("handleMountedGlideDismount"));
        assertTrue(handler.contains("TameworkGlide debug: dismountPacket"));
        assertTrue(handler.contains("store.tryRemoveComponent(riderRef, MountedComponent.getComponentType())"));
    }

    private static String readGlideInputStack() throws IOException {
        return Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideInputCaptureSystem.java"
        )) + Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideNativeInputIsolationSystem.java"
        )) + Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideStateSystem.java"
        )) + Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideAuthoritativePoseSystem.java"
        )) + Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideCleanupSystem.java"
        ));
    }

    private static String extractMethodSource(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        assertTrue(start >= 0, "Expected source to contain start marker: " + startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(end > start, "Expected source to contain end marker after start marker: " + endMarker);
        return source.substring(start, end);
    }
}
