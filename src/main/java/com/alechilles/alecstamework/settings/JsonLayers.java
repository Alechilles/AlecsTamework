package com.alechilles.alecstamework.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Layers one settings document over another, field by field. Objects are walked; every other value
 * (numbers, text, switches and whole lists) is one setting. A JSON null in the upper layer means
 * "this setting is unset here", which hides the lower layer's value so the built-in default applies.
 */
public final class JsonLayers {
    private JsonLayers() {
    }

    /** {@code over} on top of {@code base}. Neither argument is changed. */
    @Nonnull
    public static JsonObject merge(@Nullable JsonObject base, @Nullable JsonObject over) {
        JsonObject result = base == null ? new JsonObject() : base.deepCopy();
        if (over == null) {
            return result;
        }
        for (Map.Entry<String, JsonElement> entry : over.entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull()) {
                result.remove(entry.getKey());
            } else if (value.isJsonObject()) {
                JsonElement below = result.get(entry.getKey());
                result.add(entry.getKey(), merge(below != null && below.isJsonObject() ? below.getAsJsonObject() : null,
                        value.getAsJsonObject()));
            } else {
                result.add(entry.getKey(), value.deepCopy());
            }
        }
        return result;
    }

    /**
     * The smallest upper layer that gives {@code full} when merged over {@code base}: the settings
     * of {@code full} that differ from {@code base}, and a null for each setting only {@code base} has.
     */
    @Nonnull
    public static JsonObject overrides(@Nonnull JsonObject full, @Nullable JsonObject base) {
        JsonObject result = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : full.entrySet()) {
            JsonElement value = entry.getValue();
            JsonElement below = base == null ? null : base.get(entry.getKey());
            if (value == null || value.isJsonNull()) {
                if (below != null && !below.isJsonNull()) {
                    result.add(entry.getKey(), JsonNull.INSTANCE);
                }
            } else if (value.isJsonObject()) {
                JsonObject inner = overrides(value.getAsJsonObject(),
                        below != null && below.isJsonObject() ? below.getAsJsonObject() : null);
                if (inner.size() > 0 || below == null || !below.isJsonObject()) {
                    result.add(entry.getKey(), inner);
                }
            } else if (!value.equals(below)) {
                result.add(entry.getKey(), value.deepCopy());
            }
        }
        if (base != null) {
            for (Map.Entry<String, JsonElement> entry : base.entrySet()) {
                if (!full.has(entry.getKey()) && !entry.getValue().isJsonNull()) {
                    result.add(entry.getKey(), JsonNull.INSTANCE);
                }
            }
        }
        return result;
    }

    /**
     * The overrides a server keeps when it first joins a network: the settings of its {@code full}
     * local document that differ from both the network's value and the built-in default. A value
     * equal to the default counts as never chosen, because a full local file cannot say otherwise.
     */
    @Nonnull
    public static JsonObject chosenOverrides(@Nonnull JsonObject full, @Nullable JsonObject network,
                                             @Nullable JsonObject defaults) {
        JsonObject result = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : full.entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull()) {
                continue;
            }
            JsonElement networkValue = network == null ? null : network.get(entry.getKey());
            JsonElement defaultValue = defaults == null ? null : defaults.get(entry.getKey());
            if (value.isJsonObject()) {
                JsonObject inner = chosenOverrides(value.getAsJsonObject(),
                        networkValue != null && networkValue.isJsonObject() ? networkValue.getAsJsonObject() : null,
                        defaultValue != null && defaultValue.isJsonObject() ? defaultValue.getAsJsonObject() : null);
                if (inner.size() > 0) {
                    result.add(entry.getKey(), inner);
                }
            } else if (!value.equals(networkValue) && !value.equals(defaultValue)) {
                result.add(entry.getKey(), value.deepCopy());
            }
        }
        return result;
    }

    /** How many settings a layer sets or unsets. */
    public static int count(@Nullable JsonObject layer) {
        if (layer == null) {
            return 0;
        }
        int count = 0;
        for (Map.Entry<String, JsonElement> entry : layer.entrySet()) {
            JsonElement value = entry.getValue();
            count += value != null && value.isJsonObject() ? count(value.getAsJsonObject()) : 1;
        }
        return count;
    }
}
