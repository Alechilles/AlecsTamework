package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TraitMoveSpeedEffectResolverTest {

    @Test
    void resolveMoveSpeedEffectIdReturnsNullAtNeutralMultiplier() {
        assertNull(TraitMoveSpeedEffectResolver.resolveMoveSpeedEffectId(1.0));
    }

    @Test
    void resolveMoveSpeedEffectIdClampsAndSnapsToKnownSteps() {
        assertEquals("Tw_Trait_MoveSpeed_080", TraitMoveSpeedEffectResolver.resolveMoveSpeedEffectId(0.25));
        assertEquals("Tw_Trait_MoveSpeed_130", TraitMoveSpeedEffectResolver.resolveMoveSpeedEffectId(2.0));
        assertEquals("Tw_Trait_MoveSpeed_110", TraitMoveSpeedEffectResolver.resolveMoveSpeedEffectId(1.12));
        assertEquals("Tw_Trait_MoveSpeed_115", TraitMoveSpeedEffectResolver.resolveMoveSpeedEffectId(1.13));
    }

    @Test
    void resolveMoveSpeedEffectIdIgnoresInvalidNumbers() {
        assertNull(TraitMoveSpeedEffectResolver.resolveMoveSpeedEffectId(Double.NaN));
        assertNull(TraitMoveSpeedEffectResolver.resolveMoveSpeedEffectId(Double.POSITIVE_INFINITY));
        assertNull(TraitMoveSpeedEffectResolver.resolveMoveSpeedEffectId(0.0));
    }

    @Test
    void moveSpeedEffectPrefixCheckMatchesOnlyTraitEffects() {
        assertTrue(TraitMoveSpeedEffectResolver.isTraitMoveSpeedEffectId("Tw_Trait_MoveSpeed_105"));
        assertFalse(TraitMoveSpeedEffectResolver.isTraitMoveSpeedEffectId("Other_Effect"));
        assertFalse(TraitMoveSpeedEffectResolver.isTraitMoveSpeedEffectId(null));
    }
}
