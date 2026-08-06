package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import java.util.UUID;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for operation-owned projection profile persistence. */
class CommandLinkedNpcStateSnapshotServiceTest {
    @Test
    void parkedAvatarFlightSnapshotKeepsOriginalProfilePresentation() {
        UUID npcUuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        CommandLinkedNpcStateSnapshotService.LiveLinkedNpcSnapshot original = snapshot(
                npcUuid, "Tamed_Dragon_Frost", "Glacier", "Frost Dragon", true);
        CommandLinkedNpcStateSnapshotService.LiveLinkedNpcSnapshot parked = snapshot(
                npcUuid, "Tamed_Dragon_Frost", null, "Empty", false);

        CommandLinkedNpcStateSnapshotService.LiveLinkedNpcSnapshot preserved =
                CommandLiveNpcSnapshotFactory.preserveParkedPresentation(parked, original);

        assertEquals("Tamed_Dragon_Frost", preserved.roleId());
        assertEquals("Glacier", preserved.customName());
        assertEquals("Frost Dragon", preserved.displayName());
        assertTrue(preserved.tamed());
    }

    @Test
    void liveSnapshotDefensivelyCopiesMutableValues() {
        String[] toolIds = {"tool-a"};
        Vector3d lastKnown = new Vector3d(1.0D, 2.0D, 3.0D);
        Vector3d home = new Vector3d(4.0D, 5.0D, 6.0D);

        CommandLinkedNpcStateSnapshotService.LiveLinkedNpcSnapshot snapshot =
                new CommandLinkedNpcStateSnapshotService.LiveLinkedNpcSnapshot(
                        UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                        null,
                        null,
                        toolIds,
                        "Mob_Test",
                        true,
                        "Custom",
                        "Display",
                        lastKnown,
                        home
                );
        toolIds[0] = "changed";
        lastKnown.set(9.0D, 9.0D, 9.0D);
        home.set(8.0D, 8.0D, 8.0D);

        assertArrayEquals(new String[]{"tool-a"}, snapshot.toolIds());
        assertEquals(new Vector3d(1.0D, 2.0D, 3.0D),
                snapshot.lastKnownPosition());
        assertEquals(new Vector3d(4.0D, 5.0D, 6.0D),
                snapshot.homePosition());
        assertNotSame(snapshot.toolIds(), snapshot.toolIds());
        assertNotSame(snapshot.lastKnownPosition(), snapshot.lastKnownPosition());
        assertNotSame(snapshot.homePosition(), snapshot.homePosition());
    }

    @Test
    void exposesInjectedLoadedIdentityIndex() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();

        CommandLinkedNpcStateSnapshotService service =
                new CommandLinkedNpcStateSnapshotService(
                        CompanionProfileSnapshotSink.ignore(), index
                );

        assertSame(index, service.getLoadedNpcIdentityIndex());
    }

    @Test
    void projectionMarkerIsCopiedIntoAnImmutableIndexKey() {
        UUID sourceUuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        TameworkProjectionIdentityComponent marker = new TameworkProjectionIdentityComponent(
                " profile-a ",
                " operation-a ",
                TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE,
                " slot-a ",
                sourceUuid,
                2L
        );

        LoadedNpcIdentityIndex.ProjectionKey key =
                CommandLinkedNpcStateSnapshotService.projectionKey(marker);

        assertEquals("profile-a", key.profileId());
        assertEquals("operation-a", key.operationId());
        assertEquals(TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE,
                key.projectionKind());
        assertEquals("slot-a", key.slotKey());
        assertEquals(sourceUuid, key.sourceNpcUuid());
        assertEquals(2L, key.generation());
    }

    @Test
    void incompleteProjectionMarkerIsExcludedFromExactIdentityIndexing() {
        assertNull(CommandLinkedNpcStateSnapshotService.projectionKey(null));
        assertNull(CommandLinkedNpcStateSnapshotService.projectionKey(
                new TameworkProjectionIdentityComponent(
                        "profile-a", "operation-a", " ", null, null, 0L
                )
        ));
        assertNull(CommandLinkedNpcStateSnapshotService.projectionKey(
                new TameworkProjectionIdentityComponent(
                        "profile-a", "operation-a", "RECOVERY", null, null, -1L
                )
        ));
    }

    @Test
    void operationOwnedRecoveryReleaseAndCaptureMarkersDeferGenericProfileUpsert() {
        assertTrue(CommandLinkedNpcStateSnapshotService.shouldDeferProfileUpsert(marker(
                TameworkProjectionIdentityComponent.KIND_RECOVERY
        )));
        assertTrue(CommandLinkedNpcStateSnapshotService.shouldDeferProfileUpsert(marker(
                TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE
        )));
        assertTrue(CommandLinkedNpcStateSnapshotService.shouldDeferProfileUpsert(marker(
                TameworkProjectionIdentityComponent.KIND_CAPTURE_RELEASE
        )));
        assertTrue(CommandLinkedNpcStateSnapshotService.shouldDeferProfileUpsert(marker(
                TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_CAPTURE_SOURCE
        )));
        assertTrue(CommandLinkedNpcStateSnapshotService.shouldDeferProfileUpsert(marker(
                TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_IMPORT_ADOPTION
        )));
        assertTrue(CommandLinkedNpcStateSnapshotService.shouldDeferProfileUpsert(marker(
                TameworkProjectionIdentityComponent.KIND_BREEDING_CHILD
        )));
        assertTrue(CommandLinkedNpcStateSnapshotService.shouldDeferProfileUpsert(marker(
                TameworkProjectionIdentityComponent.KIND_COMMAND_ROSTER
        )));
    }

    @Test
    void missingIncompleteAndUnknownMarkersDoNotSuppressNormalPersistence() {
        assertFalse(CommandLinkedNpcStateSnapshotService.shouldDeferProfileUpsert(null));
        assertFalse(CommandLinkedNpcStateSnapshotService.shouldDeferProfileUpsert(
                new TameworkProjectionIdentityComponent(
                        "profile-a", "", TameworkProjectionIdentityComponent.KIND_RECOVERY,
                        null, null, 0L
                )
        ));
        assertFalse(CommandLinkedNpcStateSnapshotService.shouldDeferProfileUpsert(
                new TameworkProjectionIdentityComponent(
                        "profile-a", "operation-a", "UNKNOWN", null, null, 0L
                )
        ));
    }

    private TameworkProjectionIdentityComponent marker(String kind) {
        return new TameworkProjectionIdentityComponent(
                "profile-a", "operation-a", kind, null, null, 0L
        );
    }

    private CommandLinkedNpcStateSnapshotService.LiveLinkedNpcSnapshot snapshot(
            UUID npcUuid,
            String roleId,
            String customName,
            String displayName,
            boolean tamed
    ) {
        return new CommandLinkedNpcStateSnapshotService.LiveLinkedNpcSnapshot(
                npcUuid,
                null,
                null,
                new String[]{"tool-a"},
                roleId,
                tamed,
                customName,
                displayName,
                null,
                null
        );
    }

}
