package com.alechilles.alecstamework.npc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for role-id-based tame compatibility logic. */
class TamedStateResolverTest {

    @Test
    void treatsRoleIdsStartingWithTamedAsTamed() {
        assertTrue(TamedStateResolver.isTamedRoleId("Tamed_Cow"));
        assertTrue(TamedStateResolver.isTamedRoleId("tamed_wolf"));
        assertTrue(TamedStateResolver.isTamedRoleId("TaMeD_Bear"));
    }

    @Test
    void doesNotTreatNonPrefixedRoleIdsAsTamed() {
        assertFalse(TamedStateResolver.isTamedRoleId("Cow"));
        assertFalse(TamedStateResolver.isTamedRoleId("Wild_Tamed_Cow"));
        assertFalse(TamedStateResolver.isTamedRoleId("PetCat"));
    }

    @Test
    void returnsFalseForMissingRoleIds() {
        assertFalse(TamedStateResolver.isTamedRoleId(null));
        assertFalse(TamedStateResolver.isTamedRoleId(""));
        assertFalse(TamedStateResolver.isTamedRoleId("   "));
    }
}
