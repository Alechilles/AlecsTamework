package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Tests role cache selection rules for happiness config priority resolution. */
class TwHappinessConfigPriorityTest {

    @Test
    void buildRoleCachePrefersHigherPriority() throws Exception {
        TwHappinessConfig low = createConfig("Happiness_Low", 0, "Mob_Test");
        TwHappinessConfig high = createConfig("Happiness_High", 7, "Mob_Test");

        DefaultAssetMap<String, TwHappinessConfig> assetMap = new DefaultAssetMap<>();
        seedAssetMap(assetMap, Map.of("low", low, "high", high));

        Map<String, TwHappinessConfig> cache = buildRoleCache(assetMap);

        assertNotNull(cache);
        assertSame(high, cache.get("mob_test"));
    }

    @Test
    void buildRoleCacheBreaksPriorityTieByConfigId() throws Exception {
        TwHappinessConfig laterId = createConfig("Happiness_Zeta", 3, "Mob_Tie");
        TwHappinessConfig earlierId = createConfig("Happiness_Alpha", 3, "Mob_Tie");

        DefaultAssetMap<String, TwHappinessConfig> assetMap = new DefaultAssetMap<>();
        LinkedHashMap<String, TwHappinessConfig> entries = new LinkedHashMap<>();
        entries.put("later", laterId);
        entries.put("earlier", earlierId);
        seedAssetMap(assetMap, entries);

        Map<String, TwHappinessConfig> cache = buildRoleCache(assetMap);

        assertNotNull(cache);
        assertSame(earlierId, cache.get("mob_tie"));
    }

    private TwHappinessConfig createConfig(String id, int priority, String roleId) throws Exception {
        TwHappinessConfig config = new TwHappinessConfig();
        setField(config, "id", id);
        setField(config, "enabled", true);
        setField(config, "priority", priority);
        setField(config, "roleIds", new String[] { roleId });
        return config;
    }

    @SuppressWarnings("unchecked")
    private Map<String, TwHappinessConfig> buildRoleCache(DefaultAssetMap<String, TwHappinessConfig> assetMap) throws Exception {
        Method buildRoleCache = TwHappinessConfig.class.getDeclaredMethod("buildRoleCache", DefaultAssetMap.class);
        buildRoleCache.setAccessible(true);
        return (Map<String, TwHappinessConfig>) buildRoleCache.invoke(null, assetMap);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void seedAssetMap(DefaultAssetMap<String, TwHappinessConfig> assetMap,
                                     Map<String, TwHappinessConfig> entries) throws Exception {
        try {
            assetMap.getAssetMap().putAll(entries);
            return;
        } catch (UnsupportedOperationException ignored) {
        }
        Field mapField = null;
        for (Field field : DefaultAssetMap.class.getDeclaredFields()) {
            if (Map.class.isAssignableFrom(field.getType())) {
                mapField = field;
                break;
            }
        }
        if (mapField == null) {
            throw new IllegalStateException("DefaultAssetMap does not expose a mutable map field.");
        }
        mapField.setAccessible(true);
        mapField.set(assetMap, new LinkedHashMap<>(entries));
    }
}
