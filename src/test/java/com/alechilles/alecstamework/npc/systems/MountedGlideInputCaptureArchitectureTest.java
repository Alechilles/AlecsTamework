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
        String plugin = Files.readString(Path.of("src/main/java/com/alechilles/alecstamework/Tamework.java"));

        assertTrue(inputCapture.contains("NPCMountComponent"));
        assertTrue(inputCapture.contains("nativeMountStillOwnedByRider"));
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
        assertTrue(inputCapture.contains("states.sprinting"));
        assertTrue(inputCapture.contains("states.crouching || states.forcedCrouching"));
        assertTrue(inputCapture.contains("Math.toDegrees"));
        assertFalse(inputCapture.contains("MountedComponent"));
        assertFalse(inputCapture.contains("mountedStillAttachedToMount"));
        assertFalse(inputCapture.contains("playerInput.setMountId(0)"));
        assertFalse(inputCapture.contains("queue.clear()"));
        assertTrue(plugin.contains("new MountedGlideInputCaptureSystem(\r\n                            npcMountComponentType,")
                || plugin.contains("new MountedGlideInputCaptureSystem(\n                            npcMountComponentType,"));
    }

    @Test
    void mountedGlideDoesNotTreatNativeRunAsSprintFlapModifier() throws IOException {
        String systemCapture = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideInputCaptureSystem.java"
        ));
        String packetCapture = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/network/MountedGlidePacketInputCapture.java"
        ));

        assertFalse(systemCapture.contains("states.sprinting || states.running"));
        assertFalse(packetCapture.contains("states.sprinting || states.running"));
    }

    @Test
    void mountedGlidePreservesQueuedRiderMovementStatesForHeldFlapInput() throws IOException {
        String inputCapture = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideInputCaptureSystem.java"
        ));

        assertTrue(inputCapture.contains("applyRiderLocalInput"));
        assertTrue(inputCapture.contains("PlayerInput.SetRiderMovementStates riderStates"));
        assertTrue(inputCapture.contains("movementStates.setMovementStates(riderStates.movementStates())"));
        assertFalse(inputCapture.contains("queue.clear()"));
    }

    @Test
    void mountedGlideCapturesJumpFromClientMovementPacketsWithoutConsumingNativeMountPackets() throws IOException {
        String handler = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/network/MountedRidePacketHandler.java"
        ));
        String helper = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/network/MountedGlidePacketInputCapture.java"
        ));
        String inputCapture = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideInputCaptureSystem.java"
        ));
        String component = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/components/TameworkMountedGlideComponent.java"
        ));

        assertTrue(handler.contains("glidePacketInputCapture.capture(packet, packetHandler);"));
        assertTrue(handler.indexOf("glidePacketInputCapture.capture(packet, packetHandler);")
                < handler.indexOf("if (!tryHandleTameworkClientMovement(packet))"));
        assertTrue(helper.contains("TameworkMountedGlideComponent"));
        assertTrue(helper.contains("packet.riderMovementStates != null ? packet.riderMovementStates : packet.movementStates"));
        assertTrue(helper.contains("captureStates(mount,"));
        assertTrue(helper.contains("mount.setLastPacketInputAtMs(now);"));
        assertTrue(inputCapture.contains("PACKET_SNAPSHOT_GRACE_MS"));
        assertTrue(inputCapture.contains("hasRecentPacketSnapshot(mount, now)"));
        assertTrue(inputCapture.contains("mount.getLastPacketInputAtMs()"));
        assertTrue(component.contains("LastPacketInputAtMs"));
    }

    @Test
    void mountedGlideCapturesJumpFromMountMovementPacketsWithoutConsumingNativeMountPackets() throws IOException {
        String handler = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/network/MountedRidePacketHandler.java"
        ));
        String helper = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/network/MountedGlidePacketInputCapture.java"
        ));

        String method = extractMethodSource(
                handler,
                "private void handleMountMovement",
                "private void handleMouseInteraction"
        );
        assertTrue(method.contains("glidePacketInputCapture.capture(packet, packetHandler);"));
        assertTrue(method.indexOf("glidePacketInputCapture.capture(packet, packetHandler);")
                < method.indexOf("if (!tryHandleTameworkMountMovement(packet))"));
        assertTrue(helper.contains("void capture(@Nonnull MountMovement packet"));
        assertTrue(helper.contains("captureStates(mount, packet.movementStates, now);"));
        assertTrue(helper.contains("\"TameworkGlide debug: packet source=mountMovement"));
    }

    @Test
    void mountedGlidePacketCaptureLogsRawInputProbeTransitions() throws IOException {
        String helper = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/network/MountedGlidePacketInputCapture.java"
        ));
        String clientProbe = extractMethodSource(
                helper,
                "private void logInputProbe(@Nonnull ClientMovement packet",
                "private void logInputProbe(@Nonnull MountMovement packet"
        );
        String mountProbe = extractMethodSource(
                helper,
                "private void logInputProbe(@Nonnull MountMovement packet",
                "private void logInputProbe(@Nonnull String message"
        );

        assertTrue(helper.contains("TameworkGlide inputProbe"));
        assertTrue(helper.contains("lastClientMovementInputProbeSignature"));
        assertTrue(helper.contains("lastMountMovementInputProbeSignature"));
        assertTrue(helper.contains("formatProbeStates"));
        assertTrue(helper.contains("formatProbeSnapshot"));
        assertTrue(helper.contains("formatProbeControlSnapshot"));
        assertTrue(clientProbe.contains("formatProbeControlSnapshot(mount)"));
        assertTrue(mountProbe.contains("formatProbeControlSnapshot(mount)"));
        assertFalse(clientProbe.contains("|snapshot=\" + formatProbeSnapshot(mount)"));
        assertFalse(mountProbe.contains("|snapshot=\" + formatProbeSnapshot(mount)"));
    }

    @Test
    void mountedGlideIsolatesNativeMountMovementBeforeNpcBehaviour() throws IOException {
        String velocity = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlidePlayerVelocitySystem.java"
        ));
        String plugin = Files.readString(Path.of("src/main/java/com/alechilles/alecstamework/Tamework.java"));

        assertTrue(velocity.contains("extends EntityTickingSystem<EntityStore>"));
        assertTrue(velocity.contains("implements IVelocityModifyingSystem"));
        assertTrue(velocity.contains("NPCMountComponent"));
        assertTrue(velocity.contains("MovementStatesComponent"));
        assertTrue(velocity.contains("Velocity.getComponentType()"));
        assertTrue(velocity.contains("Order.AFTER, MountedGlideInputCaptureSystem.class"));
        assertFalse(velocity.contains("PlayerVelocityInstructionSystem"));
        assertTrue(velocity.contains("Query.and(mountComponentType, nativeMountComponentType, transformComponentType)"));
        assertTrue(velocity.contains("nativeMount.getOwnerPlayerRef().getReference()"));
        assertTrue(velocity.contains("riderRef.getStore() != store"));
        assertTrue(velocity.contains("commandBuffer.getComponent(riderRef, velocityComponentType)"));
        assertTrue(velocity.contains("MountedGlidePhysics.update"));
        assertTrue(velocity.contains("velocity.addInstruction(velocityVector, null, ChangeVelocityType.Set)"));
        assertTrue(plugin.contains("new MountedGlidePlayerVelocitySystem(\r\n                            mountedGlideComponentType,\r\n                            npcMountComponentType,")
                || plugin.contains("new MountedGlidePlayerVelocitySystem(\n                            mountedGlideComponentType,\n                            npcMountComponentType,"));
        assertTrue(plugin.contains("Velocity.getComponentType()"));
        assertFalse(plugin.contains("new MountedGlideNativeInputIsolationSystem"));
        assertFalse(plugin.contains("new MountedGlideStateSystem"));
        assertFalse(plugin.contains("new MountedGlideAuthoritativePoseSystem"));
    }

    @Test
    void mountedGlideCleanupTracksNativeDismountState() throws IOException {
        String cleanup = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideCleanupSystem.java"
        ));
        String plugin = Files.readString(Path.of("src/main/java/com/alechilles/alecstamework/Tamework.java"));

        assertTrue(cleanup.contains("NPCMountComponent"));
        assertTrue(cleanup.contains("nativeMountComponentType"));
        assertTrue(cleanup.contains("riderNativeMountedToMount"));
        assertTrue(cleanup.contains("nativeMount.getOwnerPlayerRef().getReference()"));
        assertFalse(cleanup.contains("MountPlugin.checkDismountNpc"));
        assertTrue(cleanup.contains("player.setMountEntityId(0)"));
        assertTrue(cleanup.contains("MountPlugin.resetOriginalPlayerMovementSettings(riderRef, bufferStore)"));
        assertTrue(cleanup.contains("RoleChangeSystem.requestRoleChange"));
        assertTrue(cleanup.contains("nativeMount.getOriginalRoleIndex()"));
        assertTrue(cleanup.contains("bufferStore.tryRemoveComponent(mountRef, nativeMountComponentType)"));
        assertTrue(cleanup.contains("bufferStore.ensureAndGetComponent(mountRef, Interactable.getComponentType())"));
        assertFalse(cleanup.contains("MountedComponent"));
        assertFalse(cleanup.contains("riderMountedToMount"));
        String cleanupMethod = extractMethodSource(
                cleanup,
                "private void cleanupGlide",
                "private void removeNativeMountComponent"
        );
        assertTrue(cleanupMethod.indexOf("restoreNpcRole(mountRef, npc, mount, nativeMount, bufferStore)")
                < cleanupMethod.indexOf("bufferStore.tryRemoveComponent(mountRef, mountComponentType)"));
        assertTrue(cleanupMethod.indexOf("restoreNpcRole(mountRef, npc, mount, nativeMount, bufferStore)")
                < cleanupMethod.indexOf("removeNativeMountComponent(mountRef, bufferStore)"));
        assertTrue(plugin.contains("new MountedGlideCleanupSystem(\r\n                            npcMountComponentType,")
                || plugin.contains("new MountedGlideCleanupSystem(\n                            npcMountComponentType,"));
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
        assertTrue(handler.contains("store.tryRemoveComponent(riderRef, riderType)"));
        assertTrue(handler.contains("store.tryRemoveComponent(mountRef, NPCMountComponent.getComponentType())"));
        assertTrue(handler.contains("store.tryRemoveComponent(mountRef, mountType)"));
        assertTrue(handler.contains("store.ensureAndGetComponent(mountRef, Interactable.getComponentType())"));
        assertTrue(handler.contains("RoleChangeSystem.requestRoleChange"));
        assertTrue(handler.contains("nativeMount.getOriginalRoleIndex()"));
        String method = extractMethodSource(
                handler,
                "private boolean handleMountedGlideDismount",
                "private RideSession resolveRegisteredRideSession"
        );
        assertFalse(method.contains("MountPlugin.checkDismountNpc"));
        assertTrue(method.contains("player.setMountEntityId(0)"));
        assertTrue(method.contains("MountPlugin.resetOriginalPlayerMovementSettings(riderRef, store)"));
        assertTrue(method.contains("restoreNpcRole(mountRef, mount, nativeMount, store)"));
        assertTrue(method.indexOf("restoreNpcRole(mountRef, mount, nativeMount, store)")
                < method.indexOf("removeNativeMountComponent(mountRef, store)"));
        assertTrue(method.indexOf("restoreNpcRole(mountRef, mount, nativeMount, store)")
                < method.indexOf("store.tryRemoveComponent(mountRef, mountType)"));
        assertFalse(method.contains("MountedComponent.getComponentType()"));
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
