package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/** Toggleable diagnostics for the command target HUD runtime path. */
public final class CommandTargetHudDebugLog {
    private static volatile boolean enabled;

    private CommandTargetHudDebugLog() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean setEnabled(boolean nextEnabled) {
        enabled = nextEnabled;
        return enabled;
    }

    public static boolean toggle() {
        enabled = !enabled;
        return enabled;
    }

    public static void info(@Nonnull String message) {
        if (!enabled) {
            return;
        }
        Tamework plugin = Tamework.getInstance();
        if (plugin == null || plugin.getLogger() == null) {
            return;
        }
        plugin.getLogger().at(Level.INFO).log("Command target HUD debug: " + message);
    }
}
