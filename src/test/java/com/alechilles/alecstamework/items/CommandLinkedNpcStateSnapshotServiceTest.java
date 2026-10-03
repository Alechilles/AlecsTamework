package com.alechilles.alecstamework.items;

import java.util.UUID;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
