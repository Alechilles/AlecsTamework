package com.alechilles.alecstamework.vessels.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NpcProfileBondedVesselProfilePortTest {
    @Test
    void missingCapturedProfileRoleUsesMappedTamedClassification() {
        assertEquals("Tamed_NordicDrake",
                NpcProfileBondedVesselProfilePort.canonicalCapturedRole(
                        null, "NordicDrake", "Tamed_NordicDrake"));
    }

    @Test
    void persistedProfileRoleRemainsCanonicalWhenFallbackEvidenceDiffers() {
        assertEquals("Tamed_NordicDrake",
                NpcProfileBondedVesselProfilePort.canonicalCapturedRole(
                        "Tamed_NordicDrake", "NordicDrake", "Tamed_Other"));
    }
}
