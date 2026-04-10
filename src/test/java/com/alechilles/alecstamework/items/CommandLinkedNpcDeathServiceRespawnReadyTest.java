package com.alechilles.alecstamework.items;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLinkedNpcDeathServiceRespawnReadyTest {

    @Test
    void markOwnerDeadSnapshotsRespawnReadyOnlyUpdatesMatchingOwner() throws Exception {
        CommandLinkedNpcDeathService service = new CommandLinkedNpcDeathService();
        ConcurrentHashMap<UUID, CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot> snapshots = getSnapshotMap(service);

        UUID ownerA = UUID.randomUUID();
        UUID ownerB = UUID.randomUUID();
        UUID npcAOnCooldown = UUID.randomUUID();
        UUID npcAReady = UUID.randomUUID();
        UUID npcBOnCooldown = UUID.randomUUID();
        long nowMs = System.currentTimeMillis();

        snapshots.put(npcAOnCooldown, snapshot(npcAOnCooldown, ownerA, nowMs + 60_000L));
        snapshots.put(npcAReady, snapshot(npcAReady, ownerA, nowMs - 1_000L));
        snapshots.put(npcBOnCooldown, snapshot(npcBOnCooldown, ownerB, nowMs + 60_000L));

        CommandLinkedNpcDeathService.RespawnReadyUpdateResult result =
                service.markOwnerDeadSnapshotsRespawnReady(ownerA);

        assertEquals(2, result.totalOwned());
        assertEquals(1, result.markedReady());
        assertEquals(1, result.alreadyReady());

        CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot updatedOwnerSnapshot = snapshots.get(npcAOnCooldown);
        assertNotNull(updatedOwnerSnapshot);
        assertTrue(updatedOwnerSnapshot.respawnAvailableAtMs() <= System.currentTimeMillis());

        CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot untouchedOwnerSnapshot = snapshots.get(npcBOnCooldown);
        assertNotNull(untouchedOwnerSnapshot);
        assertFalse(untouchedOwnerSnapshot.isRespawnReady());
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<UUID, CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot> getSnapshotMap(
            CommandLinkedNpcDeathService service
    ) throws Exception {
        Field field = CommandLinkedNpcDeathService.class.getDeclaredField("deadByNpc");
        field.setAccessible(true);
        return (ConcurrentHashMap<UUID, CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot>) field.get(service);
    }

    private CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot(UUID npcUuid,
                                                                         UUID ownerUuid,
                                                                         long respawnAvailableAtMs) {
        long diedAtMs = Math.max(1L, respawnAvailableAtMs - 10_000L);
        return new CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot(
                npcUuid,
                ownerUuid,
                "owner",
                new String[] {"tool-alpha"},
                "server.npcRole.test",
                true,
                "Companion",
                "Companion",
                null,
                null,
                diedAtMs,
                respawnAvailableAtMs,
                null,
                null,
                0L,
                null,
                null,
                0L,
                null,
                null,
                null,
                0L,
                null,
                0L,
                0L,
                0L,
                0L,
                0.55,
                0.80,
                0.80,
                0.80,
                1.00,
                1.00,
                false,
                null,
                null,
                false,
                null,
                1,
                0.0,
                null,
                0,
                null
        );
    }
}
