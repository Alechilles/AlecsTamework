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
    void mountedGlideUsesBaseMountedComponentAttachmentInsteadOfLegacyNpcMount() throws IOException {
        String interaction = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/actions/InteractionMountEffects.java"
        ));
        String plugin = Files.readString(Path.of("src/main/java/com/alechilles/alecstamework/Tamework.java"));
        String source = readGlideInputStack();

        assertTrue(interaction.contains("MountedComponent.getComponentType()"));
        assertTrue(interaction.contains("store.putComponent("));
        assertTrue(interaction.contains("new MountedComponent(npcRef, new Rotation3f(anchorX, anchorY, anchorZ), MountController.BlockMount)"));
        assertTrue(interaction.contains("mounted_component_attach"));
        assertFalse(interaction.contains("store.ensureAndGetComponent(npcRef, npcMountType)"));
        assertFalse(interaction.contains("npcMount.setOwnerPlayerRef(playerRefComponent)"));
        assertFalse(interaction.contains("npcMount.setAnchor(anchorX, anchorY, anchorZ)"));
        assertFalse(interaction.contains("new MountNPC(anchorX, anchorY, anchorZ, npcNetworkId.getId())"));
        assertFalse(interaction.contains("playerComponent.setMountEntityId(npcNetworkId.getId())"));
        assertFalse(interaction.contains("MountedGlidePacketHandler"));
        assertFalse(plugin.contains("MountedGlidePacketHandler::new"));
        assertFalse(source.contains("MountedGlidePacketHandler"));
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
        assertFalse(inputCapture.contains("inputIterator.remove()"));
        assertFalse(inputCapture.contains("playerInput.setMountId(0)"));
        assertTrue(inputCapture.contains("queue.clear()"));
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
}
