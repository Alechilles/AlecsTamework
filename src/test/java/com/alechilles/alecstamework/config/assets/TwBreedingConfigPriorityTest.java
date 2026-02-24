package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Tests role cache selection rules for breeding config priority resolution. */
class TwBreedingConfigPriorityTest {

    @Test
    void buildRoleCachePrefersHigherPriority() throws Exception {
        TwBreedingConfig low = createConfig("Breeding_Low", 0, "Mob_Test");
        TwBreedingConfig high = createConfig("Breeding_High", 5, "Mob_Test");

        DefaultAssetMap<String, TwBreedingConfig> assetMap = new DefaultAssetMap<>();
        seedAssetMap(assetMap, Map.of("low", low, "high", high));

        Map<String, TwBreedingConfig> cache = buildRoleCache(assetMap);

        assertNotNull(cache);
        assertSame(high, cache.get("mob_test"));
    }

    @Test
    void buildRoleCacheBreaksPriorityTieByConfigId() throws Exception {
        TwBreedingConfig laterId = createConfig("Breeding_Zeta", 3, "Mob_Tie");
        TwBreedingConfig earlierId = createConfig("Breeding_Alpha", 3, "Mob_Tie");

        DefaultAssetMap<String, TwBreedingConfig> assetMap = new DefaultAssetMap<>();
        LinkedHashMap<String, TwBreedingConfig> entries = new LinkedHashMap<>();
        entries.put("later", laterId);
        entries.put("earlier", earlierId);
        seedAssetMap(assetMap, entries);

        Map<String, TwBreedingConfig> cache = buildRoleCache(assetMap);

        assertNotNull(cache);
        assertSame(earlierId, cache.get("mob_tie"));
    }

    private TwBreedingConfig createConfig(String id, int priority, String roleId) throws Exception {
        TwBreedingConfig config = new TwBreedingConfig();
        setField(config, "id", id);
        setField(config, "enabled", true);
        setField(config, "priority", priority);
        setField(config, "roleIds", new String[] { roleId });
        return config;
    }

    @SuppressWarnings("unchecked")
    private Map<String, TwBreedingConfig> buildRoleCache(DefaultAssetMap<String, TwBreedingConfig> assetMap) throws Exception {
        Method buildRoleCache = TwBreedingConfig.class.getDeclaredMethod("buildRoleCache", DefaultAssetMap.class);
        buildRoleCache.setAccessible(true);
        return (Map<String, TwBreedingConfig>) buildRoleCache.invoke(null, assetMap);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void seedAssetMap(DefaultAssetMap<String, TwBreedingConfig> assetMap,
                                     Map<String, TwBreedingConfig> entries) throws Exception {
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
