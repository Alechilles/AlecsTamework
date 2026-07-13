package com.alechilles.alecstamework.ownership.reconciliation;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Regression coverage for online-player snapshot stability and bounded dispatch failure. */
class HytaleOnlinePlayerInventoryEvidenceSourceTest {
    private static final UUID FIRST_PLAYER =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_PLAYER =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID FIRST_WORLD =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_WORLD =
            UUID.fromString("10000000-0000-0000-0000-000000000002");

    @Test
    void playerJoiningAfterEmptyStartupSnapshotKeepsPersistedCoverageValid() {
        assertTrue(HytaleOnlinePlayerInventoryEvidenceSource.catalogStillCovers(
                List.of(),
                List.of(target(FIRST_PLAYER, FIRST_WORLD))));
    }

    @Test
    void joiningPlayerDoesNotInvalidateAlreadySnapshottedPlayers() {
        var first = target(FIRST_PLAYER, FIRST_WORLD);

        assertTrue(HytaleOnlinePlayerInventoryEvidenceSource.catalogStillCovers(
                List.of(first),
                List.of(first, target(SECOND_PLAYER, FIRST_WORLD))));
    }

    @Test
    void departureOrWorldTransferStillInvalidatesOnlineSnapshot() {
        var first = target(FIRST_PLAYER, FIRST_WORLD);

        assertFalse(HytaleOnlinePlayerInventoryEvidenceSource.catalogStillCovers(
                List.of(first),
                List.of()));
        assertFalse(HytaleOnlinePlayerInventoryEvidenceSource.catalogStillCovers(
                List.of(first),
                List.of(target(FIRST_PLAYER, SECOND_WORLD))));
    }

    @Test
    void synchronousDispatchFailureCompletesEvidenceFutureExceptionally() {
        CompletableFuture<Object> result = new CompletableFuture<>();

        HytaleOnlinePlayerInventoryEvidenceSource.executeOrFail(
                (task, rejected) -> {
                    throw new IllegalStateException("world stopped");
                },
                () -> {
                },
                result
        );

        assertThrows(CompletionException.class, result::join);
    }

    @Test
    void acceptedButNeverStartedDispatchCompletesEvidenceFutureExceptionally() {
        CompletableFuture<Object> result = new CompletableFuture<>();
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicReference<Runnable> rejected = new AtomicReference<>();

        HytaleOnlinePlayerInventoryEvidenceSource.executeOrFail(
                (task, rejection) -> {
                    queued.set(task);
                    rejected.set(rejection);
                },
                () -> result.complete(new Object()),
                result
        );

        rejected.get().run();
        assertThrows(CompletionException.class, result::join);
        queued.get().run();
        assertTrue(result.isCompletedExceptionally());
    }

    private static HytaleOnlinePlayerInventoryEvidenceSource.Target target(
            UUID playerUuid,
            UUID worldUuid) {
        return new HytaleOnlinePlayerInventoryEvidenceSource.Target(playerUuid, worldUuid);
    }
}
