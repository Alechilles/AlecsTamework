package com.alechilles.alecstamework.damage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TraitDamageModifierSystemTest {
    @Test
    void resolveIncomingDamageMultiplierInvertsToughnessMultiplier() {
        assertEquals(1.0, TraitDamageModifierSystem.resolveIncomingDamageMultiplier(1.0), 0.000001);
        assertEquals(0.5, TraitDamageModifierSystem.resolveIncomingDamageMultiplier(2.0), 0.000001);
        assertEquals(1.25, TraitDamageModifierSystem.resolveIncomingDamageMultiplier(0.8), 0.000001);
    }

    @Test
    void resolveIncomingDamageMultiplierFallsBackForInvalidValues() {
        assertEquals(1.0, TraitDamageModifierSystem.resolveIncomingDamageMultiplier(0.0), 0.000001);
        assertEquals(1.0, TraitDamageModifierSystem.resolveIncomingDamageMultiplier(-3.0), 0.000001);
        assertEquals(1.0, TraitDamageModifierSystem.resolveIncomingDamageMultiplier(Double.NaN), 0.000001);
    }
}
