package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.CommandSender;
import javax.annotation.Nullable;

/**
 * Permission helper for the in-game Tamework API self-test command suite.
 */
public final class TameworkApiTestPermission {
    public static final String NODE = "tamework.api.test";

    private TameworkApiTestPermission() {
    }

    public static boolean hasAccess(@Nullable CommandSender sender) {
        if (sender == null) {
            return false;
        }
        return sender.hasPermission(NODE)
                || sender.hasPermission("operator")
                || sender.hasPermission("Operator")
                || sender.hasPermission("admin")
                || sender.hasPermission("Admin")
                || sender.hasPermission("op")
                || sender.hasPermission("OP");
    }
}
