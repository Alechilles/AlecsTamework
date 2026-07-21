package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselOperationRecord;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class BondedVesselUnifiedPopulationPortTest {
    @Test
    void plannedNpcIdentityIsStablePerOperationAndGeneration() {
        UUID first = BondedVesselUnifiedPopulationPort.plannedNpcUuid(operation("op-1", 3));

        assertEquals(first, BondedVesselUnifiedPopulationPort.plannedNpcUuid(
                operation("op-1", 3)));
        assertNotEquals(first, BondedVesselUnifiedPopulationPort.plannedNpcUuid(
                operation("op-2", 3)));
        assertNotEquals(first, BondedVesselUnifiedPopulationPort.plannedNpcUuid(
                operation("op-1", 4)));
    }

    private static BondedVesselOperationRecord operation(String operationId, long prior) {
        return new BondedVesselOperationRecord(
                operationId, "test", "key-" + operationId, null,
                UUID.randomUUID().toString(), "profile-1",
                BondedVesselOperationRecord.Action.SUMMON,
                BondedVesselOperationRecord.State.APPLYING,
                prior, prior + 1, 2, "config", 1,
                BondedVesselBindingRecord.LifecycleState.STORED,
                BondedVesselBindingRecord.LifecycleState.SUMMONING,
                BondedVesselBindingRecord.LifecycleState.ACTIVE,
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                0, 0, "stored", "active", "source", "target", "{}", "{}",
                null, null, null, "APPLYING", 10, 1, 1, 0, 0);
    }
}
