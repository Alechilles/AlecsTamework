package com.alechilles.alecstamework.persistence.incidents;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceFeatureCircuitCatalogTest {
    @Test
    void exposesOnlyStableMappedEntryDomains() {
        assertEquals(PersistenceDomain.MANAGED_COOP_AUTOMATION,
                PersistenceFeatureCircuitCatalog.resolve("coop_automation"));
        assertEquals(PersistenceDomain.RECALL_RELOCATION,
                PersistenceFeatureCircuitCatalog.resolve("recall-relocation"));
        assertNull(PersistenceFeatureCircuitCatalog.resolve("storage"));
        assertTrue(PersistenceFeatureCircuitCatalog.keys().contains("automatic-recovery"));
    }
}
