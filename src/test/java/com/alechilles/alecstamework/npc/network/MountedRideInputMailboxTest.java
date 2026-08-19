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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MountedRideInputMailboxTest {
    @Test
    void firstOfferClaimsOneScheduleAndLaterOffersDoNot() {
        MountedRideInputMailbox mailbox = new MountedRideInputMailbox();
        MountedRideInputMailbox.ClientMovementSnapshot client = client(1.0);
        MountedRideInputMailbox.MountMovementSnapshot mount = mount(2.0);

        assertTrue(mailbox.offerClientMovement(client));
        assertFalse(mailbox.offerMountMovement(mount));

        MountedRideInputMailbox.Batch batch = mailbox.takeBatch();
        assertEquals(client, batch.clientMovement());
        assertEquals(mount, batch.mountMovement());
        assertFalse(mailbox.completeDrain());
    }

    @Test
    void repeatedOffersKeepTheLatestSnapshotForEachPacketType() {
        MountedRideInputMailbox mailbox = new MountedRideInputMailbox();
        MountedRideInputMailbox.ClientMovementSnapshot firstClient = client(1.0);
        MountedRideInputMailbox.ClientMovementSnapshot latestClient = client(3.0);
        MountedRideInputMailbox.MountMovementSnapshot firstMount = mount(2.0);
        MountedRideInputMailbox.MountMovementSnapshot latestMount = mount(4.0);
        MountedRideInputMailbox.MouseInteractionSnapshot firstMouse = mouse(5, 6);
        MountedRideInputMailbox.MouseInteractionSnapshot latestMouse = mouse(7, 8);

        assertTrue(mailbox.offerClientMovement(firstClient));
        assertFalse(mailbox.offerClientMovement(latestClient));
        assertFalse(mailbox.offerMountMovement(firstMount));
        assertFalse(mailbox.offerMountMovement(latestMount));
        assertFalse(mailbox.offerMouseInteraction(firstMouse));
        assertFalse(mailbox.offerMouseInteraction(latestMouse));

        MountedRideInputMailbox.Batch batch = mailbox.takeBatch();
        assertEquals(latestClient, batch.clientMovement());
        assertEquals(latestMount, batch.mountMovement());
        assertEquals(latestMouse, batch.mouseInteraction());
        assertFalse(mailbox.completeDrain());
    }

    @Test
    void offerDuringDrainCausesExactlyOneFollowUpClaim() {
        MountedRideInputMailbox mailbox = new MountedRideInputMailbox();

        assertTrue(mailbox.offerClientMovement(client(1.0)));
        assertEquals(client(1.0), mailbox.takeBatch().clientMovement());
        assertFalse(mailbox.offerMountMovement(mount(2.0)));
        assertFalse(mailbox.offerMouseInteraction(mouse(3, 4)));
        assertTrue(mailbox.completeDrain());

        MountedRideInputMailbox.Batch followUp = mailbox.takeBatch();
        assertEquals(mount(2.0), followUp.mountMovement());
        assertEquals(mouse(3, 4), followUp.mouseInteraction());
        assertFalse(mailbox.completeDrain());
    }

    @Test
    void completionWithoutNewInputReleasesTheSchedulingClaim() {
        MountedRideInputMailbox mailbox = new MountedRideInputMailbox();

        assertTrue(mailbox.offerClientMovement(client(1.0)));
        mailbox.takeBatch();
        assertFalse(mailbox.completeDrain());
        assertTrue(mailbox.offerClientMovement(client(2.0)));
    }

    @Test
    void invalidationClearsPendingInputAndRejectsLaterOffers() {
        MountedRideInputMailbox mailbox = new MountedRideInputMailbox();

        assertTrue(mailbox.offerClientMovement(client(1.0)));
        mailbox.invalidate();

        assertFalse(mailbox.offerClientMovement(client(2.0)));
        assertFalse(mailbox.offerMountMovement(mount(3.0)));
        assertFalse(mailbox.offerMouseInteraction(mouse(4, 5)));
        assertTrue(mailbox.takeBatch().isEmpty());
        assertFalse(mailbox.completeDrain());
    }

    @Test
    void clientConversionCopiesScalarsAndSeparateMovementStateMasks() {
        ClientMovement packet = new ClientMovement();
        packet.bodyOrientation = new Direction(1.0f, 2.0f, 3.0f);
        packet.lookOrientation = new Direction(4.0f, 5.0f, 6.0f);
        packet.wishMovement = new Position(7.0, 8.0, 9.0);
        packet.velocity = new Vector3d(10.0, 11.0, 12.0);
        packet.movementStates = states(true, false, true, false, true, false, true, false, true);
        packet.riderMovementStates = states(false, true, false, true, false, true, false, true, false);

        MountedRideInputMailbox.ClientMovementSnapshot snapshot =
                MountedRideInputMailbox.ClientMovementSnapshot.from(packet);

        packet.bodyOrientation.yaw = 20.0f;
        packet.lookOrientation.pitch = 21.0f;
        packet.wishMovement.x = 22.0;
        packet.velocity.z = 23.0;
        packet.movementStates.jumping = false;
        packet.riderMovementStates.swimJumping = false;

        assertTrue(snapshot.hasBodyOrientation());
        assertEquals(1.0f, snapshot.bodyYaw());
        assertEquals(5.0f, snapshot.lookPitch());
        assertEquals(7.0, snapshot.wishX());
        assertEquals(12.0, snapshot.velocityZ());
        assertTrue(snapshot.hasMovementStates());
        assertEquals(
                MountedRideInputMailbox.STATE_JUMPING
                        | MountedRideInputMailbox.STATE_CROUCHING
                        | MountedRideInputMailbox.STATE_FLYING
                        | MountedRideInputMailbox.STATE_RUNNING
                        | MountedRideInputMailbox.STATE_ON_GROUND,
                snapshot.movementStatesMask()
        );
        assertTrue(snapshot.movementState(MountedRideInputMailbox.STATE_JUMPING));
        assertTrue(snapshot.movementState(MountedRideInputMailbox.STATE_CROUCHING));
        assertTrue(snapshot.movementState(MountedRideInputMailbox.STATE_FLYING));
        assertTrue(snapshot.movementState(MountedRideInputMailbox.STATE_RUNNING));
        assertTrue(snapshot.movementState(MountedRideInputMailbox.STATE_ON_GROUND));
        assertTrue(snapshot.hasRiderMovementStates());
        assertEquals(
                MountedRideInputMailbox.STATE_SWIM_JUMPING
                        | MountedRideInputMailbox.STATE_FORCED_CROUCHING
                        | MountedRideInputMailbox.STATE_SPRINTING
                        | MountedRideInputMailbox.STATE_MOUNTING,
                snapshot.riderMovementStatesMask()
        );
        assertTrue(snapshot.riderMovementState(MountedRideInputMailbox.STATE_SWIM_JUMPING));
        assertTrue(snapshot.riderMovementState(MountedRideInputMailbox.STATE_FORCED_CROUCHING));
        assertTrue(snapshot.riderMovementState(MountedRideInputMailbox.STATE_SPRINTING));
        assertTrue(snapshot.riderMovementState(MountedRideInputMailbox.STATE_MOUNTING));
    }

    @Test
    void mountConversionCopiesPositionBodyAndStateMask() {
        MountMovement packet = new MountMovement();
        packet.absolutePosition = new Position(1.0, 2.0, 3.0);
        packet.bodyOrientation = new Direction(4.0f, 5.0f, 6.0f);
        packet.movementStates = states(true, true, false, false, false, false, false, true, true);

        MountedRideInputMailbox.MountMovementSnapshot snapshot =
                MountedRideInputMailbox.MountMovementSnapshot.from(packet);

        packet.absolutePosition.z = 30.0;
        packet.bodyOrientation.roll = 60.0f;
        packet.movementStates.mounting = false;

        assertTrue(snapshot.hasAbsolutePosition());
        assertEquals(3.0, snapshot.absoluteZ());
        assertEquals(6.0f, snapshot.bodyRoll());
        assertTrue(snapshot.hasMovementStates());
        assertEquals(
                MountedRideInputMailbox.STATE_JUMPING
                        | MountedRideInputMailbox.STATE_SWIM_JUMPING
                        | MountedRideInputMailbox.STATE_MOUNTING
                        | MountedRideInputMailbox.STATE_ON_GROUND,
                snapshot.movementStatesMask()
        );
        assertTrue(snapshot.movementState(MountedRideInputMailbox.STATE_JUMPING));
        assertTrue(snapshot.movementState(MountedRideInputMailbox.STATE_SWIM_JUMPING));
        assertTrue(snapshot.movementState(MountedRideInputMailbox.STATE_MOUNTING));
        assertTrue(snapshot.movementState(MountedRideInputMailbox.STATE_ON_GROUND));
    }

    @Test
    void mouseConversionCopiesRelativeMotionAndPresence() {
        MouseInteraction packet = new MouseInteraction();
        packet.mouseMotion = new MouseMotionEvent();
        packet.mouseMotion.relativeMotion = new Vector2i(7, -8);

        MountedRideInputMailbox.MouseInteractionSnapshot snapshot =
                MountedRideInputMailbox.MouseInteractionSnapshot.from(packet);

        packet.mouseMotion.relativeMotion.x = 70;
        packet.mouseMotion.relativeMotion.y = -80;
        packet.mouseMotion = null;

        assertTrue(snapshot.hasMouseMotion());
        assertTrue(snapshot.hasRelativeMotion());
        assertEquals(7, snapshot.relativeMotionX());
        assertEquals(-8, snapshot.relativeMotionY());
    }

    @Test
    void absentPacketFieldsReportFalsePresenceAndZeroScalars() {
        MountedRideInputMailbox.ClientMovementSnapshot client =
                MountedRideInputMailbox.ClientMovementSnapshot.from(new ClientMovement());
        assertFalse(client.hasBodyOrientation());
        assertFalse(client.hasLookOrientation());
        assertFalse(client.hasWishMovement());
        assertFalse(client.hasVelocity());
        assertEquals(0.0f, client.bodyYaw());
        assertEquals(0.0f, client.bodyPitch());
        assertEquals(0.0f, client.bodyRoll());
        assertEquals(0.0f, client.lookYaw());
        assertEquals(0.0f, client.lookPitch());
        assertEquals(0.0f, client.lookRoll());
        assertEquals(0.0, client.wishX());
        assertEquals(0.0, client.wishY());
        assertEquals(0.0, client.wishZ());
        assertEquals(0.0, client.velocityX());
        assertEquals(0.0, client.velocityY());
        assertEquals(0.0, client.velocityZ());
        assertFalse(client.hasMovementStates());
        assertEquals(0, client.movementStatesMask());
        assertFalse(client.hasRiderMovementStates());
        assertEquals(0, client.riderMovementStatesMask());

        MountMovement absentMountPacket = new MountMovement();
        absentMountPacket.absolutePosition = null;
        absentMountPacket.bodyOrientation = null;
        absentMountPacket.movementStates = null;
        MountedRideInputMailbox.MountMovementSnapshot mount =
                MountedRideInputMailbox.MountMovementSnapshot.from(absentMountPacket);
        assertFalse(mount.hasAbsolutePosition());
        assertEquals(0.0, mount.absoluteX());
        assertEquals(0.0, mount.absoluteY());
        assertEquals(0.0, mount.absoluteZ());
        assertFalse(mount.hasBodyOrientation());
        assertEquals(0.0f, mount.bodyYaw());
        assertEquals(0.0f, mount.bodyPitch());
        assertEquals(0.0f, mount.bodyRoll());
        assertFalse(mount.hasMovementStates());
        assertEquals(0, mount.movementStatesMask());

        MountedRideInputMailbox.MouseInteractionSnapshot mouse =
                MountedRideInputMailbox.MouseInteractionSnapshot.from(new MouseInteraction());
        assertFalse(mouse.hasMouseMotion());
        assertFalse(mouse.hasRelativeMotion());
        assertEquals(0, mouse.relativeMotionX());
        assertEquals(0, mouse.relativeMotionY());
    }

    private static MovementStates states(boolean jumping,
                                         boolean swimJumping,
                                         boolean crouching,
                                         boolean forcedCrouching,
                                         boolean flying,
                                         boolean sprinting,
                                         boolean running,
                                         boolean mounting,
                                         boolean onGround) {
        MovementStates states = new MovementStates();
        states.jumping = jumping;
        states.swimJumping = swimJumping;
        states.crouching = crouching;
        states.forcedCrouching = forcedCrouching;
        states.flying = flying;
        states.sprinting = sprinting;
        states.running = running;
        states.mounting = mounting;
        states.onGround = onGround;
        return states;
    }

    private static MountedRideInputMailbox.ClientMovementSnapshot client(double wishX) {
        return new MountedRideInputMailbox.ClientMovementSnapshot(
                true,
                0.1f,
                0.2f,
                0.3f,
                true,
                0.4f,
                0.5f,
                0.6f,
                true,
                wishX,
                0.0,
                -1.0,
                true,
                2.0,
                3.0,
                4.0,
                false,
                0,
                false,
                0
        );
    }

    private static MountedRideInputMailbox.MountMovementSnapshot mount(double absoluteX) {
        return new MountedRideInputMailbox.MountMovementSnapshot(
                true,
                absoluteX,
                2.0,
                3.0,
                true,
                0.7f,
                0.8f,
                0.9f,
                false,
                0
        );
    }

    private static MountedRideInputMailbox.MouseInteractionSnapshot mouse(int x, int y) {
        return new MountedRideInputMailbox.MouseInteractionSnapshot(true, true, x, y);
    }
}
