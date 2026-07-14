package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.chunk.EntityChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HytaleSavedWorldEvidenceSourceTest {
    @Test
    void stableCatalogResumesOnlyInsideOneProcessEpoch() throws Exception {
        long first = ChunkUtil.indexChunk(1, 1);
        long second = ChunkUtil.indexChunk(2, 2);
        HytaleSavedWorldEvidenceSource original = source(new long[]{first}, "durable-epoch");
        HytaleSavedWorldEvidenceSource restarted = source(new long[]{first}, "durable-epoch");
        HytaleSavedWorldEvidenceSource newProcess = source(new long[]{first}, "new-process-epoch");
        HytaleSavedWorldEvidenceSource changed = source(new long[]{first, second}, "durable-epoch");

        assertEquals(original.descriptor().scanGeneration(), restarted.descriptor().scanGeneration());
        assertNotEquals(original.descriptor().scanGeneration(), newProcess.descriptor().scanGeneration());
        assertNotEquals(original.descriptor().scanGeneration(), changed.descriptor().scanGeneration());
    }

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

    @Test
    void projectionMarkerEvidenceIncludesOwnerlessEntitiesWithUnknownComponentUuid() {
        UUID legacyUuid = new UUID(0L, 41L);
        TameworkProjectionIdentityComponent marker = marker();
        String fingerprint = markerFingerprint(marker);

        CompanionPopulationEvidence evidence = HytaleSavedWorldEvidenceSource.projectionEvidence(
                marker, null, legacyUuid, null,
                "alpha", "world-entities:alpha", 3, 4, 0
        );
        CompanionProjectionEvidence.ProjectionObservation projection =
                evidence.projectionObservation();

        assertEquals(CompanionPopulationEvidence.Kind.PROJECTION_MARKER, evidence.kind());
        assertEquals(legacyUuid, evidence.npcUuid());
        assertTrue(evidence.ownerObserved());
        assertNull(evidence.ownerUuid());
        assertEquals("alpha", evidence.physicalWorldName());
        assertEquals(fingerprint, projection.fingerprint());
        assertNull(projection.componentUuid());
        assertEquals(legacyUuid, projection.legacyNpcUuid());
    }

    @Test
    void markerWithBothEntityIdentitiesUnknownStillProducesProjectionOnlyEvidence() {
        TameworkProjectionIdentityComponent marker = marker();

        CompanionPopulationEvidence evidence = HytaleSavedWorldEvidenceSource.projectionEvidence(
                marker, null, null, null,
                "alpha", "world-entities:alpha", 5, 6, 1
        );
        CompanionPopulationEvidenceSet set = new CompanionPopulationEvidenceSet(List.of(evidence));

        assertTrue(set.evidence().isEmpty());
        assertEquals(1, set.projectionObservations(markerFingerprint(marker)).size());
        assertNull(evidence.projectionObservation().componentUuid());
        assertNull(evidence.projectionObservation().legacyNpcUuid());
    }

    @Test
    void incompleteBatchRetainsMarkerEvidenceWithoutSealingCoverage() throws Exception {
        TameworkProjectionIdentityComponent marker = marker();
        String fingerprint = markerFingerprint(marker);
        long[] indexes = {ChunkUtil.indexChunk(1, 1), ChunkUtil.indexChunk(2, 2)};
        HytaleSavedWorldEvidenceSource source = new HytaleSavedWorldEvidenceSource(
                "alpha",
                HytaleSavedWorldEvidenceSource.Mode.WORLD_ENTITIES,
                () -> indexes.clone(),
                (chunkX, chunkZ) -> CompletableFuture.completedFuture(List.of(
                        HytaleSavedWorldEvidenceSource.projectionEvidence(
                                marker, null, null, null,
                                "alpha", "world-entities:alpha", chunkX, chunkZ, 0
                        )
                )),
                "epoch-marker"
        );

        CompanionPopulationEvidenceSource.Batch batch = source.scan(0L, 1).join();
        CompanionPopulationEvidenceSet set = new CompanionPopulationEvidenceSet(batch.evidence());

        assertFalse(batch.complete());
        assertEquals(1, set.projectionObservations(fingerprint).size());
        assertTrue(set.evidence().isEmpty());
    }

    @Test
    void authoritativeEntityScanFailsClosedWithoutMarkerOrNpcTypes() {
        ComponentType<ChunkStore, EntityChunk> entityChunk = new ComponentType<>();
        ComponentType<EntityStore, UUIDComponent> uuid = new ComponentType<>();
        ComponentType<EntityStore, DeathComponent> death = new ComponentType<>();
        ComponentType<EntityStore, TameworkProjectionIdentityComponent> projection =
                new ComponentType<>();
        ComponentType<EntityStore, NPCEntity> npc = new ComponentType<>();

        assertThrows(IllegalStateException.class, () ->
                HytaleSavedWorldEvidenceSource.requireAuthoritativeEntityTypes(
                        entityChunk, uuid, death, null, npc
                ));
        assertThrows(IllegalStateException.class, () ->
                HytaleSavedWorldEvidenceSource.requireAuthoritativeEntityTypes(
                        entityChunk, uuid, death, projection, null
                ));
    }

    @Test
    void malformedSavedMarkerFailsClosedInsteadOfDisappearingFromExactLookup() {
        TameworkProjectionIdentityComponent marker = marker();
        marker.setGeneration(0L);

        assertThrows(IllegalStateException.class, () ->
                HytaleSavedWorldEvidenceSource.projectionEvidence(
                        marker, null, null, null,
                        "alpha", "world-entities:alpha", 0, 0, 0
                ));
    }

    @Test
    void knownMarkerEntityEmitsSeparatePhysicalAndProjectionRecords() {
        UUID planned = new UUID(0L, 51L);
        ComponentRegistry<EntityStore> entities = new ComponentRegistry<>();
        ComponentType<EntityStore, UUIDComponent> uuidType = entities.registerComponent(
                UUIDComponent.class, UUIDComponent::randomUUID
        );
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = entities.registerComponent(
                TameworkOwnerComponent.class, TameworkOwnerComponent::new
        );
        ComponentType<EntityStore, TameworkProjectionIdentityComponent> projectionType =
                entities.registerComponent(
                        TameworkProjectionIdentityComponent.class,
                        TameworkProjectionIdentityComponent::new
                );
        ComponentType<EntityStore, NPCEntity> npcType = entities.registerComponent(
                NPCEntity.class, NPCEntity::new
        );
        ComponentType<EntityStore, DeathComponent> deathType = entities.registerComponent(
                DeathComponent.class, SavedDeathComponent::new
        );
        Holder<EntityStore> entity = entities.newHolder();
        entity.addComponent(uuidType, new UUIDComponent(planned));
        entity.addComponent(projectionType, marker());
        NPCEntity npc = new NPCEntity();
        npc.setLegacyUUID(planned);
        entity.addComponent(npcType, npc);
        entity.addComponent(deathType, new SavedDeathComponent());

        ComponentRegistry<ChunkStore> chunks = new ComponentRegistry<>();
        ComponentType<ChunkStore, EntityChunk> entityChunkType = chunks.registerComponent(
                EntityChunk.class, EntityChunk::new
        );
        EntityChunk entityChunk = new EntityChunk();
        entityChunk.addEntityHolder(entity);
        Holder<ChunkStore> chunk = chunks.newHolder();
        chunk.addComponent(entityChunkType, entityChunk);

        List<CompanionPopulationEvidence> found = HytaleSavedWorldEvidenceSource.scanEntities(
                chunk, 7, 8, "alpha", "world-entities:alpha",
                ownerType, Set.of(planned),
                new HytaleSavedWorldEvidenceSource.SavedEntityComponentTypes(
                        entityChunkType, uuidType, deathType, projectionType, npcType
                )
        );

        assertEquals(2, found.size());
        assertEquals(1, found.stream().filter(value -> value.kind().isPhysical()).count());
        assertEquals(1, found.stream().filter(value -> value.kind().isProjectionMarker()).count());
        assertTrue(found.stream()
                .filter(value -> value.kind().isProjectionMarker())
                .findFirst().orElseThrow()
                .projectionObservation().deathObserved());
        assertEquals(CompanionPopulationEvidence.Kind.PHYSICAL_DEAD_ENTITY,
                found.stream().filter(value -> value.kind().isPhysical())
                        .findFirst().orElseThrow().kind());
    }

    /** Protects old saved NPCs that Hytale's LegacyUUIDSystem migrates only after entity load. */
    @Test
    void ownedLegacyNpcWithoutUuidComponentStillProducesPhysicalEvidence() {
        UUID legacyUuid = new UUID(0L, 61L);
        ComponentRegistry<EntityStore> entities = new ComponentRegistry<>();
        ComponentType<EntityStore, UUIDComponent> uuidType = entities.registerComponent(
                UUIDComponent.class, UUIDComponent::randomUUID
        );
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = entities.registerComponent(
                TameworkOwnerComponent.class, TameworkOwnerComponent::new
        );
        ComponentType<EntityStore, TameworkProjectionIdentityComponent> projectionType =
                entities.registerComponent(
                        TameworkProjectionIdentityComponent.class,
                        TameworkProjectionIdentityComponent::new
                );
        ComponentType<EntityStore, NPCEntity> npcType = entities.registerComponent(
                NPCEntity.class, NPCEntity::new
        );
        ComponentType<EntityStore, DeathComponent> deathType = entities.registerComponent(
                DeathComponent.class, SavedDeathComponent::new
        );
        Holder<EntityStore> entity = entities.newHolder();
        entity.addComponent(ownerType, new TameworkOwnerComponent(new UUID(0L, 62L), "owner"));
        NPCEntity npc = new NPCEntity();
        npc.setLegacyUUID(legacyUuid);
        entity.addComponent(npcType, npc);

        ComponentRegistry<ChunkStore> chunks = new ComponentRegistry<>();
        ComponentType<ChunkStore, EntityChunk> entityChunkType = chunks.registerComponent(
                EntityChunk.class, EntityChunk::new
        );
        EntityChunk entityChunk = new EntityChunk();
        entityChunk.addEntityHolder(entity);
        Holder<ChunkStore> chunk = chunks.newHolder();
        chunk.addComponent(entityChunkType, entityChunk);

        List<CompanionPopulationEvidence> found = HytaleSavedWorldEvidenceSource.scanEntities(
                chunk, 9, 10, "alpha", "world-entities:alpha",
                ownerType, Set.of(),
                new HytaleSavedWorldEvidenceSource.SavedEntityComponentTypes(
                        entityChunkType, uuidType, deathType, projectionType, npcType
                )
        );

        assertEquals(1, found.size());
        assertEquals(legacyUuid, found.getFirst().npcUuid());
        assertEquals(CompanionPopulationEvidence.Kind.PHYSICAL_ENTITY,
                found.getFirst().kind());
    }

    @Test
    void conflictingSavedComponentAndLegacyUuidsFailClosed() {
        assertThrows(IllegalStateException.class, () ->
                HytaleSavedWorldEvidenceSource.savedNpcUuid(
                        new UUID(0L, 71L), new UUID(0L, 72L)
                ));
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

    private static HytaleSavedWorldEvidenceSource source(long[] indexes, String epoch) throws Exception {
        return new HytaleSavedWorldEvidenceSource(
                "alpha",
                HytaleSavedWorldEvidenceSource.Mode.WORLD_ENTITIES,
                () -> indexes.clone(),
                (chunkX, chunkZ) -> CompletableFuture.completedFuture(List.of()),
                epoch
        );
    }

    private static TameworkProjectionIdentityComponent marker() {
        return new TameworkProjectionIdentityComponent(
                "profile-child",
                "attempt-key",
                TameworkProjectionIdentityComponent.KIND_BREEDING_CHILD,
                "child-0000",
                new UUID(0L, 40L),
                1L
        );
    }

    private static String markerFingerprint(TameworkProjectionIdentityComponent marker) {
        return CompanionProjectionEvidence.fingerprint(
                marker.getProfileId(), marker.getOperationId(), marker.getProjectionKind(),
                marker.getSlotKey(), marker.getSourceNpcUuid(), marker.getGeneration()
        );
    }

    private static final class SavedDeathComponent extends DeathComponent {
        private SavedDeathComponent() {
            super();
        }
    }
}
