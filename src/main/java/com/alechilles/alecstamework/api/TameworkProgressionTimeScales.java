package com.alechilles.alecstamework.api;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Runtime overrides for progression timing that must be scoped to a single world.
 */
public final class TameworkProgressionTimeScales {
    public static final double DEFAULT_SCALE = 1.0;

    private static final Map<UUID, Double> WORLD_SCALES = new ConcurrentHashMap<>();

    private TameworkProgressionTimeScales() {
    }

    /**
     * Registers a progression time scale for one world. Invalid or default values clear the override.
     */
    public static void registerWorldScale(@Nonnull UUID worldUuid, double scale) {
        if (worldUuid == null) {
            return;
        }
        double sanitized = sanitizeScale(scale);
        if (sanitized == DEFAULT_SCALE) {
            WORLD_SCALES.remove(worldUuid);
            return;
        }
        WORLD_SCALES.put(worldUuid, sanitized);
    }

    public static void clearWorldScale(@Nullable UUID worldUuid) {
        if (worldUuid != null) {
            WORLD_SCALES.remove(worldUuid);
        }
    }

    public static void clearWorldScale(@Nullable World world) {
        clearWorldScale(resolveWorldUuid(world));
    }

    public static double getWorldScale(@Nullable UUID worldUuid) {
        if (worldUuid == null) {
            return DEFAULT_SCALE;
        }
        return sanitizeScale(WORLD_SCALES.get(worldUuid));
    }

    public static double resolveWorldScale(@Nullable Store<EntityStore> store) {
        World world = store != null && store.getExternalData() != null
                ? store.getExternalData().getWorld()
                : null;
        return getWorldScale(resolveWorldUuid(world));
    }

    private static double sanitizeScale(@Nullable Double scale) {
        if (scale == null || !Double.isFinite(scale) || scale <= DEFAULT_SCALE) {
            return DEFAULT_SCALE;
        }
        return scale;
    }

    @Nullable
    private static UUID resolveWorldUuid(@Nullable World world) {
        if (world == null) {
            return null;
        }
        WorldConfig config = world.getWorldConfig();
        return config != null ? config.getUuid() : null;
    }
}
