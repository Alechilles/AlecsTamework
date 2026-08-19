package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves needs config for an NPC.
 *
 * <p>Role-based resolution is preferred so priority selection stays consistent across reloads and
 * config edits. Component config IDs are treated as fallback only.
 */
public final class NeedsConfigResolver {
    private static final ConcurrentHashMap<String, NeedsSensorConfig> SENSOR_CONFIG_BY_ROLE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, NeedsSensorConfig> SENSOR_CONFIG_BY_ID = new ConcurrentHashMap<>();
    private static final AtomicLong SENSOR_CONFIG_GENERATION = new AtomicLong();

    private NeedsConfigResolver() {
    }

    @Nullable
    public static TwNeedsConfig resolveConfig(@Nullable Ref<EntityStore> npcRef,
                                              @Nullable Store<EntityStore> store,
                                              @Nullable TameworkNeedsComponent component) {
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        if (roleId != null && !roleId.isBlank()) {
            TwNeedsConfig byRole = TwNeedsConfig.resolveForRole(roleId);
            if (byRole != null) {
                return byRole;
            }
        }

        if (component != null && component.getConfigId() != null && !component.getConfigId().isBlank()) {
            TwNeedsConfig config = TwNeedsConfig.resolveById(component.getConfigId());
            if (config != null) {
                return config;
            }
        }
        return null;
    }

    @Nullable
    public static NeedsSensorConfig resolveSensorConfig(@Nullable Ref<EntityStore> npcRef,
                                                        @Nullable Store<EntityStore> store,
                                                        @Nullable TameworkNeedsComponent component) {
        NeedsSensorConfig base = resolveBaseSensorConfig(npcRef, store, component);
        if (base == null) {
            return null;
        }
        return new NeedsSensorConfig(
                base.enabled() && isRuntimeEnabled(base.enabled()),
                base.hungerMin(),
                base.hungerMax(),
                base.thirstMin(),
                base.thirstMax()
        );
    }

    /**
     * Resolves the stable configured sensor values without applying the
     * mutable runtime enable switch or retaining an ECS value.
     */
    @Nullable
    public static NeedsSensorConfig resolveCompiledSensorConfig(
            @Nullable String roleId,
            @Nullable String configId) {
        if (roleId != null && !roleId.isBlank()) {
            String normalizedRoleId = normalizeKey(roleId);
            NeedsSensorConfig cached = SENSOR_CONFIG_BY_ROLE.get(normalizedRoleId);
            if (cached != null) {
                return cached;
            }
            TwNeedsConfig byRole = TwNeedsConfig.resolveForRole(roleId);
            if (byRole != null) {
                NeedsSensorConfig resolved = createSensorConfig(byRole);
                SENSOR_CONFIG_BY_ROLE.put(normalizedRoleId, resolved);
                return resolved;
            }
        }

        if (configId != null && !configId.isBlank()) {
            String normalizedConfigId = normalizeKey(configId);
            NeedsSensorConfig cached = SENSOR_CONFIG_BY_ID.get(normalizedConfigId);
            if (cached != null) {
                return cached;
            }
            TwNeedsConfig byId = TwNeedsConfig.resolveById(configId);
            if (byId != null) {
                NeedsSensorConfig resolved = createSensorConfig(byId);
                SENSOR_CONFIG_BY_ID.put(normalizedConfigId, resolved);
                return resolved;
            }
        }
        return null;
    }

    /** Returns the generation used to invalidate sensor-local compiled values. */
    public static long sensorConfigGeneration() {
        return SENSOR_CONFIG_GENERATION.get();
    }

    /** Applies the current runtime switch without creating another config value. */
    public static boolean isRuntimeEnabled(@Nullable NeedsSensorConfig config) {
        return config != null && isRuntimeEnabled(config.enabled());
    }

    public static boolean isRuntimeEnabled(@Nullable TwNeedsConfig config) {
        return isRuntimeEnabled(config, TameworkRuntimeSettings.currentOrNull());
    }

    static boolean isRuntimeEnabled(@Nullable TwNeedsConfig config, @Nullable TameworkRuntimeSettings settings) {
        return config != null && config.isEnabled() && isRuntimeEnabled(true, settings);
    }

    private static boolean isRuntimeEnabled(boolean configEnabled) {
        return isRuntimeEnabled(configEnabled, TameworkRuntimeSettings.currentOrNull());
    }

    private static boolean isRuntimeEnabled(boolean configEnabled, @Nullable TameworkRuntimeSettings settings) {
        return configEnabled && (settings == null || settings.needsEnabled());
    }

    public static void clearCache() {
        SENSOR_CONFIG_BY_ROLE.clear();
        SENSOR_CONFIG_BY_ID.clear();
        SENSOR_CONFIG_GENERATION.incrementAndGet();
    }

    @Nullable
    private static NeedsSensorConfig resolveBaseSensorConfig(@Nullable Ref<EntityStore> npcRef,
                                                             @Nullable Store<EntityStore> store,
                                                             @Nullable TameworkNeedsComponent component) {
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        String configId = component != null ? component.getConfigId() : null;
        return resolveCompiledSensorConfig(roleId, configId);
    }

    @Nonnull
    private static NeedsSensorConfig createSensorConfig(@Nonnull TwNeedsConfig config) {
        TwNeedsConfig.ValueSettings values = config.getValues();
        return new NeedsSensorConfig(
                config.isConfiguredEnabled(),
                values.getHungerMin(),
                values.getHungerMax(),
                values.getThirstMin(),
                values.getThirstMax()
        );
    }

    @Nonnull
    private static String normalizeKey(@Nonnull String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public record NeedsSensorConfig(boolean enabled,
                                    double hungerMin,
                                    double hungerMax,
                                    double thirstMin,
                                    double thirstMax) {
    }
}
