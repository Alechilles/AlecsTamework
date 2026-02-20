package com.alechilles.alecstamework.config;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Registry for command item configs keyed by item id.
 */
public final class CommandItemRegistry {
    private final Map<String, TwCommandItemConfig> configsByItemId = new HashMap<>();

    public void register(String itemId, TwCommandItemConfig config) {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(config, "config");
        configsByItemId.put(itemId, config);
    }

    public TwCommandItemConfig get(String itemId) {
        if (itemId == null) {
            return null;
        }
        TwCommandItemConfig config = configsByItemId.get(itemId);
        if (config != null) {
            return config;
        }
        String normalized = ItemFeatureRegistry.normalizeStateItemId(itemId);
        if (normalized != null && !normalized.equals(itemId)) {
            return configsByItemId.get(normalized);
        }
        return null;
    }

    public Map<String, TwCommandItemConfig> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(configsByItemId));
    }

    public void clear() {
        configsByItemId.clear();
    }
}
