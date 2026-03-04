package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Tests coop cache selection rules for coop-id priority resolution. */
class TwCoopConfigPriorityTest {

    @Test
    void buildCoopCachePrefersHigherPriority() throws Exception {
        TwCoopConfig low = createConfig("Coop_Low", 0, "Coop_Chicken");
        TwCoopConfig high = createConfig("Coop_High", 8, "Coop_Chicken");

        DefaultAssetMap<String, TwCoopConfig> assetMap = new DefaultAssetMap<>();
        seedAssetMap(assetMap, Map.of("low", low, "high", high));

        Map<String, TwCoopConfig> cache = buildCoopCache(assetMap);

        assertNotNull(cache);
        assertSame(high, cache.get("coop_chicken"));
    }

    @Test
    void buildCoopCacheBreaksPriorityTieByConfigId() throws Exception {
        TwCoopConfig laterId = createConfig("Coop_Zeta", 3, "Coop_Tie");
        TwCoopConfig earlierId = createConfig("Coop_Alpha", 3, "Coop_Tie");

        DefaultAssetMap<String, TwCoopConfig> assetMap = new DefaultAssetMap<>();
        LinkedHashMap<String, TwCoopConfig> entries = new LinkedHashMap<>();
        entries.put("later", laterId);
        entries.put("earlier", earlierId);
        seedAssetMap(assetMap, entries);

        Map<String, TwCoopConfig> cache = buildCoopCache(assetMap);

        assertNotNull(cache);
        assertSame(earlierId, cache.get("coop_tie"));
    }

    private TwCoopConfig createConfig(String id, int priority, String coopId) throws Exception {
        TwCoopConfig config = new TwCoopConfig();
        setField(config, "id", id);
        setField(config, "enabled", true);
        setField(config, "priority", priority);
        setField(config, "coopId", coopId);
        return config;
    }

    @SuppressWarnings("unchecked")
    private Map<String, TwCoopConfig> buildCoopCache(DefaultAssetMap<String, TwCoopConfig> assetMap) throws Exception {
        Method buildCoopCache = TwCoopConfig.class.getDeclaredMethod("buildCoopCache", DefaultAssetMap.class);
        buildCoopCache.setAccessible(true);
        return (Map<String, TwCoopConfig>) buildCoopCache.invoke(null, assetMap);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void seedAssetMap(DefaultAssetMap<String, TwCoopConfig> assetMap,
                                     Map<String, TwCoopConfig> entries) throws Exception {
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
