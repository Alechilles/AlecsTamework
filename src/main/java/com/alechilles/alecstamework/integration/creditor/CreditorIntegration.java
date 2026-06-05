package com.alechilles.alecstamework.integration.creditor;

import com.creditor.Creditor;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import java.util.logging.Level;

/**
 * Initializes the embedded Creditor library for Tamework's host plugin lifecycle.
 */
public final class CreditorIntegration {
    private CreditorIntegration() {
    }

    public static boolean setup(JavaPlugin plugin) {
        if (plugin == null) {
            return false;
        }
        try {
            Creditor.setup(plugin);
            plugin.getLogger().at(Level.INFO).log("Creditor embedded integration initialized.");
            return true;
        } catch (Throwable ex) {
            plugin.getLogger().at(Level.WARNING).withCause(ex)
                    .log("Creditor embedded integration failed during setup; /credits may be unavailable.");
            return false;
        }
    }

    public static boolean start(JavaPlugin plugin) {
        if (plugin == null) {
            return false;
        }
        try {
            Creditor.start(plugin);
            return true;
        } catch (Throwable ex) {
            plugin.getLogger().at(Level.WARNING).withCause(ex)
                    .log("Creditor embedded integration failed during start.");
            return false;
        }
    }
}
