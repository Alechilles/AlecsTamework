package com.alechilles.alecstamework.vessels.runtime;

import com.alechilles.alecstamework.api.BondedVesselState;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BondedVesselDurabilityProjectionTest {
    @Test
    void deathBreaksStoneAndRepairRestoresIt() {
        assertEquals(0.0D, HytaleBondedVesselExactInventoryPort.replacementDurability(
                1.0D, 1.0D, BondedVesselState.ACTIVE,
                BondedVesselBindingRecord.LifecycleState.DEAD));
        assertEquals(1.0D, HytaleBondedVesselExactInventoryPort.replacementDurability(
                0.0D, 1.0D, BondedVesselState.DEAD,
                BondedVesselBindingRecord.LifecycleState.STORED));
    }

    @Test
    void summonAndStorePreserveDurability() {
        assertEquals(1.0D, HytaleBondedVesselExactInventoryPort.replacementDurability(
                1.0D, 1.0D, BondedVesselState.STORED,
                BondedVesselBindingRecord.LifecycleState.ACTIVE));
        assertEquals(1.0D, HytaleBondedVesselExactInventoryPort.replacementDurability(
                1.0D, 1.0D, BondedVesselState.ACTIVE,
                BondedVesselBindingRecord.LifecycleState.STORED));
    }
}
