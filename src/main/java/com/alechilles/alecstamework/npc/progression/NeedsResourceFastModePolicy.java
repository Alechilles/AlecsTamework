package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.performance.RuntimePressureDomain;
import com.alechilles.alecstamework.performance.RuntimePressureLevel;
import com.alechilles.alecstamework.performance.TameworkRuntimePressureService;
import com.alechilles.alecstamework.settings.NeedsResourceMode;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Chooses whether needs resource seeking should bypass pathing and consume directly from source targets.
 */
public final class NeedsResourceFastModePolicy {
    private NeedsResourceFastModePolicy() {
    }

    public static boolean isFastModeActive(long nowMs) {
        return isFastModeActive(
                TameworkRuntimeSettings.needsResourceMode(NeedsResourceMode.ACCURATE.toConfigValue()),
                TameworkRuntimePressureService.getInstance(),
                nowMs);
    }

    static boolean isFastModeActive(
            @Nullable NeedsResourceMode mode,
            @Nonnull TameworkRuntimePressureService pressureService,
            long nowMs) {
        NeedsResourceMode resolvedMode = mode != null ? mode : NeedsResourceMode.ACCURATE;
        return switch (resolvedMode) {
            case ACCURATE -> false;
            case ALWAYS_FAST -> true;
            case AUTO_FAST -> isHotPressure(pressureService, RuntimePressureDomain.NEEDS_RESOURCE_SEARCH, nowMs)
                    || isHotPressure(pressureService, RuntimePressureDomain.NEEDS_PATH_PREFLIGHT, nowMs);
        };
    }

    private static boolean isHotPressure(
            @Nonnull TameworkRuntimePressureService pressureService,
            @Nonnull RuntimePressureDomain domain,
            long nowMs) {
        return pressureService.isAtLeast(domain, RuntimePressureLevel.HOT, nowMs);
    }
}
