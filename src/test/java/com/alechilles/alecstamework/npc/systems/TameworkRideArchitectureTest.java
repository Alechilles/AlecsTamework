package com.alechilles.alecstamework.npc.systems;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkRideArchitectureTest {
    private static final Path MAIN_JAVA = Paths.get("src", "main", "java");

    @Test
    void mountInteractionBranchesToTameworkRideWithoutLegacyNpcMount() throws IOException {
        String content = readMain(
                "com",
                "alechilles",
                "alecstamework",
                "npc",
                "actions",
                "InteractionMountEffects.java"
        );
        String tameworkRideMount = methodBody(content, "private boolean applyTameworkRideMount", "private void maybeLogTameworkRideMountDebug");

        assertTrue(content.contains("MOUNT_MODE_TAMEWORK_RIDE"));
        assertTrue(content.contains("applyTameworkRideMount"));
        assertFalse(tameworkRideMount.contains("new MountedComponent("));
        assertFalse(tameworkRideMount.contains("MountController.Minecart"));
        assertFalse(tameworkRideMount.contains("MountController.BlockMount"));
        assertTrue(tameworkRideMount.contains("TameworkRideMountComponent"));
        assertTrue(tameworkRideMount.contains("TameworkRideRiderComponent"));
        assertFalse(tameworkRideMount.contains("NPCMountComponent"));
        assertFalse(tameworkRideMount.contains("EMPTY_ROLE_ID"));
    }

    @Test
    void rideComponentsSystemsAndBuildersAreRegistered() throws IOException {
        String plugin = readMain("com", "alechilles", "alecstamework", "Tamework.java");
        String registrar = readMain(
                "com",
                "alechilles",
                "alecstamework",
                "npc",
                "TameworkNpcBuilderRegistrar.java"
        );

        assertTrue(plugin.contains("\"TameworkRideMount\""));
        assertTrue(plugin.contains("\"TameworkRideRider\""));
        assertTrue(plugin.contains("new MountedRideInputCaptureSystem("));
        assertTrue(plugin.contains("new MountedRideCleanupSystem("));
        assertTrue(plugin.contains("new MountedRideRiderFollowSystem("));
        assertTrue(plugin.contains("MountedRidePacketHandler::new"));
        assertFalse(plugin.contains("MountedRideMountMovementPacketFilter.register()"));
        assertFalse(plugin.contains("PacketAdapters.deregisterInbound(rideMountMovementPacketFilter)"));
        assertTrue(registrar.contains("BuilderBodyMotionTameworkRide.BUILDER_ID"));
        assertTrue(registrar.contains("BuilderMotionControllerTameworkRideFly.BUILDER_ID"));
    }

    @Test
    void rideInputCaptureRunsBeforeVanillaMountInputAndConsumesQueue() throws IOException {
        String content = readMain(
                "com",
                "alechilles",
                "alecstamework",
                "npc",
                "systems",
                "MountedRideInputCaptureSystem.java"
        );

        assertTrue(content.contains("Order.BEFORE, MountSystems.HandleMountInput.class"));
        assertTrue(content.contains("Order.BEFORE, PlayerSystems.ProcessPlayerInput.class"));
        assertTrue(content.contains("PlayerInput.WishMovement"));
        assertTrue(content.contains("PlayerInput.SetRiderMovementStates"));
        assertTrue(content.contains("applyRiderLocalInput"));
        assertTrue(content.contains("captureCurrentRiderRotation"));
        assertTrue(content.contains("captureMountTurnAsStrafe"));
        assertTrue(content.contains("normalizeIntent"));
        assertTrue(content.contains("queue.clear()"));
        assertTrue(content.contains("mount.setHasWishMovement(false)"));
        assertFalse(content.contains("if (mounted != null && riderRef != null && riderRef.isValid())"));
    }

    @Test
    void riderFollowMovesPlayerAndAppliesCustomRideCameraWithoutVanillaMountedComponent() throws IOException {
        String content = readMain(
                "com",
                "alechilles",
                "alecstamework",
                "npc",
                "systems",
                "MountedRideRiderFollowSystem.java"
        );

        assertTrue(content.contains("Player.getComponentType()"));
        assertTrue(content.contains("player.moveTo("));
        assertTrue(content.contains("MountedRideClientAttachment.attach("));
        assertTrue(content.contains("isClientCameraApplied()"));
        assertFalse(content.contains("tryRemoveComponent(riderRef, mountedComponentType)"));
    }

    @Test
    void rideClientAttachmentUsesServerCameraInsteadOfMountedUpdate() throws IOException {
        String content = readMain(
                "com",
                "alechilles",
                "alecstamework",
                "npc",
                "systems",
                "MountedRideClientAttachment.java"
        );

        assertTrue(content.contains("new MountNPC("));
        assertTrue(content.contains("new DismountNPC()"));
        assertTrue(content.contains("player.setMountEntityId(mountNetworkIdValue)"));
        assertTrue(content.contains("playerInput.setMountId(0)"));
        assertTrue(content.contains("new SetServerCamera("));
        assertTrue(content.contains("AttachedToType.LocalPlayer"));
        assertTrue(content.contains("MovementForceRotationType.CameraRotation"));
        assertTrue(content.contains("settings.canMoveType = CanMoveType.Always"));
        assertTrue(content.contains("settings.applyMovementType = ApplyMovementType.CharacterController"));
        assertTrue(content.contains("settings.skipCharacterPhysics = true"));
        assertFalse(content.contains("ApplyMovementType.Position"));
        assertTrue(content.contains("ClientCameraView.Custom,\n                false,"));
        assertFalse(content.contains("new MountedUpdate("));
        assertFalse(content.contains("MountController.Minecart"));
        assertFalse(content.contains("MountController.BlockMount"));
        assertTrue(content.contains("viewer.queueRemove(riderRef, ComponentUpdateType.Mounted)"));
        assertFalse(content.contains("new MountedComponent("));
        assertFalse(content.contains("tryRemoveComponent("));
    }

    @Test
    void tameworkRideDoesNotUseVanillaMountPacketFiltering() throws IOException {
        String plugin = readMain("com", "alechilles", "alecstamework", "Tamework.java");

        assertFalse(plugin.contains("PacketFilter"));
        assertFalse(plugin.contains("MountMovement"));
    }

    @Test
    void ridePacketHandlerConsumesTameworkDismountBeforeVanillaNpcMountPath() throws IOException {
        String content = readMain(
                "com",
                "alechilles",
                "alecstamework",
                "npc",
                "systems",
                "MountedRidePacketHandler.java"
        );

        assertTrue(content.contains("DISMOUNT_NPC_PACKET_ID = 294"));
        assertTrue(content.contains("mount.setDismountRequested(true)"));
        assertTrue(content.contains("player.setMountEntityId(0)"));
        assertTrue(content.contains("handleVanillaDismount"));
        assertTrue(content.contains("MountPlugin.checkDismountNpc"));
    }

    private static String readMain(String first, String... more) throws IOException {
        return Files.readString(MAIN_JAVA.resolve(Paths.get(first, more)), StandardCharsets.UTF_8);
    }

    private static String methodBody(String content, String startMarker, String endMarker) {
        int start = content.indexOf(startMarker);
        int end = content.indexOf(endMarker);
        assertTrue(start >= 0, "missing start marker " + startMarker);
        assertTrue(end > start, "missing end marker " + endMarker);
        return content.substring(start, end);
    }
}
