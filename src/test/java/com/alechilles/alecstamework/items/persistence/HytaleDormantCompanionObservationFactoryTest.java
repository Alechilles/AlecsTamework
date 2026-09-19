package com.alechilles.alecstamework.items.persistence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HytaleDormantCompanionObservationFactoryTest {
    @Test
    void duplicateCleanupPreservesTheSurvivingCompanion() {
        // Hytale 0.6.7 UUIDSystem removes the incoming duplicate with REMOVE.
        UUID uuid = UUID.randomUUID();
        class MappedEntityStore extends EntityStore {
            Ref<EntityStore> mapped;

            MappedEntityStore() {
                super(null);
            }

            @Override
            public Ref<EntityStore> getRefFromUUID(UUID requested) {
                return uuid.equals(requested) ? mapped : null;
            }
        }
        MappedEntityStore entities = new MappedEntityStore();
        try (TestEntityComponentStore store = new TestEntityComponentStore(entities)) {
            Ref<EntityStore> removed = store.createReference();
            entities.mapped = store.createReference();
            assertTrue(HytaleDormantCompanionObservationFactory
                    .hasRemovalSurvivor(removed, uuid, entities));

            // A real removal must still author loss, regardless of UUID-system order.
            entities.mapped = removed;
            assertFalse(HytaleDormantCompanionObservationFactory
                    .hasRemovalSurvivor(removed, uuid, entities));
            entities.mapped = null;
            assertFalse(HytaleDormantCompanionObservationFactory
                    .hasRemovalSurvivor(removed, uuid, entities));
            entities.mapped = new Ref<>(store);
            assertFalse(HytaleDormantCompanionObservationFactory
                    .hasRemovalSurvivor(removed, uuid, entities));
        }
    }

    @Test
    void destructiveRemovalIsTheOnlyAcceptedEntityRemovalReason() {
        assertTrue(HytaleDormantCompanionObservationFactory
                .authoritativeRemoval(
                        RemoveReason.REMOVE, false, false, false
                ));
        assertFalse(HytaleDormantCompanionObservationFactory
                .authoritativeRemoval(
                        RemoveReason.UNLOAD, false, false, false
                ));
        assertFalse(HytaleDormantCompanionObservationFactory
                .authoritativeRemoval(
                        RemoveReason.BUILDER_TOOLS_UNDO,
                        false,
                        false,
                        false
                ));
    }

    @Test
    void deathAndIntentionalRetirementOwnTheirRemovalBoundaries() {
        assertFalse(HytaleDormantCompanionObservationFactory
                .authoritativeRemoval(
                        RemoveReason.REMOVE, true, false, false
                ));
        assertFalse(HytaleDormantCompanionObservationFactory
                .authoritativeRemoval(
                        RemoveReason.REMOVE, false, true, false
                ));
        assertFalse(HytaleDormantCompanionObservationFactory
                .authoritativeRemoval(
                        RemoveReason.REMOVE, false, false, true
                ));
    }
}
