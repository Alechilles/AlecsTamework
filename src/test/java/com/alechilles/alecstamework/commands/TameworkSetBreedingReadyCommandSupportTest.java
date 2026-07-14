package com.alechilles.alecstamework.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers single-target and area-of-effect breeding-ready command arguments. */
class TameworkSetBreedingReadyCommandSupportTest {
    @Test
    void omittedArgumentsKeepSingleTargetTrueMode() {
        TameworkSetBreedingReadyCommandSupport.Arguments parsed =
                TameworkSetBreedingReadyCommandSupport.parse("/tw setbreedingready");

        assertTrue(parsed.valid());
        assertFalse(parsed.aoe());
        assertNull(parsed.radius());
        assertEquals(TameworkSetBreedingReadyCommandSupport.ReadyMode.TRUE, parsed.mode());
    }

    @Test
    void aoeSwitchSupportsDefaultAndExplicitRadius() {
        TameworkSetBreedingReadyCommandSupport.Arguments defaultRadius =
                TameworkSetBreedingReadyCommandSupport.parse("/tw setbreedingready --aoe");
        TameworkSetBreedingReadyCommandSupport.Arguments explicitRadius =
                TameworkSetBreedingReadyCommandSupport.parse("/tw setbreedingready false aoe 18.5");

        assertTrue(defaultRadius.valid());
        assertTrue(defaultRadius.aoe());
        assertNull(defaultRadius.radius());
        assertEquals(TameworkSetBreedingReadyCommandSupport.ReadyMode.TRUE, defaultRadius.mode());
        assertTrue(explicitRadius.valid());
        assertTrue(explicitRadius.aoe());
        assertEquals(18.5, explicitRadius.radius());
        assertEquals(TameworkSetBreedingReadyCommandSupport.ReadyMode.FALSE, explicitRadius.mode());
    }

    @Test
    void invalidAoeRadiusIsRejected() {
        assertFalse(TameworkSetBreedingReadyCommandSupport.parse(
                "/tw setbreedingready toggle aoe 0"
        ).valid());
        assertFalse(TameworkSetBreedingReadyCommandSupport.parse(
                "/tw setbreedingready true aoe nope"
        ).valid());
    }
}
