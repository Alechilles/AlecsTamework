package com.alechilles.alecstamework.config;

import com.alechilles.alecstamework.config.assets.TwNameItemConfig;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Registry for naming item configs keyed by item id.
 */
public final class NameItemRegistry {
    private final Map<String, TwNameItemConfig> configsByItemId = new HashMap<>();

    public void register(String itemId, TwNameItemConfig config) {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(config, "config");
        configsByItemId.put(itemId, config);
    }

    public TwNameItemConfig get(String itemId) {
        if (itemId == null) {
            return null;
        }
        TwNameItemConfig config = configsByItemId.get(itemId);
        if (config != null) {
            return config;
        }
        String normalized = ItemFeatureRegistry.normalizeStateItemId(itemId);
        if (normalized != null && !normalized.equals(itemId)) {
            return configsByItemId.get(normalized);
        }
        return null;
    }

    public Map<String, TwNameItemConfig> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(configsByItemId));
    }

    public void clear() {
        configsByItemId.clear();
    }
}
