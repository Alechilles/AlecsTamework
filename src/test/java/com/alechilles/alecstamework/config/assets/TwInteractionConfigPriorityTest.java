package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TwInteractionConfigPriorityTest {

    @Test
    void buildRoleCachePrefersHighestPriority() throws Exception {
        TwInteractionConfig low = new TwInteractionConfig();
        low.enabled = true;
        low.priority = 0;
        low.roleIds = new String[] { "Mob_Test" };

        TwInteractionConfig high = new TwInteractionConfig();
        high.enabled = true;
        high.priority = 5;
        high.roleIds = new String[] { "Mob_Test" };

        DefaultAssetMap<String, TwInteractionConfig> assetMap = new DefaultAssetMap<>();
        seedAssetMap(assetMap, Map.of("low", low, "high", high));

        Method buildRoleCache = TwInteractionConfig.class.getDeclaredMethod(
                "buildRoleCache",
                DefaultAssetMap.class
        );
        buildRoleCache.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, TwInteractionConfig> cache =
                (Map<String, TwInteractionConfig>) buildRoleCache.invoke(null, assetMap);

        assertNotNull(cache);
        assertSame(high, cache.get("mob_test"));
    }

    private static void seedAssetMap(DefaultAssetMap<String, TwInteractionConfig> assetMap,
                                     Map<String, TwInteractionConfig> entries) throws Exception {
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
        mapField.set(assetMap, new HashMap<>(entries));
    }
}
