package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;

/**
 * Resolves runtime settings that affect command-item travel behavior.
 */
final class CommandTravelSettings {
    private CommandTravelSettings() {
    }

    static boolean isRecallTeleportingEnabled() {
        return TameworkRuntimeSettings.current().recallTeleportingEnabled();
    }
}
