package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CompanionMovementSpeedEffectIdResolverTest {
    private final CompanionMovementSpeedEffectIdResolver resolver = new CompanionMovementSpeedEffectIdResolver();

    @Test
    void resolvesSupportedNonNeutralMultipliersToManagedEffectIds() {
        assertEquals("Tw_MovementSpeed_050", resolver.resolveManagedEffectId(0.50));
        assertEquals("Tw_MovementSpeed_105", resolver.resolveManagedEffectId(1.05));
        assertEquals("Tw_MovementSpeed_200", resolver.resolveManagedEffectId(2.00));
    }

    @Test
    void resolvesExactlyNeutralMultiplierToNoManagedEffect() {
        assertNull(resolver.resolveManagedEffectId(1.00));
    }

    @Test
    void classifiesOnlySupportedManagedIdsAndLegacyIdsSeparately() {
        assertTrue(resolver.isManagedEffectId("Tw_MovementSpeed_105"));
        assertFalse(resolver.isManagedEffectId("Tw_MovementSpeed_100"));
        assertFalse(resolver.isManagedEffectId("Tw_MovementSpeed_107"));
        assertFalse(resolver.isManagedEffectId(null));
        assertTrue(resolver.isLegacyEffectId("Tw_Trait_MoveSpeed_105"));
        assertFalse(resolver.isLegacyEffectId("Tw_MovementSpeed_105"));
        assertFalse(resolver.isLegacyEffectId(null));
    }
}
