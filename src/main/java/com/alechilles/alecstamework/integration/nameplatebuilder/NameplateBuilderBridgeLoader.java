package com.alechilles.alecstamework.integration.nameplatebuilder;

import com.alechilles.alecstamework.Tamework;
import java.util.logging.Level;

/**
 * Loads the optional NameplateBuilder integration only when its API is present.
 */
public final class NameplateBuilderBridgeLoader {
    private NameplateBuilderBridgeLoader() {
    }

    public static boolean initialize(Tamework plugin) {
        if (plugin == null || !isNameplateBuilderApiPresent()) {
            return false;
        }
        try {
            NameplateBuilderEntityNameOverrideBridge.initialize(plugin);
            NameplateBuilderCompanionSegmentBridge.initialize(plugin);
            plugin.getLogger().at(Level.INFO).log("NameplateBuilder integration initialized.");
            return true;
        } catch (Throwable ex) {
            plugin.getLogger().at(Level.WARNING).withCause(ex)
                    .log("NameplateBuilder was detected but the Tamework integration failed to initialize.");
            return false;
        }
    }

    private static boolean isNameplateBuilderApiPresent() {
        try {
            Class.forName("com.frotty27.nameplatebuilder.api.NameplateAPI");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
