package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TwMountedGlideConfigTest {

    @Test
    void nestedSectionsInheritMissingValues() throws Exception {
        TwMountedGlideConfig parent = config("parent", new String[] { "Parent_Role" }, 1);
        setField(parent.getGlide(), "baseSpeed", 12.0);
        setField(parent.getGlide(), "maxSpeed", 24.0);
        setField(parent.getFlap(), "cooldownSeconds", 0.85);
        setField(parent.getFlap(), "upwardBoostStrength", 8.0);
        setField(parent.getAirbrake(), "speedDecay", 7.5);

        TwMountedGlideConfig child = config("child", new String[] { "Child_Role" }, 2);
        setField(child.getGlide(), "baseSpeed", 15.0);
        setField(child.getFlap(), "cooldownSeconds", 0.45);

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("RoleIds", "Glide", "Flap"),
                Map.of(
                        "Glide", Set.of("BaseSpeed"),
                        "Flap", Set.of("CooldownSeconds")
                )
        );

        assertArrayEquals(new String[] { "Child_Role" }, child.getRoleIds());
        assertEquals(15.0, child.getGlide().getBaseSpeed(), 0.0001);
        assertEquals(24.0, child.getGlide().getMaxSpeed(), 0.0001);
        assertEquals(0.45, child.getFlap().getCooldownSeconds(), 0.0001);
        assertEquals(8.0, child.getFlap().getUpwardBoostStrength(), 0.0001);
        assertSame(parent.getAirbrake(), child.getAirbrake());
    }

    @Test
    void roleCachePrefersHigherPriorityAndNormalizesRole() throws Exception {
        TwMountedGlideConfig low = config("low", new String[] { "Example_Role" }, 1);
        TwMountedGlideConfig high = config("high", new String[] { "example_role" }, 5);
        DefaultAssetMap<String, TwMountedGlideConfig> assetMap = new DefaultAssetMap<>();
        seedAssetMap(assetMap, Map.of(low.getId(), low, high.getId(), high));

        Map<String, TwMountedGlideConfig> cache = buildRoleCache(assetMap);

        assertSame(high, cache.get("example_role"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, TwMountedGlideConfig> buildRoleCache(
            DefaultAssetMap<String, TwMountedGlideConfig> assetMap) throws Exception {
        Method method = TwMountedGlideConfig.class.getDeclaredMethod("buildRoleCache", DefaultAssetMap.class);
        method.setAccessible(true);
        return (Map<String, TwMountedGlideConfig>) method.invoke(null, assetMap);
    }

    private static TwMountedGlideConfig config(String id, String[] roles, int priority) throws Exception {
        TwMountedGlideConfig config = new TwMountedGlideConfig();
        setField(config, "id", id);
        setField(config, "enabled", true);
        setField(config, "roleIds", roles);
        setField(config, "priority", priority);
        return config;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void seedAssetMap(DefaultAssetMap<String, TwMountedGlideConfig> assetMap,
                                     Map<String, TwMountedGlideConfig> entries) throws Exception {
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
