package com.alechilles.alecstamework.npc.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Verifies that a native mount retains its live source-role profile after role replacement. */
class NativeMountMovementProfileMemoryTest {

    @Test
    void rememberedLiveProfileWinsOverThePostMountFallbackProfile() {
        NativeMountMovementProfileMemory memory = new NativeMountMovementProfileMemory();

        memory.remember("Tamed_Trillodon", "AH_Mount_Trillodon");

        assertEquals("AH_Mount_Trillodon", memory.resolve("Tamed_Trillodon", "Mount"));
    }

    @Test
    void unresolvedRoleUsesTheProfileResolvedFromItsCurrentScope() {
        NativeMountMovementProfileMemory memory = new NativeMountMovementProfileMemory();

        assertEquals("AH_Mount_Cow", memory.resolve("Tamed_Cow", "AH_Mount_Cow"));
    }
}
