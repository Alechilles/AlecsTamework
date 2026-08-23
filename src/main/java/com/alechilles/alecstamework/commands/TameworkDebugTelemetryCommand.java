package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/** Groups telemetry debug controls. */
public final class TameworkDebugTelemetryCommand extends AbstractCommandCollection {
    public TameworkDebugTelemetryCommand() {
        super("telemetry", "Tamework telemetry debug commands.");
        addSubCommand(new TameworkDebugNeedsTelemetryCommand());
        addSubCommand(new TameworkDebugCrashTelemetryCommand());
    }
}
