package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.TameworkSettingsStore;

/**
 * Resolves runtime settings that affect command-item travel behavior.
 */
final class CommandTravelSettings {
    private CommandTravelSettings() {
    }

    static boolean isRecallTeleportingEnabled() {
        TameworkSettingsStore.GlobalOverrides overrides = TameworkSettingsStore.loadRuntimeGlobalOverrides();
        return overrides == null
                || overrides.recallTeleportingEnabled() == null
                || overrides.recallTeleportingEnabled();
    }
}
