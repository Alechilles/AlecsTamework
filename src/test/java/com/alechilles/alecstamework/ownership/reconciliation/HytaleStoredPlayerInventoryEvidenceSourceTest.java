package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HytaleStoredPlayerInventoryEvidenceSourceTest {
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void durableEpochResumesStableCatalogButStructuralChangeInvalidatesGeneration() {
        HytaleStoredPlayerInventoryEvidenceSource original = source(Set.of(FIRST), "durable-epoch");
        HytaleStoredPlayerInventoryEvidenceSource restarted = source(Set.of(FIRST), "durable-epoch");
        HytaleStoredPlayerInventoryEvidenceSource changed = source(
                Set.of(FIRST, SECOND),
                "durable-epoch"
        );

        assertEquals(original.descriptor().generation(), restarted.descriptor().generation());
        assertNotEquals(original.descriptor().generation(), changed.descriptor().generation());
    }

    @Test
    void scansTheSnapshottedPlayerCatalogInStableUuidOrder() throws Exception {
        Set<UUID> unsorted = new LinkedHashSet<>(List.of(SECOND, FIRST));
        List<UUID> reads = new ArrayList<>();
        HytaleStoredPlayerInventoryEvidenceSource source =
                new HytaleStoredPlayerInventoryEvidenceSource(
                        () -> unsorted,
                        playerUuid -> {
                            reads.add(playerUuid);
                            return CompletableFuture.completedFuture(List.of(evidence(playerUuid)));
                        },
                        "epoch-a"
                );

        CompanionPopulationEvidenceSource.Batch firstBatch = source.scan(0L, 1).join();
        CompanionPopulationEvidenceSource.Batch secondBatch = source.scan(1L, 8).join();

        assertEquals(List.of(FIRST, SECOND), reads);
        assertEquals(1L, firstBatch.nextOffset());
        assertEquals(1L, firstBatch.scannedUnits());
        assertEquals(false, firstBatch.complete());
        assertEquals(2L, secondBatch.nextOffset());
        assertEquals(1L, secondBatch.scannedUnits());
        assertTrue(secondBatch.complete());
        assertEquals(SECOND, secondBatch.evidence().getFirst().npcUuid());
        assertEquals("player-saves:stored", source.descriptor().coverageKey());
        assertEquals(
                CompanionPopulationCoverageRecord.Dimension.PLAYER_SAVES,
                source.descriptor().dimension()
        );
        assertEquals("universe", source.descriptor().worldOrSaveId());
        assertEquals(2L, source.descriptor().estimatedTotal());
    }

    @Test
    void rejectsCompletionWhenTheStoredPlayerCatalogChanged() throws Exception {
        AtomicReference<Set<UUID>> players = new AtomicReference<>(Set.of(FIRST));
        HytaleStoredPlayerInventoryEvidenceSource source =
                new HytaleStoredPlayerInventoryEvidenceSource(
                        players::get,
                        ignored -> CompletableFuture.completedFuture(List.of()),
                        "epoch-b"
                );
        players.set(Set.of(FIRST, SECOND));

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> source.scan(0L, 10).join()
        );

        assertTrue(failure.getCause().getMessage().contains("catalog changed"));
    }

    private static CompanionPopulationEvidence evidence(UUID playerUuid) {
        return new CompanionPopulationEvidence(
                "player/" + playerUuid,
                playerUuid,
                null,
                CompanionPopulationEvidence.Kind.CAPTURED_ITEM,
                null,
                null,
                null,
                null,
                "player-saves:stored"
        );
    }

    private static HytaleStoredPlayerInventoryEvidenceSource source(Set<UUID> players, String epoch) {
        return new HytaleStoredPlayerInventoryEvidenceSource(
                () -> players,
                ignored -> CompletableFuture.completedFuture(List.of()),
                epoch
        );
    }
}
