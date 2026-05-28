package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.persistence.TameworkSettingsStore;
import javax.annotation.Nullable;

/**
 * Resolves global runtime gates for companion progression systems.
 */
public final class CompanionProgressionSettings {
    private CompanionProgressionSettings() {
    }

    public static boolean isLevelingEnabled() {
        return isLevelingEnabled(TameworkSettingsStore.loadRuntimeGlobalOverrides());
    }

    public static boolean isTalentsEnabled() {
        return isTalentsEnabled(TameworkSettingsStore.loadRuntimeGlobalOverrides());
    }

    static boolean isLevelingEnabled(@Nullable TameworkSettingsStore.GlobalOverrides overrides) {
        return overrides == null || overrides.levelingEnabled() == null || overrides.levelingEnabled();
    }

    static boolean isTalentsEnabled(@Nullable TameworkSettingsStore.GlobalOverrides overrides) {
        return overrides == null || overrides.talentsEnabled() == null || overrides.talentsEnabled();
    }
}
