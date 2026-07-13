package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.RemoveReason;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for linked companions removed by vanilla {@code npc clean}. */
class CommandUnexpectedRemovalRecoveryServiceTest {
    private static final UUID NPC = UUID.fromString("00000000-0000-0000-0000-000000000901");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000902");

    @Test
    void destructiveRemovalWithFullSnapshotSubmitsStrictLostRecovery() {
        ArrayList<CommandUnexpectedRemovalRecoveryService.LostRequest> recorded = new ArrayList<>();
        CommandUnexpectedRemovalRecoveryService service =
                new CommandUnexpectedRemovalRecoveryService(recorded::add);

        CommandUnexpectedRemovalRecoveryService.Result result = service.recordIfRecoverable(
                evidence(RemoveReason.REMOVE, true, false, false, false)
        );

        assertEquals(CommandUnexpectedRemovalRecoveryService.Result.SUBMITTED, result);
        assertEquals(1, recorded.size());
        CommandUnexpectedRemovalRecoveryService.LostRequest request = recorded.get(0);
        assertEquals(NPC, request.npcUuid());
        assertEquals(OWNER, request.ownerUuid());
        assertEquals(1234L, request.removedAtMs());
        assertEquals(new Vector3d(1.0, 2.0, 3.0), request.lastKnownPosition());
        assertEquals(new Vector3d(4.0, 5.0, 6.0), request.homePosition());
    }

    @Test
    void ordinaryChunkUnloadNeverCreatesReplacementRecovery() {
        ArrayList<CommandUnexpectedRemovalRecoveryService.LostRequest> recorded = new ArrayList<>();
        CommandUnexpectedRemovalRecoveryService service =
                new CommandUnexpectedRemovalRecoveryService(recorded::add);

        CommandUnexpectedRemovalRecoveryService.Result result = service.recordIfRecoverable(
                evidence(RemoveReason.UNLOAD, true, false, false, false)
        );

        assertEquals(CommandUnexpectedRemovalRecoveryService.Result.SKIPPED_NOT_DESTRUCTIVE, result);
        assertTrue(recorded.isEmpty());
    }

    @Test
    void deathAndMissingFullStateFailClosed() {
        ArrayList<CommandUnexpectedRemovalRecoveryService.LostRequest> recorded = new ArrayList<>();
        CommandUnexpectedRemovalRecoveryService service =
                new CommandUnexpectedRemovalRecoveryService(recorded::add);

        assertEquals(
                CommandUnexpectedRemovalRecoveryService.Result.SKIPPED_DEATH,
                service.recordIfRecoverable(evidence(RemoveReason.REMOVE, true, true, false, false))
        );
        assertEquals(
                CommandUnexpectedRemovalRecoveryService.Result.SKIPPED_DEATH,
                service.recordIfRecoverable(evidence(RemoveReason.REMOVE, true, false, true, false))
        );
        assertEquals(
                CommandUnexpectedRemovalRecoveryService.Result.SKIPPED_NO_RECOVERY_SNAPSHOT,
                service.recordIfRecoverable(evidence(RemoveReason.REMOVE, false, false, false, false))
        );
        assertTrue(recorded.isEmpty());
    }

    @Test
    void managedCoopCaptureHandoffNeverCreatesLostRecovery() {
        ArrayList<CommandUnexpectedRemovalRecoveryService.LostRequest> recorded = new ArrayList<>();
        CommandUnexpectedRemovalRecoveryService service =
                new CommandUnexpectedRemovalRecoveryService(recorded::add);

        assertEquals(
                CommandUnexpectedRemovalRecoveryService.Result.SKIPPED_INTENTIONAL_HANDOFF,
                service.recordIfRecoverable(evidence(RemoveReason.REMOVE, true, false, false, true))
        );
        assertTrue(recorded.isEmpty());
    }

    @Test
    void removalObserverRetainsSnapshotUntilLostSubmission() throws Exception {
        String system = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "npc", "systems",
                "CommandNpcRelocationOnLoadSystem.java"
        ), StandardCharsets.UTF_8);
        int removeStart = system.indexOf("public void onEntityRemove");
        int removeEnd = system.indexOf("public Query<EntityStore> getQuery", removeStart);
        String removal = system.substring(removeStart, removeEnd);

        assertTrue(removal.indexOf("beginNpcRemoval") < removal.indexOf("deathService.onNpcRemoved"));
        assertTrue(removal.indexOf("deathService.onNpcRemoved") < removal.indexOf("lostService.onNpcRemoved"));
        assertTrue(removal.indexOf("lostService.onNpcRemoved") < removal.indexOf("recordUnexpectedRemoval"));
        assertTrue(removal.indexOf("recordUnexpectedRemoval") < removal.indexOf("completeNpcRemoval"));
        assertTrue(removal.contains("isManagedCaptureHandoff(reference, store)"));

        String snapshots = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "items",
                "CommandLinkedNpcStateSnapshotService.java"
        ), StandardCharsets.UTF_8);
        int beginStart = snapshots.indexOf("public UUID beginNpcRemoval");
        int beginEnd = snapshots.indexOf("public void completeNpcRemoval", beginStart);
        String begin = snapshots.substring(beginStart, beginEnd);
        assertTrue(begin.indexOf("refreshFromEntity(reference, store)")
                        < begin.indexOf("loadedNpcIdentityIndex.recordRemoved"),
                "The final full snapshot must be refreshed before live identity is closed.");
    }

    private static CommandUnexpectedRemovalRecoveryService.RemovalEvidence evidence(
            RemoveReason reason,
            boolean restorableSnapshotAvailable,
            boolean deathTracked,
            boolean permanentDeathReleased,
            boolean intentionalHandoff) {
        return new CommandUnexpectedRemovalRecoveryService.RemovalEvidence(
                NPC,
                reason,
                OWNER,
                new Vector3d(1.0, 2.0, 3.0),
                new Vector3d(4.0, 5.0, 6.0),
                restorableSnapshotAvailable,
                deathTracked,
                permanentDeathReleased,
                intentionalHandoff,
                1234L
        );
    }
}
