package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import java.lang.reflect.Method;
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
        assetMap.getAssetMap().put("low", low);
        assetMap.getAssetMap().put("high", high);

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
}
