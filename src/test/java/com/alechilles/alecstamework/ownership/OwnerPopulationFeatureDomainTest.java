package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OwnerPopulationFeatureDomainTest {
    @Test
    void unresolvedFeatureFailuresDoNotCollapseIntoOneOwnerDomain() {
        Map<String, PersistenceDomain> cases = Map.of(
                "breeding_population_commit_failed", PersistenceDomain.BREEDING_BIRTH,
                "managed_coop_population_commit_failed", PersistenceDomain.MANAGED_COOP_RELEASE,
                "coop_release_live_identity_remap_failed", PersistenceDomain.MANAGED_COOP_RELEASE,
                "spawn_identity_remap_failed", PersistenceDomain.TAMED_SPAWN,
                "capture_source_finalize_failed", PersistenceDomain.CAPTURE_RELEASE,
                "recall_relocation_commit_failed", PersistenceDomain.RECALL_RELOCATION,
                "lost_recovery_commit_failed", PersistenceDomain.DEATH_LOST_RECOVERY,
                "public_population_commit_failed", PersistenceDomain.OWNER_MUTATION
        );
        cases.forEach((reason, expected) -> assertEquals(
                expected, OwnerPopulationPersistenceGuard.featureDomain(reason), reason));
    }
}
