package com.alechilles.alecstamework.items;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLinkedNpcCoopServiceTest {

    @Test
    void fallsBackToOwnerMatchWhenToolIdDoesNotMatch() {
        CommandLinkedNpcCoopService service = new CommandLinkedNpcCoopService();
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();

        service.recordCoopSnapshot(
                new CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot(
                        npcUuid,
                        ownerUuid,
                        new String[] {"tool-alpha"},
                        "server.npcRole.test",
                        "Cooped Companion",
                        "coop/chicken_oak",
                        -1,
                        System.currentTimeMillis()
                )
        );

        assertNull(service.getCoopSnapshotForTool(npcUuid, "tool-beta", ownerUuid));
        assertNotNull(service.getCoopSnapshotForToolOrOwner(npcUuid, "tool-beta", ownerUuid));
    }

    @Test
    void ownerFallbackStillRejectsMismatchedOwner() {
        CommandLinkedNpcCoopService service = new CommandLinkedNpcCoopService();
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();

        service.recordCoopSnapshot(
                new CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot(
                        npcUuid,
                        ownerUuid,
                        new String[] {"tool-alpha"},
                        "server.npcRole.test",
                        "Cooped Companion",
                        "coop/chicken_oak",
                        -1,
                        System.currentTimeMillis()
                )
        );

        assertNull(service.getCoopSnapshotForToolOrOwner(npcUuid, "tool-beta", UUID.randomUUID()));
    }

    @Test
    void toolMatchStillWorks() {
        CommandLinkedNpcCoopService service = new CommandLinkedNpcCoopService();
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();

        service.recordCoopSnapshot(
                new CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot(
                        npcUuid,
                        ownerUuid,
                        new String[] {"tool-alpha"},
                        null,
                        "Cooped Companion",
                        "coop/chicken_oak",
                        -1,
                        System.currentTimeMillis()
                )
        );

        assertNotNull(service.getCoopSnapshotForToolOrOwner(npcUuid, "tool-alpha", ownerUuid));
    }

    @Test
    void consumeRespawnSnapshotRemovesDirectUuidMatch() {
        CommandLinkedNpcCoopService service = new CommandLinkedNpcCoopService();
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();

        service.recordCoopSnapshot(
                new CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot(
                        npcUuid,
                        ownerUuid,
                        new String[] {"tool-alpha"},
                        "tamed_chicken",
                        "Cooped Companion",
                        "Coop_Chicken",
                        0,
                        System.currentTimeMillis()
                )
        );

        CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot consumed =
                service.consumeRespawnSnapshotForLinks(npcUuid, ownerUuid, new String[] {"tool-alpha"}, "tamed_chicken");
        assertNotNull(consumed);
        assertEquals(npcUuid, consumed.npcUuid());
        assertNull(service.getCoopSnapshot(npcUuid));
    }

    @Test
    void consumeRespawnSnapshotRemapsUniqueOwnerToolRoleMatch() {
        CommandLinkedNpcCoopService service = new CommandLinkedNpcCoopService();
        UUID oldUuid = UUID.randomUUID();
        UUID newUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();

        service.recordCoopSnapshot(
                new CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot(
                        oldUuid,
                        ownerUuid,
                        new String[] {"tool-alpha"},
                        "tamed_chicken",
                        "Cooped Companion",
                        "Coop_Chicken",
                        2,
                        System.currentTimeMillis()
                )
        );

        CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot consumed =
                service.consumeRespawnSnapshotForLinks(newUuid, ownerUuid, new String[] {"tool-alpha"}, "Tamed_Chicken");
        assertNotNull(consumed);
        assertEquals(oldUuid, consumed.npcUuid());
        assertNull(service.getCoopSnapshot(oldUuid));
    }

    @Test
    void consumeRespawnSnapshotReturnsNullWhenMatchIsAmbiguous() {
        CommandLinkedNpcCoopService service = new CommandLinkedNpcCoopService();
        UUID ownerUuid = UUID.randomUUID();
        UUID firstUuid = UUID.randomUUID();
        UUID secondUuid = UUID.randomUUID();

        service.recordCoopSnapshot(
                new CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot(
                        firstUuid,
                        ownerUuid,
                        new String[] {"tool-alpha"},
                        "tamed_chicken",
                        "First Companion",
                        "Coop_Chicken",
                        1,
                        System.currentTimeMillis()
                )
        );
        service.recordCoopSnapshot(
                new CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot(
                        secondUuid,
                        ownerUuid,
                        new String[] {"tool-alpha"},
                        "tamed_chicken",
                        "Second Companion",
                        "Coop_Chicken",
                        3,
                        System.currentTimeMillis()
                )
        );

        CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot consumed =
                service.consumeRespawnSnapshotForLinks(UUID.randomUUID(), ownerUuid, new String[] {"tool-alpha"}, "tamed_chicken");
        assertNull(consumed);
        assertNotNull(service.getCoopSnapshot(firstUuid));
        assertNotNull(service.getCoopSnapshot(secondUuid));
    }

    @Test
    void consumeRespawnSnapshotMatchesByCoopResidentSlot() {
        CommandLinkedNpcCoopService service = new CommandLinkedNpcCoopService();
        UUID oldUuid = UUID.randomUUID();
        UUID newUuid = UUID.randomUUID();

        service.recordCoopSnapshot(
                new CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot(
                        oldUuid,
                        UUID.randomUUID(),
                        new String[] {"tool-alpha"},
                        "tamed_chicken",
                        "Cooped Companion",
                        "coop/chicken_oak",
                        4,
                        System.currentTimeMillis()
                )
        );

        CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot consumed =
                service.consumeRespawnSnapshotForCoopResident(newUuid, "coop/chicken_oak", 4, "tamed_chicken");
        assertNotNull(consumed);
        assertEquals(oldUuid, consumed.npcUuid());
        assertNull(service.getCoopSnapshot(oldUuid));
    }

    @Test
    void consumeRespawnSnapshotSlotMatchReturnsNullWhenAmbiguous() {
        CommandLinkedNpcCoopService service = new CommandLinkedNpcCoopService();

        service.recordCoopSnapshot(
                new CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        new String[] {"tool-alpha"},
                        "tamed_chicken",
                        "First",
                        "coop/chicken_oak",
                        2,
                        System.currentTimeMillis()
                )
        );
        service.recordCoopSnapshot(
                new CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        new String[] {"tool-beta"},
                        "tamed_chicken",
                        "Second",
                        "coop/chicken_oak",
                        2,
                        System.currentTimeMillis()
                )
        );

        CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot consumed =
                service.consumeRespawnSnapshotForCoopResident(UUID.randomUUID(), "coop/chicken_oak", 2, "tamed_chicken");
        assertNull(consumed);
    }

    @Test
    void resolveReleaseRequiresFreshCaptureBeforeRemap() {
        CommandLinkedNpcCoopService service = new CommandLinkedNpcCoopService();
        UUID housedUuid = UUID.randomUUID();
        UUID firstReleaseUuid = UUID.randomUUID();
        CommandLinkedNpcCoopService.CoopSlotContext slot =
                CommandLinkedNpcCoopService.CoopSlotContext.of("default", "Coop_Chicken", 10, 70, 10, 0);

        service.captureResident(
                housedUuid,
                "tamed_chicken",
                slot,
                UUID.randomUUID(),
                new String[] { "tool-alpha" },
                "Companion",
                null
        );
        CommandLinkedNpcCoopService.ReleaseResolution first =
                service.resolveRelease(firstReleaseUuid, "tamed_chicken", slot, false);
        assertTrue(!first.isFailure());

        CommandLinkedNpcCoopService.ReleaseResolution second =
                service.resolveRelease(UUID.randomUUID(), "tamed_chicken", slot, false);
        assertTrue(second.isFailure());
        assertEquals("release_without_capture", second.failureReason());
    }

    @Test
    void resolveReleaseDoesNotFallbackAcrossDifferentCoordinates() {
        CommandLinkedNpcCoopService service = new CommandLinkedNpcCoopService();
        UUID housedUuid = UUID.randomUUID();
        CommandLinkedNpcCoopService.CoopSlotContext original =
                CommandLinkedNpcCoopService.CoopSlotContext.of("default", "Coop_Chicken", 1, 64, 1, 0);
        CommandLinkedNpcCoopService.CoopSlotContext different =
                CommandLinkedNpcCoopService.CoopSlotContext.of("default", "Coop_Chicken", 99, 64, 99, 0);

        service.captureResident(
                housedUuid,
                "tamed_chicken",
                original,
                UUID.randomUUID(),
                new String[] { "tool-alpha" },
                "Companion",
                null
        );

        CommandLinkedNpcCoopService.ReleaseResolution resolution =
                service.resolveRelease(UUID.randomUUID(), "tamed_chicken", different, false);
        assertTrue(resolution.isFailure());
        assertEquals("slot_untracked", resolution.failureReason());
    }

    @Test
    void recaptureResidentFromReleasedUuidRecoversSecondNightCycle() {
        CommandLinkedNpcCoopService service = new CommandLinkedNpcCoopService();
        UUID originalHoused = UUID.randomUUID();
        UUID firstReleased = UUID.randomUUID();
        UUID secondReleased = UUID.randomUUID();
        CommandLinkedNpcCoopService.CoopSlotContext slot =
                CommandLinkedNpcCoopService.CoopSlotContext.of("default", "Coop_Chicken", 305, 122, 369, 1);

        service.captureResident(
                originalHoused,
                "tamed_chicken",
                slot,
                UUID.randomUUID(),
                new String[] { "tool-alpha" },
                "Companion",
                null
        );
        CommandLinkedNpcCoopService.ReleaseResolution firstRelease =
                service.resolveRelease(firstReleased, "tamed_chicken", slot, false);
        assertTrue(!firstRelease.isFailure());

        boolean recaptured = service.recaptureResidentFromReleasedUuid(
                firstReleased,
                "tamed_chicken",
                "default",
                "Coop_Chicken",
                305,
                122,
                369,
                null,
                null,
                null,
                null
        );
        assertTrue(recaptured);

        CommandLinkedNpcCoopService.ReleaseResolution secondRelease =
                service.resolveRelease(secondReleased, "tamed_chicken", slot, false);
        assertTrue(!secondRelease.isFailure());
        assertEquals(firstReleased, secondRelease.previousNpcUuid());
    }

    @Test
    void recaptureResidentFromReleasedUuidRequiresMatchingCoordinates() {
        CommandLinkedNpcCoopService service = new CommandLinkedNpcCoopService();
        UUID originalHoused = UUID.randomUUID();
        UUID firstReleased = UUID.randomUUID();
        CommandLinkedNpcCoopService.CoopSlotContext slot =
                CommandLinkedNpcCoopService.CoopSlotContext.of("default", "Coop_Chicken", 305, 122, 369, 1);

        service.captureResident(
                originalHoused,
                "tamed_chicken",
                slot,
                UUID.randomUUID(),
                new String[] { "tool-alpha" },
                "Companion",
                null
        );
        CommandLinkedNpcCoopService.ReleaseResolution firstRelease =
                service.resolveRelease(firstReleased, "tamed_chicken", slot, false);
        assertTrue(!firstRelease.isFailure());

        boolean recaptured = service.recaptureResidentFromReleasedUuid(
                firstReleased,
                "tamed_chicken",
                "default",
                "Coop_Chicken",
                999,
                122,
                369,
                null,
                null,
                null,
                null
        );
        assertTrue(!recaptured);
    }
}
