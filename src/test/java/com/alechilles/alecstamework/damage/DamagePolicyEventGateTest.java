package com.alechilles.alecstamework.damage;

import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DamagePolicyEventGateTest {
    @Test
    void nullAndAlreadyCancelledDamageSkipPolicyEvaluation() {
        Damage damage = new Damage(Damage.NULL_SOURCE, 0, 5.0f);
        assertFalse(DamagePolicyEventGate.shouldSkip(damage));

        damage.setCancelled(true);

        assertTrue(DamagePolicyEventGate.shouldSkip(damage));
        assertTrue(DamagePolicyEventGate.shouldSkip(null));
    }
}
