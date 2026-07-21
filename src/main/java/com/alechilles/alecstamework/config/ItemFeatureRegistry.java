package com.alechilles.alecstamework.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Registry for item feature configs, including defaults and overrides.
 */
public final class ItemFeatureRegistry {
    private final Map<String, ItemFeatureConfig> configsByItemId = new HashMap<>();

    public void register(String itemId, ItemFeatureConfig config) {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(config, "config");
        ItemFeatureConfig existing = configsByItemId.putIfAbsent(itemId, config);
        if (existing != null) {
            throw new IllegalArgumentException("Duplicate item feature binding for item ID: " + itemId);
        }
    }

    public ItemFeatureConfig get(String itemId) {
        if (itemId == null) {
            return null;
        }
        ItemFeatureConfig config = configsByItemId.get(itemId);
        if (config != null) {
            return config;
        }
        // Normalize state variants ("*_State_*" or "*" prefix) back to base item IDs.
        String normalized = normalizeStateItemId(itemId);
        if (normalized != null && !normalized.equals(itemId)) {
            return configsByItemId.get(normalized);
        }
        return null;
    }

    public static String normalizeStateItemId(String itemId) {
        if (itemId == null) {
            return null;
        }
        String trimmed = itemId.startsWith("*") ? itemId.substring(1) : itemId;
        int stateIndex = trimmed.indexOf("_State_");
        if (stateIndex > 0) {
            return trimmed.substring(0, stateIndex);
        }
        return itemId;
    }

    public Map<String, ItemFeatureConfig> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(configsByItemId));
    }

    public void clear() {
        configsByItemId.clear();
    }

    public void registerDefaults() {
        // No code-driven defaults; all item feature configs come from JSON.
    }
}

