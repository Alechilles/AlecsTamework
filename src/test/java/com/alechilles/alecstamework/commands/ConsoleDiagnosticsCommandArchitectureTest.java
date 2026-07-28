package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleDiagnosticsCommandArchitectureTest {
    @Test
    void readOnlySelfTestCommandsAreConsoleCapable() {
        assertTrue(AbstractTameworkServerCommand.class.isAssignableFrom(TameworkApiTestRunCommand.class));
        assertTrue(AbstractTameworkServerCommand.class.isAssignableFrom(TameworkApiTestStatusCommand.class));
        assertFalse(AbstractPlayerCommand.class.isAssignableFrom(TameworkApiTestRunCommand.class));
        assertFalse(AbstractPlayerCommand.class.isAssignableFrom(TameworkApiTestStatusCommand.class));
    }

    @Test
    void fixtureMutatingCommandsRemainPlayerScoped() {
        assertTrue(AbstractPlayerCommand.class.isAssignableFrom(TameworkApiTestPrepareCommand.class));
        assertTrue(AbstractPlayerCommand.class.isAssignableFrom(TameworkApiTestResetCommand.class));
    }
}
