package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;

/**
 * Resolves global runtime gates for companion progression systems.
 */
public final class CompanionProgressionSettings {
    private CompanionProgressionSettings() {
    }

    public static boolean isLevelingEnabled() {
        return TameworkRuntimeSettings.levelingEnabled(true);
    }

    public static boolean isTalentsEnabled() {
        return TameworkRuntimeSettings.talentsEnabled(true);
    }
}
