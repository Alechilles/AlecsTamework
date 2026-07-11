package com.alechilles.alecstamework.npc.systems;

import com.hypixel.hytale.server.core.modules.entity.damage.DeferredCorpseRemoval;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionPermanentDeathHoldTest {
    @Test
    void markerSurvivesComponentCloningWithoutMatchingVanillaTimers() {
        DeferredCorpseRemoval hold = CompanionPermanentDeathHold.create("Particles_Test");

        assertTrue(CompanionPermanentDeathHold.isHold(hold));
        assertTrue(CompanionPermanentDeathHold.isHold(
                (DeferredCorpseRemoval) hold.clone()
        ));
        assertTrue(CompanionPermanentDeathHold.isHold(
                new DeferredCorpseRemoval(Double.MAX_VALUE, "Particles_Test")
        ));
        assertFalse(CompanionPermanentDeathHold.isHold(
                new DeferredCorpseRemoval(5.0, "Particles_Test")
        ));
    }
}
