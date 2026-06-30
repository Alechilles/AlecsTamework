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
    void mountedGlideUsesNativeMountAttachmentInsteadOfPacketInterceptor() throws IOException {
        String interaction = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/actions/InteractionMountEffects.java"
        ));
        String plugin = Files.readString(Path.of("src/main/java/com/alechilles/alecstamework/Tamework.java"));
        String source = readGlideInputStack();

        assertTrue(interaction.contains("NPCMountComponent.getComponentType()"));
        assertTrue(interaction.contains("store.ensureAndGetComponent(npcRef, npcMountType)"));
        assertTrue(interaction.contains("npcMount.setOwnerPlayerRef(playerRefComponent)"));
        assertTrue(interaction.contains("npcMount.setAnchor(anchorX, anchorY, anchorZ)"));
        assertFalse(interaction.contains("new MountedComponent(npcRef"));
        assertFalse(interaction.contains("MountController.Minecart"));
        assertFalse(interaction.contains("MountedGlidePacketHandler"));
        assertFalse(plugin.contains("MountedGlidePacketHandler::new"));
        assertFalse(source.contains("MountedGlidePacketHandler"));
    }

    @Test
    void mountedGlideCapturesNativeMountedInputBeforeVanillaAppliesMovement() throws IOException {
        String inputCapture = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideInputCaptureSystem.java"
        ));

        assertTrue(inputCapture.contains("MovementStatesComponent"));
        assertTrue(inputCapture.contains("HeadRotation"));
        assertTrue(inputCapture.contains("Order.BEFORE, com.hypixel.hytale.server.npc.systems.RoleSystems.BehaviourTickSystem.class"));
        assertTrue(inputCapture.contains("Query.and(mountComponentType, npcMountComponentType)"));
        assertTrue(inputCapture.contains("states.jumping || states.swimJumping"));
        assertTrue(inputCapture.contains("states.sprinting || states.running"));
        assertTrue(inputCapture.contains("states.crouching || states.forcedCrouching"));
        assertTrue(inputCapture.contains("Math.toDegrees"));
        assertFalse(inputCapture.contains("PlayerInput"));
        assertFalse(inputCapture.contains("MountSystems.HandleMountInput"));
        assertFalse(inputCapture.contains("inputIterator.remove()"));
        assertFalse(inputCapture.contains("playerInput.setMountId(0)"));
        assertFalse(inputCapture.contains("queue.clear()"));
    }

    @Test
    void mountedGlideCleanupTracksNativeDismountState() throws IOException {
        String cleanup = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideCleanupSystem.java"
        ));

        assertTrue(cleanup.contains("MountPlugin.checkDismountNpc(bufferStore, riderRef, player)"));
        assertTrue(cleanup.contains("bufferStore.tryRemoveComponent(mountRef, npcMountComponentType)"));
        assertTrue(cleanup.contains("bufferStore.ensureAndGetComponent(mountRef, Interactable.getComponentType())"));
        assertTrue(cleanup.contains("npcMountStillLinkedToRider"));
        assertFalse(cleanup.contains("mountedComponentType"));
        assertFalse(cleanup.contains("mounted.getMountedToEntity()"));
    }

    private static String readGlideInputStack() throws IOException {
        return Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideInputCaptureSystem.java"
        )) + Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideCleanupSystem.java"
        ));
    }
}
