package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord;
import com.hypixel.hytale.math.util.ChunkUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HytaleSavedWorldEvidenceSourceTest {
    @Test
    void classifiesSavedDeathComponentsAsDeadPhysicalEvidence() {
        assertEquals(
                CompanionPopulationEvidence.Kind.PHYSICAL_DEAD_ENTITY,
                HytaleSavedWorldEvidenceSource.entityKind(true)
        );
        assertEquals(
                CompanionPopulationEvidence.Kind.PHYSICAL_ENTITY,
                HytaleSavedWorldEvidenceSource.entityKind(false)
        );
    }

    @Test
    void scansTheSnapshottedChunkCatalogInStableIndexOrder() throws Exception {
        long firstIndex = ChunkUtil.indexChunk(1, 1);
        long secondIndex = ChunkUtil.indexChunk(4, 2);
        long[] unsorted = {secondIndex, firstIndex};
        long[] expected = unsorted.clone();
        Arrays.sort(expected);
        List<Long> reads = new ArrayList<>();
        HytaleSavedWorldEvidenceSource source = new HytaleSavedWorldEvidenceSource(
                "alpha",
                HytaleSavedWorldEvidenceSource.Mode.WORLD_ENTITIES,
                () -> unsorted,
                (chunkX, chunkZ) -> {
                    reads.add(ChunkUtil.indexChunk(chunkX, chunkZ));
                    return CompletableFuture.completedFuture(List.of(evidence(chunkX, chunkZ)));
                },
                "epoch-a"
        );

        CompanionPopulationEvidenceSource.Batch firstBatch = source.scan(0L, 1).join();
        CompanionPopulationEvidenceSource.Batch secondBatch = source.scan(1L, 10).join();

        assertEquals(List.of(expected[0], expected[1]), reads);
        assertFalse(firstBatch.complete());
        assertEquals(1L, firstBatch.nextOffset());
        assertEquals(1L, firstBatch.scannedUnits());
        assertTrue(secondBatch.complete());
        assertEquals(2L, secondBatch.nextOffset());
        assertEquals(1L, secondBatch.scannedUnits());
        assertEquals("world-entities:alpha", source.descriptor().coverageKey());
        assertEquals(
                CompanionPopulationCoverageRecord.Dimension.WORLD_ENTITIES,
                source.descriptor().dimension()
        );
        assertEquals("alpha", source.descriptor().worldOrSaveId());
        assertEquals(2L, source.descriptor().estimatedTotal());
    }

    @Test
    void rejectsCompletionWhenTheSavedChunkCatalogChanged() throws Exception {
        long initial = ChunkUtil.indexChunk(1, 1);
        AtomicReference<long[]> indexes = new AtomicReference<>(new long[]{initial});
        HytaleSavedWorldEvidenceSource source = new HytaleSavedWorldEvidenceSource(
                "alpha",
                HytaleSavedWorldEvidenceSource.Mode.BASE_CONTAINER_BLOCKS,
                () -> indexes.get().clone(),
                (chunkX, chunkZ) -> CompletableFuture.completedFuture(List.of()),
                "epoch-b"
        );
        indexes.set(new long[]{initial, ChunkUtil.indexChunk(2, 2)});

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> source.scan(0L, 10).join()
        );

        assertTrue(failure.getCause().getMessage().contains("catalog changed"));
        assertEquals("base-containers:alpha", source.descriptor().coverageKey());
        assertEquals(
                CompanionPopulationCoverageRecord.Dimension.BASE_CONTAINER_BLOCKS,
                source.descriptor().dimension()
        );
    }

    private static CompanionPopulationEvidence evidence(int chunkX, int chunkZ) {
        UUID npcUuid = new UUID(chunkX, chunkZ);
        return new CompanionPopulationEvidence(
                "world/alpha/" + chunkX + "," + chunkZ,
                npcUuid,
                null,
                CompanionPopulationEvidence.Kind.PHYSICAL_ENTITY,
                null,
                "alpha",
                chunkX,
                chunkZ,
                "world-entities:alpha"
        );
    }
}
