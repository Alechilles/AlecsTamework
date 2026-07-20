package com.alechilles.alecstamework.persistence.health;

import java.util.Set;
import java.util.List;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScope;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeFactory;
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

    @Test
    void partialCoverageAllowsOnlyExplicitlyCoveredScopes() {
        PersistenceCoverageRegistry registry = new PersistenceCoverageRegistry();
        PersistenceScopeFactory scopes = PersistenceScopeFactory.ephemeral();
        PersistenceScope covered = scopes.profile("profile-a");
        PersistenceScope unknown = scopes.profile("profile-b");
        String dimension = PersistenceEvidenceDimension.CANONICAL_PROFILE_CATALOG.key();

        registry.publish(
                dimension, PersistenceCoverageStatus.PARTIAL, "one-profile-ready", 4L,
                Set.of(covered.scopeHash()), true, Set.of("incident-a"), "profile_loaded");

        assertTrue(registry.areReady(Set.of(dimension), List.of(covered)));
        assertFalse(registry.areReady(Set.of(dimension), List.of(unknown)));
        assertFalse(registry.areReady(Set.of(dimension)));
        PersistenceCoverageRegistry.CoverageState state = registry.snapshot().get(dimension);
        assertTrue(state.absenceAuthoritative());
        assertTrue(state.incidentIds().contains("incident-a"));
        assertTrue("profile_loaded".equals(state.nextSafeTrigger()));
    }

    @Test
    void contradictoryCoverageNeverAuthorizesMutation() {
        PersistenceCoverageRegistry registry = new PersistenceCoverageRegistry();
        PersistenceScope scope = PersistenceScopeFactory.ephemeral().profile("profile-a");
        String dimension = PersistenceEvidenceDimension.CANONICAL_PROFILE_CATALOG.key();

        registry.publish(
                dimension, PersistenceCoverageStatus.CONTRADICTORY, "duplicate-alias", 5L,
                Set.of(scope.scopeHash()), false, Set.of("incident-a"), "operator_review");

        assertFalse(registry.areReady(Set.of(dimension), List.of(scope)));
    }
}
