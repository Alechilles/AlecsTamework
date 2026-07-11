package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionPopulationReconciliationCatalogTest {
    @Test
    void combinesCoreAndCustomSourcesAndPreservesSealDeclarations() {
        CompanionPopulationEvidenceSource profiles = source(
                "profiles", CompanionPopulationCoverageRecord.Dimension.PROFILE_STATE
        );
        CompanionPopulationEvidenceSource worlds = source(
                "worlds", CompanionPopulationCoverageRecord.Dimension.WORLD_ENTITIES
        );
        CompanionPopulationEvidenceSource custom = source(
                "custom", CompanionPopulationCoverageRecord.Dimension.CUSTOM_CONTAINERS
        );
        CustomContainerReconciliationRegistry.Snapshot customSnapshot =
                new CustomContainerReconciliationRegistry.Snapshot(
                        List.of(custom), true, "all custom stores", "custom-generation"
                );

        CompanionPopulationReconciliationCatalog catalog =
                new CompanionPopulationReconciliationCatalog(
                        List.of(profiles, worlds),
                        true,
                        true,
                        false,
                        false,
                        customSnapshot
                );

        assertEquals(List.of(profiles, worlds, custom), catalog.sources());
        assertEquals(List.of(custom), catalog.sources(
                CompanionPopulationCoverageRecord.Dimension.CUSTOM_CONTAINERS
        ));
        assertTrue(catalog.sealed(CompanionPopulationCoverageRecord.Dimension.PROFILE_STATE));
        assertTrue(catalog.sealed(CompanionPopulationCoverageRecord.Dimension.WORLD_ENTITIES));
        assertFalse(catalog.sealed(CompanionPopulationCoverageRecord.Dimension.PLAYER_SAVES));
        assertFalse(catalog.sealed(CompanionPopulationCoverageRecord.Dimension.BASE_CONTAINER_BLOCKS));
        assertTrue(catalog.sealed(CompanionPopulationCoverageRecord.Dimension.CUSTOM_CONTAINERS));
        CompanionPopulationReconciliationCatalog unsealedWorldCatalog =
                new CompanionPopulationReconciliationCatalog(
                        List.of(profiles, worlds), true, false, false, false, customSnapshot
                );
        assertNotEquals(
                catalog.generation(CompanionPopulationCoverageRecord.Dimension.WORLD_ENTITIES),
                unsealedWorldCatalog.generation(CompanionPopulationCoverageRecord.Dimension.WORLD_ENTITIES),
                "The dimension generation must encode whether its source catalog was sealed."
        );
    }

    @Test
    void rejectsDuplicateCoverageKeysAcrossCoreAndCustomSources() {
        CompanionPopulationEvidenceSource core = source(
                "duplicate", CompanionPopulationCoverageRecord.Dimension.PROFILE_STATE
        );
        CustomContainerReconciliationRegistry.Snapshot customSnapshot =
                new CustomContainerReconciliationRegistry.Snapshot(
                        List.of(source(
                                "duplicate",
                                CompanionPopulationCoverageRecord.Dimension.CUSTOM_CONTAINERS
                        )),
                        true,
                        "all custom stores",
                        "custom-generation"
                );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new CompanionPopulationReconciliationCatalog(
                        List.of(core), true, true, true, true, customSnapshot
                )
        );

        assertTrue(failure.getMessage().contains("Duplicate reconciliation coverage key"));
    }

    private static CompanionPopulationEvidenceSource source(
            String key,
            CompanionPopulationCoverageRecord.Dimension dimension
    ) {
        return new CompanionPopulationEvidenceSource() {
            @Override
            public Descriptor descriptor() {
                return new Descriptor(key, dimension, null, "generation", 0L);
            }

            @Override
            public CompletableFuture<Batch> scan(long offset, int maxUnits) {
                return CompletableFuture.completedFuture(new Batch(List.of(), offset, 0L, true));
            }
        };
    }
}
