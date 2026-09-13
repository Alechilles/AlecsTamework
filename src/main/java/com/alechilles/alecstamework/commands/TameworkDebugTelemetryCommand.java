package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/** Groups telemetry debug controls. */
public final class TameworkDebugTelemetryCommand extends AbstractCommandCollection {
    public TameworkDebugTelemetryCommand() {
        super("telemetry", "server.tamework.commands.debugTelemetry.description");
        addSubCommand(new TameworkDebugNeedsTelemetryCommand());
        addSubCommand(new TameworkDebugCrashTelemetryCommand());
    }
}
