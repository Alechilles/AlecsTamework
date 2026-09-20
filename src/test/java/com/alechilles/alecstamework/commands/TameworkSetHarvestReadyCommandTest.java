package com.alechilles.alecstamework.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkSetHarvestReadyCommandTest {
    @Test
    void resolvesExplicitAndToggleReadinessModes() {
        assertEquals(Boolean.TRUE, TameworkSetHarvestReadyCommand.resolveRequestedReadiness("true", false));
        assertEquals(Boolean.FALSE, TameworkSetHarvestReadyCommand.resolveRequestedReadiness("false", true));
        assertEquals(Boolean.FALSE, TameworkSetHarvestReadyCommand.resolveRequestedReadiness("toggle", true));
        assertEquals(Boolean.TRUE, TameworkSetHarvestReadyCommand.resolveRequestedReadiness("toggle", false));
    }

    @Test
    void defaultsToReadyAndRejectsUnknownModes() {
        assertTrue(TameworkSetHarvestReadyCommand.resolveRequestedReadiness(null, false));
        assertFalse(TameworkSetHarvestReadyCommand.resolveRequestedReadiness("off", true));
        assertNull(TameworkSetHarvestReadyCommand.resolveRequestedReadiness("ready", false));
    }
}
