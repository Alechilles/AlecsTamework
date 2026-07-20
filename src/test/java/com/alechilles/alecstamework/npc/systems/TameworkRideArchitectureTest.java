package com.alechilles.alecstamework.npc.systems;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
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
        String modes = readMain(
                "com", "alechilles", "alecstamework", "npc", "actions", "InteractionMountMode.java"
        );
        String dispatcher = readMain(
                "com", "alechilles", "alecstamework", "npc", "actions", "InteractionMountModeDispatcher.java"
        );
        String tameworkRideMount = methodBody(content, "private boolean applyTameworkRideMount", "private void maybeLogTameworkRideMountDebug");

        assertTrue(modes.contains("case \"tameworkride\" -> TAMEWORK_RIDE"));
        assertTrue(dispatcher.contains("case TAMEWORK_RIDE -> rideMount.apply(request)"));
        assertTrue(content.contains("request -> applyTameworkRideMount("));
        assertTrue(content.contains("applyTameworkRideMount"));
        assertFalse(tameworkRideMount.contains("new MountedComponent("));
        assertFalse(tameworkRideMount.contains("MountController.Minecart"));
        assertFalse(tameworkRideMount.contains("MountController.BlockMount"));
        assertTrue(tameworkRideMount.contains("TameworkRideMountComponent"));
        assertTrue(tameworkRideMount.contains("TameworkRideRiderComponent"));
        assertFalse(tameworkRideMount.contains("NPCMountComponent"));
        assertFalse(tameworkRideMount.contains("EMPTY_ROLE_ID"));
        assertTrue(content.contains("Ref<EntityStore> existingMountRef = resolveMountRefFromRider(rider, store)"));
        assertTrue(content.contains("if (existingMountRef == null && mounted != null)"));
        assertTrue(content.contains("boolean stale = existingMountRef == null"));
        assertFalse(content.contains("Ref<EntityStore> existingMountRef = mounted == null"));
        assertTrue(content.contains("MountedRideClientAttachment.detach(store, playerRef)"));
        assertFalse(content.contains("MountedRideClientAttachment.suppressRiderCollision(store, playerRef)"));
        assertFalse(content.contains("MountedRideClientAttachment.restoreRiderCollision(store, playerRef, rider)"));
        assertTrue(content.contains("new TameworkRideRiderComponent(npcUuid.getUuid().toString())"));
        assertTrue(tameworkRideMount.contains("MountedRideClientAttachment.placeRiderAtMountAnchor(store, playerRef, npcRef, existingNpcRide)"));
        assertTrue(tameworkRideMount.contains("MountedRideClientAttachment.placeRiderAtMountAnchor(store, playerRef, npcRef, rideMount)"));
        assertTrue(tameworkRideMount.contains("MountedRideClientAttachment.attach(store, playerRef, npcRef, rideMount)"));
    }

    @Test
    void rideComponentsSystemsAndBuildersAreRegistered() throws IOException {
        String plugin = readMain("com", "alechilles", "alecstamework", "Tamework.java");
        String componentRegistrar = readMain(
                "com", "alechilles", "alecstamework", "TameworkComponentRegistrar.java"
        );
        String registrar = readMain(
                "com",
                "alechilles",
                "alecstamework",
                "npc",
                "TameworkNpcBuilderRegistrar.java"
        );

        assertTrue(componentRegistrar.contains("\"TameworkRideMount\""));
        assertTrue(componentRegistrar.contains("\"TameworkRideRider\""));
        assertTrue(plugin.contains("new MountedRideInputCaptureSystem("));
        assertTrue(plugin.contains("new MountedRideCleanupSystem("));
        assertTrue(plugin.contains("new MountedRideRiderFollowSystem("));
        assertTrue(plugin.contains("MountedRidePacketHandler::new"));
        assertFalse(plugin.contains("MountedRideMountMovementPacketFilter.register()"));
        assertFalse(plugin.contains("PacketAdapters.deregisterInbound(rideMountMovementPacketFilter)"));
        assertTrue(registrar.contains("BuilderBodyMotionTameworkFlyingOrbit.BUILDER_ID"));
        assertTrue(registrar.contains("BuilderBodyMotionTameworkRide.BUILDER_ID"));
        assertTrue(registrar.contains("BuilderMotionControllerTameworkFly.BUILDER_ID"));
        assertTrue(registrar.contains("BuilderMotionControllerTameworkRideWalk.BUILDER_ID"));
        assertFalse(registrar.contains("BuilderMotionControllerTameworkRideFly"));
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
        assertTrue(content.contains("captureWish(mount, wish.getX(), wish.getY(), wish.getZ())"));
        assertTrue(content.contains("mount.captureWishMovement(wishX * scale, 0.0, wishZ * scale, wishZ < -0.0001)"));
        assertTrue(content.contains("mount.captureWishMovement(intent.strafe(), 0.0, intent.forward())"));
        assertTrue(content.contains("mount.captureWishMovement(strafe, existingVertical, existingForward, existingBackwardBrakeInput)"));
        assertFalse(content.contains("mount.captureWishMovement(wish.getX(), wish.getY(), wish.getZ())"));
        assertFalse(content.contains("wishY * scale"));
        assertTrue(content.contains("PlayerInput.SetRiderMovementStates"));
        assertTrue(content.contains("applyRiderLocalInput"));
        assertTrue(content.contains("captureCurrentRiderRotation"));
        assertTrue(content.contains("captureCurrentRiderMovementStates"));
        assertTrue(content.contains("syncAuthoritativePose(mountRef, mount, commandBuffer, false)"));
        assertTrue(content.contains("captureMountTurnAsStrafe"));
        assertTrue(content.contains("normalizeIntent"));
        assertTrue(content.contains("return store.getExternalData().getWorld().getEntityRef(UUID.fromString(mountUuid))"));
        assertTrue(content.contains("MountedRidePacketHandler.unregisterRide"));
        assertTrue(content.contains("restoreNpcState(mountRef, npc, currentMount, bufferStore)"));
        assertTrue(content.contains("boolean sawMovementIntent = false"));
        assertTrue(content.contains("playerInput.setMountId(0)"));
        assertFalse(content.contains("mount.clearControlInputSnapshot();"));
        assertTrue(content.contains("absolute.getZ() - position.z,\n                true")
                || content.contains("absolute.getZ() - position.z,\r\n                true"));
        assertTrue(content.contains("captureAbsoluteMovement(mount, mountRef, absolute, commandBuffer)"));
        assertTrue(content.contains("captureVelocityMovement(mount, value.x, value.y, value.z)"));
        assertTrue(content.contains("TameworkRideVelocityIntent.isVerticalDominant(worldX, worldY, worldZ)"));
        assertTrue(content.contains("TameworkRideVelocityIntent.hasUsableHorizontalIntent(worldX, worldZ)"));
        assertTrue(content.contains("mount.captureWishMovement(0.0, TameworkRideVelocityIntent.verticalInput(worldX, worldY, worldZ), 0.0)"));
        assertTrue(content.contains("shouldPreserveExistingForwardIntent(mount, intent)"));
        assertTrue(content.contains("VELOCITY_BRAKE_BACKWARD_DEAD_ZONE = -0.25"));
        assertTrue(content.contains("VELOCITY_BRAKE_BACKWARD_DOMINANCE = 1.35"));
        assertTrue(content.contains("if (isBackwardBrakeIntent(intent))"));
        assertTrue(content.contains("captureBackwardBrakeIntent(mount)"));
        assertTrue(content.contains("mount.captureWishMovement(0.0, 0.0, 0.0, true)"));
        assertTrue(content.contains("queue.clear()"));
        assertTrue(content.contains("mount.setHasWishMovement(false)"));
        assertFalse(content.contains("if (mounted != null && riderRef != null && riderRef.isValid())"));
    }

    @Test
    void riderFollowAppliesCameraWithoutMovingRealPlayerEveryTick() throws IOException {
        String content = readMain(
                "com",
                "alechilles",
                "alecstamework",
                "npc",
                "systems",
                "MountedRideRiderFollowSystem.java"
        );

        assertTrue(content.contains("Player.getComponentType()"));
        assertFalse(content.contains("RIDER_ANCHOR_CORRECTION_DISTANCE_SQUARED"));
        assertFalse(content.contains("MountedRideClientAttachment.placeRiderAtMountAnchorIfNeeded("));
        assertFalse(content.contains("MountedRideClientAttachment.placeRiderAtMountAnchor(commandBuffer"));
        assertFalse(content.contains("MountedRideClientAttachment.queueRiderAnchorTeleport("));
        assertTrue(content.contains("MountedRideClientAttachment.attach("));
        assertTrue(content.contains("MountedRideClientAttachment.updateCamera("));
        assertTrue(content.contains("resolveClientSpeedModifier"));
        assertTrue(content.contains("MotionControllerTameworkFly fly"));
        assertTrue(content.contains("MotionControllerTameworkRideWalk walk"));
        assertTrue(content.contains("currentRider.setClientSpeedModifier(currentClientSpeedModifier)"));
        assertTrue(content.contains("commandBuffer.run(bufferStore ->"));
        assertTrue(content.contains("bufferStore.putComponent(riderRef, rideRiderComponentType, currentRider)"));
        assertTrue(content.contains("isClientCameraApplied()"));
        assertTrue(content.contains("return store.getExternalData().getWorld().getEntityRef(UUID.fromString(mountUuid))"));
        assertTrue(content.contains("return mounted != null && mounted.getMountedToEntity()"));
        assertTrue(content.indexOf("return store.getExternalData().getWorld().getEntityRef(UUID.fromString(mountUuid))")
                < content.indexOf("return mounted != null && mounted.getMountedToEntity()"));
        assertFalse(content.contains("tryRemoveComponent(riderRef, mountedComponentType)"));
    }

    @Test
    void rideClientAttachmentUsesEntityAttachedCustomCameraWithoutTeleportLoop() throws IOException {
        String content = readMain(
                "com",
                "alechilles",
                "alecstamework",
                "npc",
                "systems",
                "MountedRideClientAttachment.java"
        );

        assertTrue(content.contains("new DismountNPC()"));
        assertTrue(content.contains("DEFAULT_RIDE_INPUT_SPEED_MODIFIER = 10.0"));
        assertTrue(content.contains("RIDE_CAMERA_EYE_HEIGHT = 1.6"));
        assertTrue(content.contains("playerInput.setMountId(0)"));
        assertTrue(content.contains("deliberately avoids all native mount identity"));
        assertTrue(content.contains("ClientCameraView.Custom"));
        assertTrue(content.contains("createRideCameraSettings(mountEntityId, mount, speedModifier)"));
        assertTrue(content.contains("ServerCameraSettings settings = new ServerCameraSettings()"));
        assertTrue(content.contains("settings.attachedToType = AttachedToType.EntityId"));
        assertTrue(content.contains("settings.attachedToEntityId = mountEntityId"));
        assertTrue(content.contains("settings.positionType = PositionType.AttachedToPlusOffset"));
        assertTrue(content.contains("settings.positionOffset = new com.hypixel.hytale.protocol.Position("));
        assertTrue(content.contains("mount.getAnchorY() + RIDE_CAMERA_EYE_HEIGHT"));
        assertTrue(content.contains("settings.applyMovementType = ApplyMovementType.CharacterController"));
        assertFalse(content.contains("settings.applyMovementType = ApplyMovementType.Position"));
        assertFalse(content.contains("settings.movementMultiplier = new Vector3f(0.0f, 0.0f, 0.0f)"));
        assertFalse(content.contains("settings.applyLookType = ApplyLookType.LocalPlayerLookOrientation"));
        assertTrue(content.contains("settings.rotationType = RotationType.Custom"));
        assertTrue(content.contains("settings.rotation = resolveCameraRotation(mount)"));
        assertTrue(content.contains("settings.applyLookType = ApplyLookType.Rotation"));
        assertTrue(content.contains("settings.lookMultiplier = new Vector2f(1.0f, 1.0f)"));
        assertTrue(content.contains("settings.mouseInputType = MouseInputType.LookAtPlane"));
        assertTrue(content.contains("settings.planeNormal = new Vector3f(0.0f, 1.0f, 0.0f)"));
        assertTrue(content.contains("private static Direction resolveCameraRotation"));
        assertTrue(content.contains("settings.skipCharacterPhysics = true"));
        assertTrue(content.contains("settings.isFirstPerson = true"));
        assertTrue(content.contains("player.moveTo("));
        assertFalse(content.contains("placeRiderAtMountAnchorIfNeeded"));
        assertFalse(content.contains("queueRiderAnchorTeleport"));
        assertFalse(content.contains("queueRiderAnchorTeleportIfClientTooFar"));
        assertFalse(content.contains("Teleport.createForPlayer"));
        assertFalse(content.contains("teleport.setHeadRotation"));
        assertFalse(content.contains("store.putComponent(riderRef, teleportType, teleport)"));
        assertTrue(content.contains("distanceSquared("));
        assertTrue(content.contains("NetworkId mountNetworkId"));
        assertTrue(content.contains("sendRideCamera(player, mountNetworkId.getId(), mount, speedModifier)"));
        assertFalse(content.contains("new MountNPC("));
        assertFalse(content.contains("player.setMountEntityId(mountNetworkId.getId())"));
        assertFalse(content.contains("placeRiderAtMountAnchor(store, riderRef, mountRef, mount)"));
        assertFalse(content.contains("new ClientTeleport("));
        assertFalse(content.contains("new ModelTransform("));
        assertFalse(content.contains("new Position(anchoredPosition.x, anchoredPosition.y, anchoredPosition.z)"));
        assertFalse(content.contains("new MountedUpdate("));
        assertFalse(content.contains("viewer.queueUpdate(riderRef, update)"));
        assertFalse(content.contains("MountController.Minecart"));
        assertFalse(content.contains("sendMountPacket"));
        assertFalse(content.contains("MountController.BlockMount"));
        assertTrue(content.contains("viewer.queueRemove(riderRef, ComponentUpdateType.Mounted)"));
        assertFalse(content.contains("suppressRiderCollision"));
        assertFalse(content.contains("Intangible.INSTANCE"));
        assertFalse(content.contains("restoreRiderCollision"));
    }

    @Test
    void tameworkRideWrapsClientAndMountMovementPackets() throws IOException {
        String plugin = readMain("com", "alechilles", "alecstamework", "Tamework.java");
        String handler = readMain(
                "com",
                "alechilles",
                "alecstamework",
                "npc",
                "network",
                "MountedRidePacketHandler.java"
        );

        assertFalse(plugin.contains("PacketFilter"));
        assertTrue(handler.contains("ClientMovement.PACKET_ID"));
        assertTrue(handler.contains("MountMovement.PACKET_ID"));
        assertTrue(handler.contains("MouseInteraction.PACKET_ID"));
        assertTrue(handler.contains("SyncInteractionChains.PACKET_ID"));
        assertTrue(handler.contains("findRegisteredHandler(ClientMovement.PACKET_ID)"));
        assertTrue(handler.contains("findRegisteredHandler(MountMovement.PACKET_ID)"));
        assertTrue(handler.contains("findRegisteredHandler(MouseInteraction.PACKET_ID)"));
        assertTrue(handler.contains("findRegisteredHandler(SyncInteractionChains.PACKET_ID)"));
        assertTrue(handler.contains("ACTIVE_TAMEWORK_RIDES"));
        assertTrue(handler.contains("public static void registerRide"));
        assertTrue(handler.contains("public static void unregisterRide"));
        assertTrue(handler.contains("resolveRegisteredRideSession()"));
        assertTrue(handler.contains("session.world.execute"));
        assertFalse(handler.contains("RideContext context = resolveRideContext();"));
        assertTrue(handler.contains("tryHandleTameworkClientMovement"));
        assertTrue(handler.contains("tryHandleTameworkMountMovement"));
        assertTrue(handler.contains("tryHandleTameworkMouseInteraction"));
        assertTrue(handler.contains("logClientMovementDebug(packet, mount, capturedMovementIntent)"));
        assertTrue(handler.contains("logMountMovementDebug(packet, mount)"));
        assertTrue(handler.contains("logMouseMovementDebug(packet, mount)"));
        assertTrue(handler.contains("packet source=clientMovement"));
        assertTrue(handler.contains("packet source=mountMovement"));
        assertTrue(handler.contains("packet source=mouseInteraction"));
        assertTrue(handler.contains("boolean capturedMovementIntent = false"));
        assertTrue(handler.contains("mount.clearWishMovement()"));
        assertFalse(handler.contains("mount.clearControlInputSnapshot();"));
        assertTrue(handler.contains("if (!tryHandleTameworkClientMovement(packet))"));
        assertTrue(handler.contains("delegate(clientMovementDelegate, packet)"));
        assertTrue(handler.contains("delegate(mountMovementDelegate, packet)"));
        assertTrue(handler.contains("delegate(mouseInteractionDelegate, packet)"));
        assertTrue(handler.contains("delegate(interactionChainsDelegate, packet)"));
        assertTrue(handler.indexOf("if (!tryHandleTameworkClientMovement(packet))")
                < handler.indexOf("delegate(clientMovementDelegate, packet)"));
        assertTrue(handler.contains("packet.wishMovement"));
        assertTrue(handler.contains("mount.captureWishMovement(wishX * scale, 0.0, wishZ * scale, wishZ < -0.0001)"));
        assertTrue(handler.contains("mount.captureWishMovement(strafe, 0.0, forward)"));
        assertTrue(handler.contains("packet.velocity.x"));
        assertTrue(handler.contains("capturedPacketInput"));
        assertTrue(handler.contains("captureVelocityMovementIntent(mount, packet.velocity.x, packet.velocity.y, packet.velocity.z)"));
        assertTrue(handler.contains("TameworkRideVelocityIntent.isVerticalDominant(worldX, worldY, worldZ)"));
        assertTrue(handler.contains("TameworkRideVelocityIntent.hasUsableHorizontalIntent(worldX, worldZ)"));
        assertTrue(handler.contains("captureVerticalVelocityIntent(mount, worldX, worldY, worldZ)"));
        assertTrue(handler.contains("mount.captureWishMovement(0.0, TameworkRideVelocityIntent.verticalInput(worldX, worldY, worldZ), 0.0)"));
        assertTrue(handler.contains("shouldPreserveExistingForwardIntent(mount, intent)"));
        assertTrue(handler.contains("captureExistingForwardIntent(mount)"));
        assertTrue(handler.contains("MovementIntent intent = projectWorldMovement(mount, worldX, worldZ, true)"));
        assertTrue(handler.contains("if (!isForwardDominant(intent))"));
        assertTrue(handler.contains("if (isBackwardBrakeIntent(intent))"));
        assertTrue(handler.contains("captureBackwardBrakeIntent(mount)"));
        assertTrue(handler.contains("mount.captureWishMovement(0.0, 0.0, 0.0, true)"));
        assertTrue(handler.contains("VELOCITY_LOOK_FORWARD_DEAD_ZONE = 0.25"));
        assertTrue(handler.contains("VELOCITY_LOOK_FORWARD_DOMINANCE = 1.35"));
        assertTrue(handler.contains("VELOCITY_BRAKE_BACKWARD_DEAD_ZONE = -0.25"));
        assertTrue(handler.contains("VELOCITY_BRAKE_BACKWARD_DOMINANCE = 1.35"));
        assertTrue(handler.contains("if (captureForwardLookFromWorldVector(mount, worldX, worldY, worldZ))"));
        assertTrue(handler.contains("double yawDelta = Math.abs(normalizeAngle(yaw - currentYaw))"));
        assertTrue(handler.contains("MAX_VELOCITY_LOOK_YAW_DELTA = Math.toRadians(25.0)"));
        assertTrue(handler.contains("if (yawDelta > MAX_VELOCITY_LOOK_YAW_DELTA)"));
        assertFalse(handler.contains("packet.relativePosition.x"));
        assertFalse(handler.contains("captureAbsoluteMovementFromRider"));
        assertFalse(handler.contains("maybeQueueClientAnchorCorrection"));
        assertFalse(handler.contains("CLIENT_ANCHOR_TELEPORT_INTERVAL_MS"));
        assertFalse(handler.contains("CLIENT_ANCHOR_TELEPORT_DISTANCE_SQUARED"));
        assertFalse(handler.contains("wishY * scale"));
        assertTrue(handler.contains("packet.lookOrientation"));
        assertTrue(handler.contains("captureMouseLook(mount, packet.mouseMotion.relativeMotion.x, packet.mouseMotion.relativeMotion.y)"));
        assertTrue(handler.contains("MOUSE_LOOK_RADIANS_PER_UNIT"));
        assertTrue(handler.contains("captureAbsoluteMovementFromMount"));
        assertFalse(handler.contains("MountedRideClientAttachment.placeRiderAtMountAnchor(\n                    current.store,\n                    current.riderRef,\n                    current.mountRef,\n                    mount\n            )")
                || handler.contains("MountedRideClientAttachment.placeRiderAtMountAnchor(\r\n                    current.store,\r\n                    current.riderRef,\r\n                    current.mountRef,\r\n                    mount\r\n            )"));
        assertTrue(handler.contains("DismountNPC.PACKET_ID"));
        assertFalse(handler.contains("DISMOUNT_NPC_PACKET_ID"));
        assertFalse(handler.contains("294"));
        assertTrue(handler.contains("MountedRideClientAttachment.placeRiderAtMountAnchor(store, riderRef, mountRef, mount)"));
        assertTrue(handler.contains("MountedRideClientAttachment.detach(store, riderRef)"));
        assertTrue(handler.contains("store.tryRemoveComponent(riderRef, riderType)"));
        assertTrue(handler.contains("store.tryRemoveComponent(mountRef, mountType)"));
        assertTrue(handler.contains("MountPlugin.checkDismountNpc(store, riderRef, player)"));
    }

    @Test
    void tameworkFlyControllerForcesFlyingAnimationStatesWhileAirborne() throws IOException {
        String content = readMain(
                "com",
                "alechilles",
                "alecstamework",
                "npc",
                "movement",
                "MotionControllerTameworkFly.java"
        );

        assertTrue(content.contains("public final class MotionControllerTameworkFly"));
        assertTrue(content.contains("BuilderMotionControllerTameworkFly.BUILDER_ID"));
        assertTrue(content.contains("updateMovementState"));
        assertTrue(content.contains("updateFlyingStates(movementStates, horizontalIdle, fast)"));
        assertTrue(content.contains("clearGroundMovementStates(movementStates)"));
        assertTrue(content.contains("movementStates.horizontalIdle = horizontalIdle"));
        assertTrue(content.contains("AnimationSlot.Movement"));
        assertTrue(content.contains("FLY_IDLE_ANIMATION"));
        assertTrue(content.contains("FLY_ANIMATION"));
        assertTrue(content.contains("FLY_FAST_ANIMATION"));
        assertTrue(content.contains("TameworkFlyAnimationState.resolveHorizontalIdle"));
        assertTrue(content.contains("TameworkFlyVisualState.isVerticalDominantFlight(lastVelocity)"));
        assertTrue(content.contains("TameworkFlyVisualState.resolveVisualPitch(targetPitch, lastVelocity)"));
        assertTrue(content.contains("targetPitch = TameworkFlyVisualState.limitPitch(targetPitch)"));
        assertTrue(content.contains("lastVisualPitch = TameworkFlyVisualState.approachVisualAngle(lastVisualPitch, visualPitch, dt)"));
        assertTrue(content.contains("steering.setPitch(lastVisualPitch)"));
        assertTrue(content.contains("lastRoll = approach(lastRoll, (float) strafeRoll, rollStep(dt))"));
        assertTrue(content.contains("if (verticalDominantFlight)"));
        assertTrue(content.contains("setMotionKind(MotionKind.FLYING)"));
        assertTrue(content.contains("TameworkFlyAnimationState.resolveFast"));
        assertTrue(content.contains("lastFlightMovementAnimation"));
        assertTrue(content.contains("if (ride == null)"));
        assertTrue(content.indexOf("if (ride == null)")
                < content.indexOf("translation.set(steering.getTranslation())"));
        assertTrue(content.contains("return super.computeMove(ref, role, steering, dt, translation, componentAccessor)"));
        assertTrue(content.contains("return lastRidden || super.canRestAtPlace()"));
        assertTrue(content.contains("return lastRidden ? 0.0 : super.getDesiredAltitudeWeight()"));
        assertTrue(content.contains("mountedMaxHorizontalSpeed"));
        assertTrue(content.contains("mountedMaxClimbSpeed"));
        assertTrue(content.contains("mountedMaxSinkSpeed"));
        assertTrue(content.contains("mountedSprintMultiplier"));
        assertTrue(content.contains("getMountedClientSpeed"));
        assertTrue(content.contains("public double getMaximumSpeed()"));
        assertTrue(content.contains("lastHorizontalSpeedLimit"));
        assertTrue(content.contains("targetYaw = approachAngle(getYaw(), targetYaw, maxTurnSpeed * (float) dt)"));
        assertTrue(content.contains("resolveRiddenTranslation(ride, targetYaw, targetPitch, translation)"));
        assertTrue(content.contains("forwardX * forwardAmount + rightX * strafe"));
        assertTrue(content.contains("float delta = normalizeAngle(target - value)"));
        assertTrue(content.contains("boolean brakingFromBackwardInput = hasBackwardInput(ride)"));
        assertTrue(content.contains("inputLength <= INPUT_DEAD_ZONE && !brakingFromBackwardInput"));
        assertTrue(content.contains("RiddenBackwardBrake.apply(targetVelocity, lastVelocity, riddenBackwardBrakeState, brakingFromBackwardInput, dt)"));
        assertTrue(content.contains("MountedFlightCollisionRecovery.apply(ride, translation, mountedCollisionRecoveryState)"));
        assertTrue(content.contains("MountedFlightCollisionRecovery.recordMoveResult("));
        assertTrue(content.contains("ride.isRiderBackwardBrakeInput()"));
        assertTrue(content.contains("lastRiddenBackwardBraking"));
        assertTrue(content.contains("backwardAirbrake=%s"));
        assertTrue(content.contains("collisionRecovery=%s"));
        assertFalse(content.contains("targetVelocity.mul(effectHorizontalSpeedMultiplier)"));
        assertFalse(content.contains("collisionResult.disableCharacterCollisions()"));
        assertFalse(content.contains("collisionResult.enableCharacterCollsions()"));
    }

    @Test
    void tameworkFlyBuilderExposesMountedSpeedOverrides() throws IOException {
        String builder = readMain(
                "com",
                "alechilles",
                "alecstamework",
                "npc",
                "movement",
                "BuilderMotionControllerTameworkFly.java"
        );

        assertTrue(builder.contains("\"MountedMaxHorizontalSpeed\""));
        assertTrue(builder.contains("\"MountedMaxClimbSpeed\""));
        assertTrue(builder.contains("\"MountedMaxSinkSpeed\""));
        assertTrue(builder.contains("\"MountedAcceleration\""));
        assertTrue(builder.contains("\"MountedDeceleration\""));
        assertTrue(builder.contains("\"MountedSprintMultiplier\""));
        assertTrue(builder.contains("DEFAULT_MOUNTED_SPRINT_MULTIPLIER"));
    }

    @Test
    void tameworkRideWalkBuilderExposesMountedGroundSpeedOverrides() throws IOException {
        String builder = readMain(
                "com",
                "alechilles",
                "alecstamework",
                "npc",
                "movement",
                "BuilderMotionControllerTameworkRideWalk.java"
        );
        String controller = readMain(
                "com",
                "alechilles",
                "alecstamework",
                "npc",
                "movement",
                "MotionControllerTameworkRideWalk.java"
        );

        assertTrue(builder.contains("BUILDER_ID = \"TameworkRideWalk\""));
        assertTrue(builder.contains("\"MountedMaxWalkSpeed\""));
        assertTrue(builder.contains("\"MountedSprintMultiplier\""));
        assertTrue(controller.contains("extends MotionControllerWalk"));
        assertTrue(controller.contains("TameworkRideMountComponent"));
        assertTrue(controller.contains("mountedMaxWalkSpeed"));
        assertTrue(controller.contains("return BuilderMotionControllerTameworkRideWalk.BUILDER_ID"));
    }

    @Test
    void genericTameworkFlyBuilderReplacesRideSpecificFlightId() throws IOException {
        String builder = readMain(
                "com",
                "alechilles",
                "alecstamework",
                "npc",
                "movement",
                "BuilderMotionControllerTameworkFly.java"
        );
        String controller = readMain(
                "com",
                "alechilles",
                "alecstamework",
                "npc",
                "movement",
                "MotionControllerTameworkFly.java"
        );
        String rideComponent = readMain(
                "com",
                "alechilles",
                "alecstamework",
                "npc",
                "components",
                "TameworkRideMountComponent.java"
        );

        assertTrue(builder.contains("BUILDER_ID = \"TameworkFly\""));
        assertTrue(builder.contains("return new MotionControllerTameworkFly(builderSupport, this)"));
        assertTrue(controller.contains("return BuilderMotionControllerTameworkFly.BUILDER_ID"));
        assertTrue(rideComponent.contains("DEFAULT_FLIGHT_CONTROLLER = \"TameworkFly\""));
        assertFalse(builder.contains("TameworkRideFly"));
        assertFalse(controller.contains("TameworkRideFly"));
        assertFalse(rideComponent.contains("\"TameworkRideFly\""));
    }

    @Test
    void productionCodeAndResourcesDoNotReferenceRideSpecificFlightId() throws IOException {
        String staleId = "Tamework" + "RideFly";
        assertFalse(anyFileContains(Paths.get("src", "main", "java"), staleId));
        assertFalse(anyFileContains(Paths.get("src", "main", "resources"), staleId));
    }

    @Test
    void rideCleanupClearsForcedMovementAnimationAndRestoresState() throws IOException {
        String mountCleanup = readMain(
                "com",
                "alechilles",
                "alecstamework",
                "npc",
                "systems",
                "MountedRideCleanupSystem.java"
        );
        String riderCleanup = readMain(
                "com",
                "alechilles",
                "alecstamework",
                "npc",
                "systems",
                "MountedRideRiderCleanupSystem.java"
        );
        String inputCapture = readMain(
                "com",
                "alechilles",
                "alecstamework",
                "npc",
                "systems",
                "MountedRideInputCaptureSystem.java"
        );

        assertTrue(mountCleanup.contains("AnimationSlot.Movement"));
        assertTrue(mountCleanup.contains("npc.playAnimation(mountRef, AnimationSlot.Movement, null, store)"));
        assertTrue(riderCleanup.contains("AnimationSlot.Movement"));
        assertTrue(riderCleanup.contains("npc.playAnimation(mountRef, AnimationSlot.Movement, null, store)"));
        assertTrue(riderCleanup.contains("if (riderRef.isValid()) {\n                    MountedRideClientAttachment.placeRiderAtMountAnchor")
                || riderCleanup.contains("if (riderRef.isValid()) {\r\n                    MountedRideClientAttachment.placeRiderAtMountAnchor"));
        assertTrue(inputCapture.contains("getDefaultSubState()"));
        assertFalse(mountCleanup.contains("MountedRideClientAttachment.restoreRiderCollision"));
        assertFalse(riderCleanup.contains("MountedRideClientAttachment.restoreRiderCollision"));
        assertFalse(inputCapture.contains("MountedRideClientAttachment.restoreRiderCollision"));
        assertFalse(mountCleanup.contains("exceedsSanityDistance"));
        assertTrue(mountCleanup.contains("TameworkRide debug: cleanup source=%s"));
        assertTrue(riderCleanup.contains("TameworkRide debug: cleanup source=riderCleanup"));
    }

    @Test
    void mountOwnerSanityDoesNotClearOwnersBeforeVanillaMountOnAdd() throws IOException {
        String ownerSanity = readMain(
                "com",
                "alechilles",
                "alecstamework",
                "npc",
                "systems",
                "MountedOwnerReferenceSanitySystem.java"
        );
        String teleportSafety = readMain(
                "com",
                "alechilles",
                "alecstamework",
                "npc",
                "systems",
                "MountedNpcTeleportSafetySystem.java"
        );

        assertFalse(ownerSanity.contains("getMountEntityId()"));
        assertTrue(ownerSanity.contains("return safeGetComponent(store, ownerRef, playerType) != null;"));
        assertTrue(teleportSafety.contains("if (ownerPlayer.getMountEntityId() == 0) {\n            return;\n        }")
                || teleportSafety.contains("if (ownerPlayer.getMountEntityId() == 0) {\r\n            return;\r\n        }"));
        assertTrue(teleportSafety.indexOf("if (ownerPlayer.getMountEntityId() == 0)")
                < teleportSafety.indexOf("MountPlugin.checkDismountNpc(commandBuffer, ownerRef, ownerPlayer)"));
    }

    private static String readMain(String first, String... more) throws IOException {
        return Files.readString(MAIN_JAVA.resolve(Paths.get(first, more)), StandardCharsets.UTF_8);
    }

    private static boolean anyFileContains(Path root, String value) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .anyMatch(path -> contains(path, value));
        }
    }

    private static boolean contains(Path path, String value) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).contains(value);
        } catch (MalformedInputException e) {
            return false;
        } catch (IOException e) {
            throw new IllegalStateException("Failed reading " + path, e);
        }
    }

    private static String methodBody(String content, String startMarker, String endMarker) {
        int start = content.indexOf(startMarker);
        int end = content.indexOf(endMarker);
        assertTrue(start >= 0, "missing start marker " + startMarker);
        assertTrue(end > start, "missing end marker " + endMarker);
        return content.substring(start, end);
    }
}
