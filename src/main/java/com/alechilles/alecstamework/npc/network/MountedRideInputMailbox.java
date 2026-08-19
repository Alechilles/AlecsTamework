package com.alechilles.alecstamework.npc.network;

import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.protocol.MouseMotionEvent;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.Vector2i;
import com.hypixel.hytale.protocol.Vector3d;
import com.hypixel.hytale.protocol.packets.entities.MountMovement;
import com.hypixel.hytale.protocol.packets.player.ClientMovement;
import com.hypixel.hytale.protocol.packets.player.MouseInteraction;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Coalesces mounted input before a world-thread callback consumes it.
 *
 * <p>The mailbox stores copied scalar snapshots only. It does not know how to schedule work and
 * does not call gameplay code while synchronized. A session owns one mailbox, so a network burst
 * can have at most one queued or running callback and one follow-up callback.</p>
 */
final class MountedRideInputMailbox {
    static final int STATE_JUMPING = 1 << 0;
    static final int STATE_SWIM_JUMPING = 1 << 1;
    static final int STATE_CROUCHING = 1 << 2;
    static final int STATE_FORCED_CROUCHING = 1 << 3;
    static final int STATE_FLYING = 1 << 4;
    static final int STATE_SPRINTING = 1 << 5;
    static final int STATE_RUNNING = 1 << 6;
    static final int STATE_MOUNTING = 1 << 7;
    static final int STATE_ON_GROUND = 1 << 8;

    @Nullable
    private ClientMovementSnapshot pendingClientMovement;
    @Nullable
    private MountMovementSnapshot pendingMountMovement;
    @Nullable
    private MouseInteractionSnapshot pendingMouseInteraction;
    private boolean scheduleClaimed;
    private boolean draining;
    private boolean inputArrivedDuringDrain;
    private boolean invalidated;

    /** Offers the latest client snapshot and reports whether the caller must queue a callback. */
    synchronized boolean offerClientMovement(@Nonnull ClientMovementSnapshot snapshot) {
        if (invalidated) {
            return false;
        }
        pendingClientMovement = snapshot;
        return claimScheduleIfNeeded();
    }

    /** Offers the latest mount snapshot and reports whether the caller must queue a callback. */
    synchronized boolean offerMountMovement(@Nonnull MountMovementSnapshot snapshot) {
        if (invalidated) {
            return false;
        }
        pendingMountMovement = snapshot;
        return claimScheduleIfNeeded();
    }

    /** Offers the latest mouse snapshot and reports whether the caller must queue a callback. */
    synchronized boolean offerMouseInteraction(@Nonnull MouseInteractionSnapshot snapshot) {
        if (invalidated) {
            return false;
        }
        pendingMouseInteraction = snapshot;
        return claimScheduleIfNeeded();
    }

    /** Takes one combined batch and clears only the snapshots that were pending at the call. */
    synchronized Batch takeBatch() {
        if (invalidated) {
            return Batch.EMPTY;
        }
        Batch batch = new Batch(pendingClientMovement, pendingMountMovement, pendingMouseInteraction);
        pendingClientMovement = null;
        pendingMountMovement = null;
        pendingMouseInteraction = null;
        draining = true;
        inputArrivedDuringDrain = false;
        return batch;
    }

    /** Completes the current drain and reports whether one follow-up callback must be queued. */
    synchronized boolean completeDrain() {
        if (invalidated) {
            draining = false;
            inputArrivedDuringDrain = false;
            scheduleClaimed = false;
            return false;
        }
        boolean followUpRequired = draining && inputArrivedDuringDrain;
        draining = false;
        inputArrivedDuringDrain = false;
        scheduleClaimed = followUpRequired;
        return followUpRequired;
    }

    /** Clears all pending input and permanently rejects later offers for this session. */
    synchronized void invalidate() {
        invalidated = true;
        pendingClientMovement = null;
        pendingMountMovement = null;
        pendingMouseInteraction = null;
        scheduleClaimed = false;
        draining = false;
        inputArrivedDuringDrain = false;
    }

    private boolean claimScheduleIfNeeded() {
        if (draining) {
            inputArrivedDuringDrain = true;
        }
        if (scheduleClaimed) {
            return false;
        }
        scheduleClaimed = true;
        return true;
    }

    /** Copies the state bits used by ride behavior and diagnostics into one primitive mask. */
    static int movementStateMask(@Nullable MovementStates states) {
        if (states == null) {
            return 0;
        }
        int mask = 0;
        if (states.jumping) mask |= STATE_JUMPING;
        if (states.swimJumping) mask |= STATE_SWIM_JUMPING;
        if (states.crouching) mask |= STATE_CROUCHING;
        if (states.forcedCrouching) mask |= STATE_FORCED_CROUCHING;
        if (states.flying) mask |= STATE_FLYING;
        if (states.sprinting) mask |= STATE_SPRINTING;
        if (states.running) mask |= STATE_RUNNING;
        if (states.mounting) mask |= STATE_MOUNTING;
        if (states.onGround) mask |= STATE_ON_GROUND;
        return mask;
    }

    static boolean hasMovementState(boolean present, int mask, int state) {
        return present && (mask & state) != 0;
    }

    /** Immutable scalar copy of client movement input used by the ride handler. */
    record ClientMovementSnapshot(
            boolean hasBodyOrientation,
            float bodyYaw,
            float bodyPitch,
            float bodyRoll,
            boolean hasLookOrientation,
            float lookYaw,
            float lookPitch,
            float lookRoll,
            boolean hasWishMovement,
            double wishX,
            double wishY,
            double wishZ,
            boolean hasVelocity,
            double velocityX,
            double velocityY,
            double velocityZ,
            boolean hasMovementStates,
            int movementStatesMask,
            boolean hasRiderMovementStates,
            int riderMovementStatesMask
    ) {
        static ClientMovementSnapshot from(@Nonnull ClientMovement packet) {
            Direction bodyOrientation = packet.bodyOrientation;
            Direction lookOrientation = packet.lookOrientation;
            Position wishMovement = packet.wishMovement;
            Vector3d velocity = packet.velocity;
            MovementStates movementStates = packet.movementStates;
            MovementStates riderMovementStates = packet.riderMovementStates;
            return new ClientMovementSnapshot(
                    bodyOrientation != null,
                    bodyOrientation == null ? 0.0f : bodyOrientation.yaw,
                    bodyOrientation == null ? 0.0f : bodyOrientation.pitch,
                    bodyOrientation == null ? 0.0f : bodyOrientation.roll,
                    lookOrientation != null,
                    lookOrientation == null ? 0.0f : lookOrientation.yaw,
                    lookOrientation == null ? 0.0f : lookOrientation.pitch,
                    lookOrientation == null ? 0.0f : lookOrientation.roll,
                    wishMovement != null,
                    wishMovement == null ? 0.0 : wishMovement.x,
                    wishMovement == null ? 0.0 : wishMovement.y,
                    wishMovement == null ? 0.0 : wishMovement.z,
                    velocity != null,
                    velocity == null ? 0.0 : velocity.x,
                    velocity == null ? 0.0 : velocity.y,
                    velocity == null ? 0.0 : velocity.z,
                    movementStates != null,
                    movementStateMask(movementStates),
                    riderMovementStates != null,
                    movementStateMask(riderMovementStates)
            );
        }

        boolean movementState(int state) {
            return hasMovementState(hasMovementStates, movementStatesMask, state);
        }

        boolean riderMovementState(int state) {
            return hasMovementState(hasRiderMovementStates, riderMovementStatesMask, state);
        }
    }

    /** Immutable scalar copy of mount movement input used by the ride handler. */
    record MountMovementSnapshot(
            boolean hasAbsolutePosition,
            double absoluteX,
            double absoluteY,
            double absoluteZ,
            boolean hasBodyOrientation,
            float bodyYaw,
            float bodyPitch,
            float bodyRoll,
            boolean hasMovementStates,
            int movementStatesMask
    ) {
        static MountMovementSnapshot from(@Nonnull MountMovement packet) {
            Position absolutePosition = packet.absolutePosition;
            Direction bodyOrientation = packet.bodyOrientation;
            MovementStates movementStates = packet.movementStates;
            return new MountMovementSnapshot(
                    absolutePosition != null,
                    absolutePosition == null ? 0.0 : absolutePosition.x,
                    absolutePosition == null ? 0.0 : absolutePosition.y,
                    absolutePosition == null ? 0.0 : absolutePosition.z,
                    bodyOrientation != null,
                    bodyOrientation == null ? 0.0f : bodyOrientation.yaw,
                    bodyOrientation == null ? 0.0f : bodyOrientation.pitch,
                    bodyOrientation == null ? 0.0f : bodyOrientation.roll,
                    movementStates != null,
                    movementStateMask(movementStates)
            );
        }

        boolean movementState(int state) {
            return hasMovementState(hasMovementStates, movementStatesMask, state);
        }
    }

    /** Immutable scalar copy of mouse relative motion used by the ride handler. */
    record MouseInteractionSnapshot(
            boolean hasMouseMotion,
            boolean hasRelativeMotion,
            int relativeMotionX,
            int relativeMotionY
    ) {
        static MouseInteractionSnapshot from(@Nonnull MouseInteraction packet) {
            MouseMotionEvent mouseMotion = packet.mouseMotion;
            Vector2i relativeMotion = mouseMotion == null ? null : mouseMotion.relativeMotion;
            return new MouseInteractionSnapshot(
                    mouseMotion != null,
                    relativeMotion != null,
                    relativeMotion == null ? 0 : relativeMotion.x,
                    relativeMotion == null ? 0 : relativeMotion.y
            );
        }
    }

    /** One immutable batch containing at most the latest snapshot of each packet type. */
    record Batch(
            @Nullable ClientMovementSnapshot clientMovement,
            @Nullable MountMovementSnapshot mountMovement,
            @Nullable MouseInteractionSnapshot mouseInteraction
    ) {
        private static final Batch EMPTY = new Batch(null, null, null);

        boolean isEmpty() {
            return clientMovement == null && mountMovement == null && mouseInteraction == null;
        }
    }
}
