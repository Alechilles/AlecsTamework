package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OwnerPopulationFeatureDomainTest {
    @ParameterizedTest
    @CsvSource({
            "breeding_population_commit_failed, BREEDING_BIRTH",
            "managed_coop_population_commit_failed, MANAGED_COOP_RELEASE",
            "coop_release_live_identity_remap_failed, MANAGED_COOP_RELEASE",
            "spawn_identity_remap_failed, TAMED_SPAWN",
            "capture_source_finalize_failed, CAPTURE_RELEASE",
            "recall_relocation_commit_failed, RECALL_RELOCATION",
            "lost_recovery_commit_failed, DEATH_LOST_RECOVERY",
            "public_population_commit_failed, OWNER_MUTATION"
    })
    void unresolvedFeatureFailuresDoNotCollapseIntoOneOwnerDomain(
            String reason,
            PersistenceDomain expected) {
        assertEquals(expected, OwnerPopulationPersistenceGuard.featureDomain(reason));
    }
}
