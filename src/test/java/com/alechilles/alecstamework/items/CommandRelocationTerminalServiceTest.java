package com.alechilles.alecstamework.items;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

/** Guards terminal Recall recovery routing. */
class CommandRelocationTerminalServiceTest {
    @Test
    void requestsAnotherRelocationWindowAfterActiveReconciliation() {
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        PendingRelocation pending = new PendingRelocation(
                npcUuid,
                new Vector3d(1, 2, 3),
                "world-a",
                null,
                null,
                ownerUuid,
                true,
                true,
                null,
                null,
                0,
                0,
                true,
                null,
                null,
                true
        );
        Map<UUID, PendingRelocation> pendingByNpc =
                new java.util.concurrent.ConcurrentHashMap<>();
        pendingByNpc.put(npcUuid, pending);
        AtomicReference<PendingRelocation> retry = new AtomicReference<>();
        ImportedRecallRecoverySink recovery = failure ->
                CompletableFuture.completedFuture(
                        ImportedRecallRecoverySink.RecoveryOutcome.RETRY_REQUIRED
                );
        CommandRelocationTerminalService terminal =
                new CommandRelocationTerminalService(
                        null,
                        recovery,
                        pendingByNpc,
                        pendingByNpc::remove,
                        (level, message) -> {
                        },
                        retry::set
                );

        terminal.finish(npcUuid, pending, 11_000, true);

        assertSame(pending, retry.get());
    }
}
