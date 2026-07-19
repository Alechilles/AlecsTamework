package com.alechilles.alecstamework.persistence.health;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceCoverageRegistryTest {
    @Test
    void namedDimensionsFailClosedUntilTheirOwnGenerationIsReady() {
        PersistenceCoverageRegistry registry = new PersistenceCoverageRegistry();
        String profiles = PersistenceEvidenceDimension.CANONICAL_PROFILE_CATALOG.key();
        String owners = PersistenceEvidenceDimension.OWNER_POPULATION_CATALOG.key();

        assertFalse(registry.areReady(Set.of(profiles)));
        registry.publish(profiles, true, "loaded", 2L);
        assertTrue(registry.areReady(Set.of(profiles)));
        assertFalse(registry.areReady(Set.of(profiles, owners)));

        registry.publish(profiles, false, "stale-generation", 1L);
        assertTrue(registry.areReady(Set.of(profiles)));
    }

    @Test
    void unavailableDimensionDoesNotPoisonIndependentCoverage() {
        PersistenceCoverageRegistry registry = new PersistenceCoverageRegistry();
        registry.publish(PersistenceEvidenceDimension.MANAGED_COOP_CATALOG,
                false, "world-unloaded", 3L);
        registry.publish(PersistenceEvidenceDimension.BREEDING_REPLAY_JOURNAL,
                true, "loaded", 3L);

        assertFalse(registry.areReady(Set.of(
                PersistenceEvidenceDimension.MANAGED_COOP_CATALOG.key())));
        assertTrue(registry.areReady(Set.of(
                PersistenceEvidenceDimension.BREEDING_REPLAY_JOURNAL.key())));
    }
}
