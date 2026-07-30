package com.alechilles.alecstamework.npc.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NativeMountSourceRoleTest {

    @Test
    void prefersTheLiveNpcRoleOverTheInheritedActionRole() {
        NativeMountSourceRole source = NativeMountSourceRole.resolve(
                "Tamed_Cow", 42, "AH_Template_Livestock_Tamed", 17);

        assertEquals("Tamed_Cow", source.id());
        assertEquals(42, source.index());
    }

    @Test
    void fallsBackToTheActionRoleWhenTheLiveRoleIsUnavailable() {
        NativeMountSourceRole source = NativeMountSourceRole.resolve(
                "", -1, "Tamed_Cow", 42);

        assertEquals("Tamed_Cow", source.id());
        assertEquals(42, source.index());
    }
}
