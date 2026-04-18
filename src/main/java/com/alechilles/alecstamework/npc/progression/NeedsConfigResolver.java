package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.persistence.TameworkSettingsStore;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
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
        TameworkSettingsStore.GlobalOverrides overrides = TameworkSettingsStore.loadRuntimeGlobalOverrides();
        if (overrides == null || overrides.needsEnabled() == null) {
            return base;
        }
        return new NeedsSensorConfig(
                overrides.needsEnabled(),
                base.hungerMin(),
                base.hungerMax(),
                base.thirstMin(),
                base.thirstMax()
        );
    }

    public static void clearCache() {
        SENSOR_CONFIG_BY_ROLE.clear();
        SENSOR_CONFIG_BY_ID.clear();
    }

    @Nullable
    private static NeedsSensorConfig resolveBaseSensorConfig(@Nullable Ref<EntityStore> npcRef,
                                                             @Nullable Store<EntityStore> store,
                                                             @Nullable TameworkNeedsComponent component) {
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
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

        String configId = component != null ? component.getConfigId() : null;
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
