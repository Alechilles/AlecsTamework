package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/** Groups avatar debug controls. */
public final class TameworkDebugAvatarCommand extends AbstractCommandCollection {
    public TameworkDebugAvatarCommand() {
        super("avatar", "server.tamework.commands.debugAvatar.description");
        addSubCommand(new TameworkDebugPlayerInputCommand());
        addSubCommand(new TameworkDebugDragonFlightCommand());
        addSubCommand(new TameworkDebugPlayerModelCommand());
    }
}
