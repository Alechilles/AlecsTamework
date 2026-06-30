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

        assertTrue(interaction.contains("new MountedComponent(npcRef"));
        assertTrue(interaction.contains("MountController.Minecart"));
        assertFalse(interaction.contains("MountedGlidePacketHandler"));
        assertFalse(plugin.contains("MountedGlidePacketHandler::new"));
        assertFalse(source.contains("MountedGlidePacketHandler"));
    }

    @Test
    void mountedGlideCapturesNativeMountedInputBeforeVanillaAppliesMovement() throws IOException {
        String inputCapture = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideInputCaptureSystem.java"
        ));

        assertTrue(inputCapture.contains("Order.BEFORE, MountSystems.HandleMountInput.class"));
        assertTrue(inputCapture.contains("Query.and(playerInputComponentType, riderComponentType, mountedComponentType)"));
        assertTrue(inputCapture.contains("PlayerInput.SetRiderMovementStates"));
        assertTrue(inputCapture.contains("PlayerInput.SetHead"));
        assertTrue(inputCapture.contains("PlayerInput.SetBody"));
        assertTrue(inputCapture.contains("shouldConsumeBeforeVanillaMountHandling"));
        assertTrue(inputCapture.contains("inputIterator.remove()"));
        assertFalse(inputCapture.contains("playerInput.setMountId(0)"));
        assertFalse(inputCapture.contains("queue.clear()"));
    }

    @Test
    void mountedGlideCleanupTracksNativeDismountState() throws IOException {
        String cleanup = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideCleanupSystem.java"
        ));

        assertTrue(cleanup.contains("nativeMountStillLinkedTo"));
        assertTrue(cleanup.contains("store.getComponent(riderRef, mountedComponentType)"));
        assertTrue(cleanup.contains("mounted.getMountedToEntity()"));
        assertTrue(cleanup.contains("bufferStore.tryRemoveComponent(riderRef, mountedComponentType)"));
    }

    private static String readGlideInputStack() throws IOException {
        return Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideInputCaptureSystem.java"
        )) + Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideCleanupSystem.java"
        ));
    }
}
