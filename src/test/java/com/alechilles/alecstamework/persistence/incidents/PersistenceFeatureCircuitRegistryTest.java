package com.alechilles.alecstamework.persistence.incidents;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceFeatureCircuitRegistryTest {
    @Test
    void configuredDefaultAppliesWhenNoDurableOverrideExists() {
        PersistenceFeatureCircuitRegistry registry = new PersistenceFeatureCircuitRegistry();

        registry.applyDefaults(Map.of(PersistenceDomain.MANAGED_COOP_AUTOMATION, false));

        assertFalse(registry.isEnabled(PersistenceDomain.MANAGED_COOP_AUTOMATION));
        assertTrue(registry.isEnabled(PersistenceDomain.BREEDING_PAIRING));
    }

    @Test
    void durableOverrideRetainsPrecedenceAcrossConfigReload() {
        PersistenceFeatureCircuitRegistry registry = new PersistenceFeatureCircuitRegistry();
        registry.reload(Map.of(
                PersistenceDomain.MANAGED_COOP_AUTOMATION,
                new PersistenceFeatureCircuitRegistry.CircuitState(
                        true, "operator_enable", 10L, "admin")));

        registry.applyDefaults(Map.of(PersistenceDomain.MANAGED_COOP_AUTOMATION, false));

        assertTrue(registry.isEnabled(PersistenceDomain.MANAGED_COOP_AUTOMATION));
    }

    @Test
    void configuredGlobalDisableCannotBeBypassedBySpecificOverride() {
        PersistenceFeatureCircuitRegistry registry = new PersistenceFeatureCircuitRegistry();
        registry.publish(PersistenceDomain.MANAGED_COOP_AUTOMATION,
                true, "operator_enable", 10L, "admin");

        registry.applyDefaults(Map.of(PersistenceDomain.ALL_PERSISTENCE, false));

        assertFalse(registry.isEnabled(PersistenceDomain.MANAGED_COOP_AUTOMATION));
    }
}
