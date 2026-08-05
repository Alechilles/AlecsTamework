package com.alechilles.alecstamework.avatarflight;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.validation.ValidationResults;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies durable mount linkage and recovery snapshots survive ECS cloning. */
class AvatarFlightMountComponentsTest {
    @Test
    void playerSessionClonePreservesPairAndSafeGround() {
        AvatarFlightMountSessionComponent session = new AvatarFlightMountSessionComponent(
                "npc-uuid", "world", "flight-config", 42L);
        session.setPhase(AvatarFlightMountPhase.ACTIVE);
        session.captureOrigin(1.0, 2.0, 3.0, 0.4f);
        session.captureLastSafeGround(4.0, 5.0, 6.0, 0.7f);
        session.setDismountHoldStartedAtMs(99L);

        AvatarFlightMountSessionComponent copy = session.clone();

        assertEquals("npc-uuid", copy.getSourceNpcUuid());
        assertEquals(AvatarFlightMountPhase.ACTIVE, copy.getPhase());
        assertTrue(copy.isLastSafeGroundValid());
        assertEquals(6.0, copy.getLastSafeGroundZ());
        assertEquals(99L, copy.getDismountHoldStartedAtMs());
        assertTrue(AvatarFlightRuntimeEpoch.isCurrent(copy.getRuntimeEpoch()));
    }

    @Test
    void sourceClonePreservesRoleAndVisibilitySnapshot() {
        AvatarFlightSourceComponent source = new AvatarFlightSourceComponent("rider", "Tamed_Drake", 7);
        source.setPreviousState("Idle");
        source.setPreviousSubState("Default");
        source.setPreviousMotionController("Walk");
        source.setWasInteractable(true);
        source.setWasVisible(true);
        source.captureOrigin(1.0, 2.0, 3.0, 0.1f, 0.2f, 0.3f);

        AvatarFlightSourceComponent copy = source.clone();

        assertEquals("rider", copy.getRiderUuid());
        assertEquals("Tamed_Drake", copy.getOriginalRoleId());
        assertEquals(7, copy.getOriginalRoleIndex());
        assertTrue(copy.wasInteractable());
        assertTrue(copy.wasVisible());
        assertEquals(0.3f, copy.getOriginRoll());
        assertTrue(AvatarFlightRuntimeEpoch.isCurrent(copy.getRuntimeEpoch()));
    }

    /** Protects crash recovery for components decoded from saves written by an earlier process. */
    @Test
    void decodedLegacyComponentsAreStaleUntilCreatedByCurrentRuntime() {
        ExtraInfo extraInfo = new ExtraInfo(ExtraInfo.UNSET_VERSION, ValidationResults::new);
        AvatarFlightMountSessionComponent legacySession = AvatarFlightMountSessionComponent.CODEC.decode(
                BsonDocument.parse("{}"), extraInfo);
        AvatarFlightSourceComponent previousSource = AvatarFlightSourceComponent.CODEC.decode(
                BsonDocument.parse("{\"RuntimeEpoch\":\"previous-process\"}"), extraInfo);

        assertFalse(AvatarFlightRuntimeEpoch.isCurrent(legacySession.getRuntimeEpoch()));
        assertFalse(AvatarFlightRuntimeEpoch.isCurrent(previousSource.getRuntimeEpoch()));
        assertFalse(AvatarFlightRuntimeEpoch.isCurrent("previous-process"));
    }

    /**
     * Covers the crash path where disconnect cleanup persisted its in-progress marker before the
     * process died. A new server must resume that cleanup rather than leave the player transformed.
     */
    @Test
    void staleRestoringSessionIsNotTreatedAsActiveCleanup() {
        AvatarFlightMountSessionComponent session = new AvatarFlightMountSessionComponent(
                "npc-uuid", "world", "flight-config", 42L);
        session.setPhase(AvatarFlightMountPhase.RESTORING);
        session.setRuntimeEpoch("previous-process");

        assertFalse(AvatarFlightMountLifecycleService.isRestorationInProgress(session));
    }
}
