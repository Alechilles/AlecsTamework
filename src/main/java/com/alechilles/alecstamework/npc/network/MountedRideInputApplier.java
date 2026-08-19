package com.alechilles.alecstamework.npc.network;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.alechilles.alecstamework.npc.movement.TameworkRideVelocityIntent;
import com.alechilles.alecstamework.npc.systems.MountedRideInputProbeLogger;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/** Applies one coalesced mounted-input batch without owning scheduling or component writes. */
final class MountedRideInputApplier {
    private static final float MOUSE_LOOK_RADIANS_PER_UNIT = 0.0025f;
    private static final float MIN_CAMERA_PITCH = (float) -Math.toRadians(85.0);
    private static final float MAX_CAMERA_PITCH = (float) Math.toRadians(85.0);
    private static final double MAX_VELOCITY_LOOK_YAW_DELTA = Math.toRadians(25.0);
    private static final double VELOCITY_LOOK_FORWARD_DEAD_ZONE = 0.25;
    private static final double VELOCITY_LOOK_FORWARD_DOMINANCE = 1.35;
    private static final double VELOCITY_BRAKE_BACKWARD_DEAD_ZONE = -0.25;
    private static final double VELOCITY_BRAKE_BACKWARD_DOMINANCE = 1.35;

    private long lastClientMovementDebugMs;
    private long lastMountMovementDebugMs;
    private long lastMouseMovementDebugMs;

    /** Applies client, mount, and mouse snapshots in packet order. */
    boolean apply(@Nonnull MountedRideInputMailbox.Batch batch,
                  @Nonnull Ref<EntityStore> mountRef,
                  @Nonnull Store<EntityStore> store,
                  @Nonnull TameworkRideMountComponent mount) {
        boolean capturedInput = false;
        if (batch.clientMovement() != null) {
            capturedInput |= applyClientMovement(batch.clientMovement(), mount);
        }
        if (batch.mountMovement() != null) {
            capturedInput |= applyMountMovement(batch.mountMovement(), mountRef, store, mount);
        }
        if (batch.mouseInteraction() != null) {
            capturedInput |= applyMouseInteraction(batch.mouseInteraction(), mount);
        }
        return capturedInput;
    }

    private boolean applyClientMovement(@Nonnull MountedRideInputMailbox.ClientMovementSnapshot input,
                                        @Nonnull TameworkRideMountComponent mount) {
        boolean capturedPacketInput = input.hasMovementStates() || input.hasRiderMovementStates();
        boolean capturedMovementIntent = false;
        if (input.hasRiderMovementStates()) {
            captureStates(mount, true, input.riderMovementStatesMask());
        } else {
            captureStates(mount, input.hasMovementStates(), input.movementStatesMask());
        }
        if (input.hasBodyOrientation()) {
            mount.captureBodyRotation(input.bodyYaw(), input.bodyPitch(), input.bodyRoll());
            capturedPacketInput = true;
        }
        if (input.hasLookOrientation()) {
            mount.captureHeadRotation(input.lookYaw(), input.lookPitch(), input.lookRoll());
            capturedPacketInput = true;
        }
        if (input.hasWishMovement()) {
            captureWish(mount, input.wishX(), input.wishY(), input.wishZ());
            capturedMovementIntent = true;
            capturedPacketInput = true;
        } else if (input.hasVelocity()) {
            captureVelocityMovementIntent(mount, input.velocityX(), input.velocityY(), input.velocityZ());
            capturedMovementIntent = true;
            capturedPacketInput = true;
        }
        MountedRideInputProbeLogger.logClientMovement(
                mount,
                input.hasWishMovement(), input.wishX(), input.wishY(), input.wishZ(),
                input.hasVelocity(), input.velocityX(), input.velocityY(), input.velocityZ(),
                input.hasMovementStates(), input.movementState(MountedRideInputMailbox.STATE_JUMPING),
                input.movementState(MountedRideInputMailbox.STATE_CROUCHING),
                input.movementState(MountedRideInputMailbox.STATE_SPRINTING),
                input.movementState(MountedRideInputMailbox.STATE_FLYING),
                input.movementState(MountedRideInputMailbox.STATE_MOUNTING),
                input.movementState(MountedRideInputMailbox.STATE_ON_GROUND),
                input.hasRiderMovementStates(), input.riderMovementState(MountedRideInputMailbox.STATE_JUMPING),
                input.riderMovementState(MountedRideInputMailbox.STATE_CROUCHING),
                input.riderMovementState(MountedRideInputMailbox.STATE_SPRINTING),
                input.riderMovementState(MountedRideInputMailbox.STATE_FLYING),
                input.riderMovementState(MountedRideInputMailbox.STATE_MOUNTING),
                input.riderMovementState(MountedRideInputMailbox.STATE_ON_GROUND),
                capturedMovementIntent
        );
        logClientMovementDebug(input, mount, capturedMovementIntent);
        return capturedPacketInput;
    }

    private boolean applyMountMovement(@Nonnull MountedRideInputMailbox.MountMovementSnapshot input,
                                        @Nonnull Ref<EntityStore> mountRef,
                                        @Nonnull Store<EntityStore> store,
                                        @Nonnull TameworkRideMountComponent mount) {
        if (input.hasBodyOrientation() && !mount.hasHeadRotation()) {
            mount.captureBodyRotation(input.bodyYaw(), input.bodyPitch(), input.bodyRoll());
        }
        captureStates(mount, input.hasMovementStates(), input.movementStatesMask());
        if (!mount.hasWishMovement() && input.hasAbsolutePosition()) {
            captureAbsoluteMovementFromMount(
                    mountRef, store, mount,
                    input.absoluteX(), input.absoluteY(), input.absoluteZ()
            );
        }
        logMountMovementDebug(input, mount);
        return true;
    }

    private boolean applyMouseInteraction(@Nonnull MountedRideInputMailbox.MouseInteractionSnapshot input,
                                           @Nonnull TameworkRideMountComponent mount) {
        if (input.hasRelativeMotion()) {
            captureMouseLook(mount, input.relativeMotionX(), input.relativeMotionY());
        }
        logMouseMovementDebug(input, mount);
        return input.hasRelativeMotion();
    }

    private void captureStates(@Nonnull TameworkRideMountComponent mount, boolean present, int mask) {
        mount.setRiderJumping(
                MountedRideInputMailbox.hasMovementState(present, mask, MountedRideInputMailbox.STATE_JUMPING)
                        || MountedRideInputMailbox.hasMovementState(present, mask, MountedRideInputMailbox.STATE_SWIM_JUMPING)
        );
        mount.setRiderCrouching(
                MountedRideInputMailbox.hasMovementState(present, mask, MountedRideInputMailbox.STATE_CROUCHING)
                        || MountedRideInputMailbox.hasMovementState(present, mask, MountedRideInputMailbox.STATE_FORCED_CROUCHING)
        );
        mount.setRiderFlying(
                MountedRideInputMailbox.hasMovementState(present, mask, MountedRideInputMailbox.STATE_FLYING)
        );
        mount.setRiderSprinting(
                MountedRideInputMailbox.hasMovementState(present, mask, MountedRideInputMailbox.STATE_SPRINTING)
                        || MountedRideInputMailbox.hasMovementState(present, mask, MountedRideInputMailbox.STATE_RUNNING)
        );
    }

    private void captureWish(@Nonnull TameworkRideMountComponent mount,
                             double wishX,
                             double wishY,
                             double wishZ) {
        double length = Math.sqrt(wishX * wishX + wishZ * wishZ);
        if (length <= 0.0001) {
            mount.clearWishMovement();
            return;
        }
        double scale = length > 1.0 ? 1.0 / length : 1.0;
        mount.captureWishMovement(wishX * scale, 0.0, wishZ * scale, wishZ < -0.0001);
    }

    private void captureAbsoluteMovementFromMount(@Nonnull Ref<EntityStore> mountRef,
                                                  @Nonnull Store<EntityStore> store,
                                                  @Nonnull TameworkRideMountComponent mount,
                                                  double absoluteX,
                                                  double absoluteY,
                                                  double absoluteZ) {
        TransformComponent transform = store.getComponent(mountRef, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }
        captureWorldMovement(
                mount,
                absoluteX - transform.getPosition().x,
                absoluteY - transform.getPosition().y,
                absoluteZ - transform.getPosition().z,
                true
        );
    }

    private void captureWorldMovement(@Nonnull TameworkRideMountComponent mount,
                                      double worldX,
                                      double worldY,
                                      double worldZ,
                                      boolean normalizeIntent) {
        MovementIntent intent = projectWorldMovement(mount, worldX, worldZ, normalizeIntent);
        if (intent == null) {
            mount.clearWishMovement();
            return;
        }
        captureProjectedIntent(mount, intent);
    }

    private void captureVelocityMovementIntent(@Nonnull TameworkRideMountComponent mount,
                                               double worldX,
                                               double worldY,
                                               double worldZ) {
        if (TameworkRideVelocityIntent.isVerticalDominant(worldX, worldY, worldZ)) {
            captureVerticalVelocityIntent(mount, worldX, worldY, worldZ);
            return;
        }
        if (!TameworkRideVelocityIntent.hasUsableHorizontalIntent(worldX, worldZ)) {
            mount.clearWishMovement();
            return;
        }
        MovementIntent intent = projectWorldMovement(mount, worldX, worldZ, true);
        if (intent == null) {
            mount.clearWishMovement();
            return;
        }
        if (isBackwardBrakeIntent(intent)) {
            captureBackwardBrakeIntent(mount);
            return;
        }
        if (shouldPreserveExistingForwardIntent(mount, intent)) {
            captureExistingForwardIntent(mount);
            return;
        }
        if (!isForwardDominant(intent)) {
            captureProjectedIntent(mount, intent);
            return;
        }
        if (captureForwardLookFromWorldVector(mount, worldX, worldY, worldZ)) {
            MovementIntent updatedIntent = projectWorldMovement(mount, worldX, worldZ, true);
            if (updatedIntent != null) {
                intent = updatedIntent;
            }
        }
        captureProjectedIntent(mount, intent);
    }

    private void captureProjectedIntent(@Nonnull TameworkRideMountComponent mount,
                                        @Nonnull MovementIntent intent) {
        mount.captureWishMovement(intent.strafe(), 0.0, intent.forward());
    }

    private void captureBackwardBrakeIntent(@Nonnull TameworkRideMountComponent mount) {
        mount.captureWishMovement(0.0, 0.0, 0.0, true);
    }

    private void captureVerticalVelocityIntent(@Nonnull TameworkRideMountComponent mount,
                                               double worldX,
                                               double worldY,
                                               double worldZ) {
        mount.captureWishMovement(0.0, TameworkRideVelocityIntent.verticalInput(worldX, worldY, worldZ), 0.0);
    }

    private void captureExistingForwardIntent(@Nonnull TameworkRideMountComponent mount) {
        mount.captureWishMovement(0.0, mount.getWishY(), Math.copySign(1.0, mount.getWishZ()));
    }

    private MovementIntent projectWorldMovement(@Nonnull TameworkRideMountComponent mount,
                                                double worldX,
                                                double worldZ,
                                                boolean normalizeIntent) {
        double horizontalLength = Math.sqrt(worldX * worldX + worldZ * worldZ);
        if (horizontalLength <= 0.0001) {
            return null;
        }
        double normalizedX;
        double normalizedZ;
        if (normalizeIntent) {
            normalizedX = worldX / horizontalLength;
            normalizedZ = worldZ / horizontalLength;
        } else {
            normalizedX = horizontalLength > 1.0 ? worldX / horizontalLength : worldX;
            normalizedZ = horizontalLength > 1.0 ? worldZ / horizontalLength : worldZ;
        }
        double yaw = mount.hasHeadRotation()
                ? mount.getHeadYaw()
                : mount.hasBodyRotation() ? mount.getBodyYaw() : 0.0;
        double forwardX = -Math.sin(yaw);
        double forwardZ = -Math.cos(yaw);
        double rightX = -Math.sin(yaw - Math.PI / 2.0);
        double rightZ = -Math.cos(yaw - Math.PI / 2.0);
        double strafe = clamp(normalizedX * rightX + normalizedZ * rightZ, -1.0, 1.0);
        double forward = clamp(normalizedX * forwardX + normalizedZ * forwardZ, -1.0, 1.0);
        return new MovementIntent(strafe, forward);
    }

    private boolean isForwardDominant(@Nonnull MovementIntent intent) {
        return intent.forward() > VELOCITY_LOOK_FORWARD_DEAD_ZONE
                && Math.abs(intent.forward()) >= Math.abs(intent.strafe()) * VELOCITY_LOOK_FORWARD_DOMINANCE;
    }

    private boolean isBackwardBrakeIntent(@Nonnull MovementIntent intent) {
        return intent.forward() < VELOCITY_BRAKE_BACKWARD_DEAD_ZONE
                && Math.abs(intent.forward()) >= Math.abs(intent.strafe()) * VELOCITY_BRAKE_BACKWARD_DOMINANCE;
    }

    private boolean shouldPreserveExistingForwardIntent(@Nonnull TameworkRideMountComponent mount,
                                                        @Nonnull MovementIntent intent) {
        if (!mount.hasWishMovement() || Math.abs(mount.getWishZ()) < 0.75) {
            return false;
        }
        return Math.abs(intent.strafe()) > 0.65 && Math.abs(intent.forward()) < 0.35;
    }

    private void captureMouseLook(@Nonnull TameworkRideMountComponent mount, int relativeX, int relativeY) {
        if (relativeX == 0 && relativeY == 0) {
            return;
        }
        float yaw = mount.hasHeadRotation()
                ? mount.getHeadYaw()
                : mount.hasBodyRotation() ? mount.getBodyYaw() : mount.getAuthoritativeYaw();
        float pitch = mount.hasHeadRotation()
                ? mount.getHeadPitch()
                : mount.hasBodyRotation() ? mount.getBodyPitch() : mount.getAuthoritativePitch();
        yaw = normalizeAngle(yaw - relativeX * MOUSE_LOOK_RADIANS_PER_UNIT);
        pitch = clampFloat(pitch - relativeY * MOUSE_LOOK_RADIANS_PER_UNIT, MIN_CAMERA_PITCH, MAX_CAMERA_PITCH);
        mount.captureHeadRotation(yaw, pitch, 0.0f);
    }

    private boolean captureForwardLookFromWorldVector(@Nonnull TameworkRideMountComponent mount,
                                                      double worldX,
                                                      double worldY,
                                                      double worldZ) {
        double horizontalLength = Math.sqrt(worldX * worldX + worldZ * worldZ);
        double length = Math.sqrt(horizontalLength * horizontalLength + worldY * worldY);
        if (length <= 0.05 || horizontalLength <= 0.0001) {
            return false;
        }
        float yaw = normalizeAngle((float) Math.atan2(-worldX, -worldZ));
        float pitch = clampFloat((float) -Math.atan2(worldY, horizontalLength), MIN_CAMERA_PITCH, MAX_CAMERA_PITCH);
        if (mount.hasHeadRotation() || mount.hasBodyRotation() || mount.hasAuthoritativePose()) {
            float currentYaw = mount.hasHeadRotation()
                    ? mount.getHeadYaw()
                    : mount.hasBodyRotation() ? mount.getBodyYaw() : mount.getAuthoritativeYaw();
            if (Math.abs(normalizeAngle(yaw - currentYaw)) > MAX_VELOCITY_LOOK_YAW_DELTA) {
                return false;
            }
        }
        mount.captureHeadRotation(yaw, pitch, 0.0f);
        return true;
    }

    private void logClientMovementDebug(@Nonnull MountedRideInputMailbox.ClientMovementSnapshot input,
                                        @Nonnull TameworkRideMountComponent mount,
                                        boolean capturedMovementIntent) {
        long now = System.currentTimeMillis();
        if (now - lastClientMovementDebugMs < 1000) {
            return;
        }
        lastClientMovementDebugMs = now;
        logDebug(
                "TameworkRide debug: packet source=clientMovement capturedMovement=%s packetWish=%s "
                        + "packetVelocity=%s body=%s look=%s movementStates=%s riderStates=%s "
                        + "snapshotWish=%s/%s/%s hasWish=%s snapshotBody=%s/%s hasBody=%s "
                        + "snapshotHead=%s/%s hasHead=%s riderJump=%s riderCrouch=%s",
                capturedMovementIntent,
                formatTriple(input.hasWishMovement(), input.wishX(), input.wishY(), input.wishZ()),
                formatTriple(input.hasVelocity(), input.velocityX(), input.velocityY(), input.velocityZ()),
                formatDirection(input.hasBodyOrientation(), input.bodyYaw(), input.bodyPitch(), input.bodyRoll()),
                formatDirection(input.hasLookOrientation(), input.lookYaw(), input.lookPitch(), input.lookRoll()),
                formatStates(input.hasMovementStates(), input.movementStatesMask()),
                formatStates(input.hasRiderMovementStates(), input.riderMovementStatesMask()),
                mount.getWishX(), mount.getWishY(), mount.getWishZ(), mount.hasWishMovement(),
                mount.getBodyYaw(), mount.getBodyPitch(), mount.hasBodyRotation(),
                mount.getHeadYaw(), mount.getHeadPitch(), mount.hasHeadRotation(),
                mount.isRiderJumping(), mount.isRiderCrouching()
        );
    }

    private void logMountMovementDebug(@Nonnull MountedRideInputMailbox.MountMovementSnapshot input,
                                       @Nonnull TameworkRideMountComponent mount) {
        long now = System.currentTimeMillis();
        if (now - lastMountMovementDebugMs < 1000) {
            return;
        }
        lastMountMovementDebugMs = now;
        logDebug(
                "TameworkRide debug: packet source=mountMovement absolute=%s body=%s movementStates=%s "
                        + "snapshotWish=%s/%s/%s hasWish=%s snapshotBody=%s/%s hasBody=%s "
                        + "snapshotHead=%s/%s hasHead=%s",
                formatTriple(input.hasAbsolutePosition(), input.absoluteX(), input.absoluteY(), input.absoluteZ()),
                formatDirection(input.hasBodyOrientation(), input.bodyYaw(), input.bodyPitch(), input.bodyRoll()),
                formatStates(input.hasMovementStates(), input.movementStatesMask()),
                mount.getWishX(), mount.getWishY(), mount.getWishZ(), mount.hasWishMovement(),
                mount.getBodyYaw(), mount.getBodyPitch(), mount.hasBodyRotation(),
                mount.getHeadYaw(), mount.getHeadPitch(), mount.hasHeadRotation()
        );
    }

    private void logMouseMovementDebug(@Nonnull MountedRideInputMailbox.MouseInteractionSnapshot input,
                                       @Nonnull TameworkRideMountComponent mount) {
        long now = System.currentTimeMillis();
        if (now - lastMouseMovementDebugMs < 1000) {
            return;
        }
        lastMouseMovementDebugMs = now;
        logDebug(
                "TameworkRide debug: packet source=mouseInteraction motion=%s snapshotHead=%s/%s hasHead=%s",
                input.hasRelativeMotion() ? input.relativeMotionX() + "/" + input.relativeMotionY() : "<none>",
                mount.getHeadYaw(), mount.getHeadPitch(), mount.hasHeadRotation()
        );
    }

    private String formatTriple(boolean present, double x, double y, double z) {
        return present ? x + "/" + y + "/" + z : "<none>";
    }

    private String formatDirection(boolean present, float yaw, float pitch, float roll) {
        return present ? yaw + "/" + pitch + "/" + roll : "<none>";
    }

    private String formatStates(boolean present, int mask) {
        if (!present) {
            return "<none>";
        }
        return "jump=" + MountedRideInputMailbox.hasMovementState(present, mask, MountedRideInputMailbox.STATE_JUMPING)
                + ",crouch=" + MountedRideInputMailbox.hasMovementState(present, mask, MountedRideInputMailbox.STATE_CROUCHING)
                + ",fly=" + MountedRideInputMailbox.hasMovementState(present, mask, MountedRideInputMailbox.STATE_FLYING)
                + ",sprint=" + MountedRideInputMailbox.hasMovementState(present, mask, MountedRideInputMailbox.STATE_SPRINTING)
                + ",ground=" + MountedRideInputMailbox.hasMovementState(present, mask, MountedRideInputMailbox.STATE_ON_GROUND)
                + ",run=" + MountedRideInputMailbox.hasMovementState(present, mask, MountedRideInputMailbox.STATE_RUNNING)
                + ",mounting=" + MountedRideInputMailbox.hasMovementState(present, mask, MountedRideInputMailbox.STATE_MOUNTING);
    }

    private void logDebug(@Nonnull String message, Object... args) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugRideEnabled() || instance.getLogger() == null) {
            return;
        }
        instance.getLogger().at(Level.INFO).log(String.format(message, args));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float normalizeAngle(float angle) {
        while (angle <= -Math.PI) {
            angle += Math.PI * 2.0f;
        }
        while (angle > Math.PI) {
            angle -= Math.PI * 2.0f;
        }
        return angle;
    }

    private record MovementIntent(double strafe, double forward) {
    }
}
