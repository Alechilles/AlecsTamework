package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Tests role cache selection rules for needs config priority resolution. */
class TwNeedsConfigPriorityTest {

    @Test
    void buildRoleCachePrefersHigherPriority() throws Exception {
        TwNeedsConfig low = createConfig("Needs_Low", 0, "Mob_Test");
        TwNeedsConfig high = createConfig("Needs_High", 9, "Mob_Test");

        DefaultAssetMap<String, TwNeedsConfig> assetMap = new DefaultAssetMap<>();
        seedAssetMap(assetMap, Map.of("low", low, "high", high));

        Map<String, TwNeedsConfig> cache = buildRoleCache(assetMap);

        assertNotNull(cache);
        assertSame(high, cache.get("mob_test"));
    }

    @Test
    void buildRoleCacheBreaksPriorityTieByConfigId() throws Exception {
        TwNeedsConfig laterId = createConfig("Needs_Zeta", 3, "Mob_Tie");
        TwNeedsConfig earlierId = createConfig("Needs_Alpha", 3, "Mob_Tie");

        DefaultAssetMap<String, TwNeedsConfig> assetMap = new DefaultAssetMap<>();
        LinkedHashMap<String, TwNeedsConfig> entries = new LinkedHashMap<>();
        entries.put("later", laterId);
        entries.put("earlier", earlierId);
        seedAssetMap(assetMap, entries);

        Map<String, TwNeedsConfig> cache = buildRoleCache(assetMap);

        assertNotNull(cache);
        assertSame(earlierId, cache.get("mob_tie"));
    }

    private TwNeedsConfig createConfig(String id, int priority, String roleId) throws Exception {
        TwNeedsConfig config = new TwNeedsConfig();
        setField(config, "id", id);
        setField(config, "enabled", true);
        setField(config, "priority", priority);
        setField(config, "roleIds", new String[] { roleId });
        return config;
    }

    @SuppressWarnings("unchecked")
    private Map<String, TwNeedsConfig> buildRoleCache(DefaultAssetMap<String, TwNeedsConfig> assetMap) throws Exception {
        Method buildRoleCache = TwNeedsConfig.class.getDeclaredMethod("buildRoleCache", DefaultAssetMap.class);
        buildRoleCache.setAccessible(true);
        return (Map<String, TwNeedsConfig>) buildRoleCache.invoke(null, assetMap);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void seedAssetMap(DefaultAssetMap<String, TwNeedsConfig> assetMap,
                                     Map<String, TwNeedsConfig> entries) throws Exception {
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
