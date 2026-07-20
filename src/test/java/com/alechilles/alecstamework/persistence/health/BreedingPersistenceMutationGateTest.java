package com.alechilles.alecstamework.persistence.health;

import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFeatureCircuitRegistry;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRecord;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRegistry;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineState;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeFactory;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BreedingPersistenceMutationGateTest {
    @Test
    void parentQuarantineBlocksOnlyAttemptsUsingThatParent() {
        PersistenceCoverageRegistry coverage = new PersistenceCoverageRegistry();
        for (PersistenceEvidenceDimension dimension : PersistenceEvidenceDimension.values()) {
            coverage.publish(dimension, true, "test", 1L);
        }
        PersistenceQuarantineRegistry quarantines = new PersistenceQuarantineRegistry();
        PersistenceScopeFactory scopes = new PersistenceScopeFactory(new byte[32]);
        BreedingPersistenceMutationGate gate = new BreedingPersistenceMutationGate(
                new PersistenceMutationAvailabilityService(
                        new PersistenceStorageHealthService(), quarantines,
                        new PersistenceFeatureCircuitRegistry(), coverage), scopes);
        var parent = scopes.breedingParent("parent-a");
        quarantines.openImmediate(new PersistenceQuarantineRecord(
                "q", "incident", parent, PersistenceDomain.BREEDING_PAIRING,
                "pair_under_verification", PersistenceQuarantineState.ACTIVE,
                "evidence", 1L, 1L, 1L, 0L, null));

        assertFalse(gate.decide("parent-a", "parent-b", UUID.randomUUID(), "world").allowed());
        assertTrue(gate.decide("parent-c", "parent-d", UUID.randomUUID(), "world").allowed());
    }
}
