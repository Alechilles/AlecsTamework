package com.alechilles.alecstamework.items;

import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;

/**
 * Lightweight debug logger for coop identity diagnostics.
 */
public final class CoopDebugLogger {
    private static final Logger LOGGER = Logger.getLogger("Alec's Tamework!");
    private static volatile boolean enabled;

    private CoopDebugLogger() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean setEnabled(boolean value) {
        enabled = value;
        return enabled;
    }

    public static void log(@Nonnull String message) {
        if (!enabled || message == null) {
            return;
        }
        LOGGER.log(Level.INFO, "[tw-debug-coop] " + message);
    }
}
