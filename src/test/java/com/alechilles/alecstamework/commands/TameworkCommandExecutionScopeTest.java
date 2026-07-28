package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkCommandExecutionScopeTest {

    @Test
    void serverGlobalDiagnosticsAndTogglesDoNotRequirePlayerContext() {
        Class<?>[] serverCommands = {
                TameworkPatchesStatusCommand.class,
                TameworkApiTestRunCommand.class,
                TameworkApiTestStatusCommand.class,
                TameworkDebugCrashTelemetryCommand.class,
                TameworkDebugDbCommand.class,
                TameworkDebugCoopCommand.class,
                TameworkDebugDespawnCommand.class,
                TameworkDebugFlyingCompanionCommand.class,
                TameworkDebugHarvestCommand.class,
                TameworkDebugHookCommand.class,
                TameworkDebugLagCommand.class,
                TameworkDebugNeedsConsumeCommand.class,
                TameworkDebugNeedsDamageCommand.class,
                TameworkDebugNeedsSeekCommand.class,
                TameworkDebugNeedsTelemetryCommand.class,
                TameworkDebugPromptCommand.class,
                TameworkDebugRespawnTraceCommand.class,
                TameworkDebugRideCommand.class,
                TameworkDebugSpawnerCommand.class,
                TameworkDebugSpawnerLocationCommand.class,
                TameworkDebugTargetHudCommand.class,
                TameworkDebugXpEventsCommand.class
        };

        for (Class<?> command : serverCommands) {
            assertTrue(AbstractTameworkServerCommand.class.isAssignableFrom(command), command.getSimpleName());
            assertFalse(AbstractPlayerCommand.class.isAssignableFrom(command), command.getSimpleName());
        }
    }

    @Test
    void worldScopedAdminCommandsAcceptExplicitWorldWithoutRequiringPlayer() {
        Class<?>[] worldCommands = {
                TameworkReloadConfigCommand.class,
                TameworkPatchesReloadCommand.class,
                TameworkNpcCleanCommand.class,
                TameworkFindNpcCommand.class,
                TameworkGetAlarmCommand.class
        };

        for (Class<?> command : worldCommands) {
            assertTrue(AbstractWorldCommand.class.isAssignableFrom(command), command.getSimpleName());
            assertFalse(AbstractPlayerCommand.class.isAssignableFrom(command), command.getSimpleName());
        }
    }

    @Test
    void playerUiAndPlayerFixtureCommandsRemainPlayerScoped() {
        Class<?>[] playerCommands = {
                TameworkConfigCommand.class,
                TameworkSettingsCommand.class,
                TameworkNewsCommand.class,
                TameworkApiTestPrepareCommand.class,
                TameworkApiTestResetCommand.class,
                TameworkNpcSpawnTamedCommand.class,
                TameworkShowHitboxesCommand.class,
                TameworkShowSpawnMarkersCommand.class
        };

        for (Class<?> command : playerCommands) {
            assertTrue(AbstractPlayerCommand.class.isAssignableFrom(command), command.getSimpleName());
        }
    }
}
