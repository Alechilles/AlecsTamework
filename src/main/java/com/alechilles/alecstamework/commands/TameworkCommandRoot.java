package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * Root /tw command dispatcher.
 */
public final class TameworkCommandRoot extends AbstractCommandCollection {
    public static final String ROOT_PERMISSION = "tamework.command.tw";

    public TameworkCommandRoot() {
        super("tw", "Tamework commands.");
        requirePermission(ROOT_PERMISSION);
        setPermissionGroups(TameworkConfigPermission.adminPermissionGroups());
        addSubCommand(new TameworkSetOwnerCommand());
        addSubCommand(new TameworkGetOwnerCommand());
        addSubCommand(new TameworkGetHappinessCommand());
        addSubCommand(new TameworkGetNeedsCommand());
        addSubCommand(new TameworkGetLifeStageCommand());
        addSubCommand(new TameworkSetHappinessCommand());
        addSubCommand(new TameworkSetLevelCommand());
        addSubCommand(new TameworkSetNeedsCommand());
        addSubCommand(new TameworkSetHungerCommand());
        addSubCommand(new TameworkSetThirstCommand());
        addSubCommand(new TameworkSetBreedingReadyCommand());
        addSubCommand(new TameworkGetTraitsCommand());
        addSubCommand(new TameworkSetTraitsCommand());
        addSubCommand(new TameworkAddTraitCommand());
        addSubCommand(new TameworkGetTamedCommand());
        addSubCommand(new TameworkSetTamedCommand());
        addSubCommand(new TameworkFindNpcCommand());
        addSubCommand(new TameworkNpcSpawnTamedCommand());
        addSubCommand(new TameworkNpcCleanCommand());
        addSubCommand(new TameworkGetAlarmCommand());
        addSubCommand(new TameworkGetFlockDebugCommand());
        addSubCommand(new TameworkApiCommandCollection());
        addSubCommand(new TameworkConfigCommand());
        addSubCommand(new TameworkCoopCommand());
        addSubCommand(new TameworkPatchesCommand());
        addSubCommand(new TameworkSettingsCommand());
        addSubCommand(new TameworkNewsCommand());
        addSubCommand(new TameworkReloadConfigCommand());
        addSubCommand(new TameworkDebugHookCommand());
        addSubCommand(new TameworkDebugSpawnerCommand());
        addSubCommand(new TameworkDebugSpawnerLocationCommand());
        addSubCommand(new TameworkDebugPromptCommand());
        addSubCommand(new TameworkDebugRideCommand());
        addSubCommand(new TameworkDebugPlayerModelCommand());
        addSubCommand(new TameworkDebugPlayerInputCommand());
        addSubCommand(new TameworkDebugDragonFlightCommand());
        addSubCommand(new TameworkDebugDespawnCommand());
        addSubCommand(new TameworkDebugLagCommand());
        addSubCommand(new TameworkDebugTargetHudCommand());
        addSubCommand(new TameworkDebugCoopCommand());
        addSubCommand(new TameworkDebugNeedsConsumeCommand());
        addSubCommand(new TameworkDebugNeedsDamageCommand());
        addSubCommand(new TameworkDebugNeedsSeekCommand());
        addSubCommand(new TameworkDebugNeedsTelemetryCommand());
        addSubCommand(new TameworkDebugHarvestCommand());
        addSubCommand(new TameworkDebugRespawnTraceCommand());
        addSubCommand(new TameworkDebugFlyingCompanionCommand());
        addSubCommand(new TameworkDebugXpEventsCommand());
        addSubCommand(new TameworkShowHitboxesCommand());
        addSubCommand(new TameworkShowSpawnMarkersCommand());
        addSubCommand(new TameworkDeleteSpawnMarkerCommand());
        addSubCommand(new TameworkDebugDbCommand());
        addSubCommand(new TameworkDebugCrashTelemetryCommand());
        addSubCommand(new TameworkDebugReviveReadyCommand());
    }
}
