package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedItem;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.util.ArrayList;

/** Interaction item parser. */
final class InteractionItemParser {
    private InteractionItemParser() {
    }

    static String[] parseItemIdsFromParam(String[] rawValues) {
        if (rawValues == null || rawValues.length == 0) {
            return null;
        }
        if (rawValues.length == 1 && looksLikeJsonArray(rawValues[0])) {
            String[] parsed = parseItemIdsFromJson(rawValues[0]);
            if (parsed != null && parsed.length > 0) {
                return parsed;
            }
            return null;
        }
        return filterItemIds(rawValues);
    }

    static String[] parseItemIdsFromJson(String json) {
        if (!looksLikeJsonArray(json)) {
            return null;
        }
        JsonArray array = parseJsonArray(json);
        if (array == null) {
            return null;
        }
        ArrayList<String> items = new ArrayList<>();
        for (JsonElement element : array) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                String value = element.getAsString();
                if (value != null && !value.isBlank()) {
                    items.add(value);
                }
                continue;
            }
            if (element.isJsonObject()) {
                JsonObject obj = element.getAsJsonObject();
                String item = firstNonBlank(getJsonString(obj, "Item"), getJsonString(obj, "item"));
                if (item != null && !item.isBlank()) {
                    items.add(item);
                }
            }
        }
        return items.isEmpty() ? null : items.toArray(new String[0]);
    }

    static FeedItem[] parseFeedItemsFromJson(String json) {
        if (!looksLikeJsonArray(json)) {
            return null;
        }
        JsonArray array = parseJsonArray(json);
        if (array == null) {
            return null;
        }
        ArrayList<FeedItem> items = new ArrayList<>();
        for (JsonElement element : array) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                String value = element.getAsString();
                if (value != null && !value.isBlank()) {
                    items.add(new FeedItem(value, null));
                }
                continue;
            }
            if (element.isJsonObject()) {
                JsonObject obj = element.getAsJsonObject();
                String item = firstNonBlank(getJsonString(obj, "Item"), getJsonString(obj, "item"));
                if (item == null || item.isBlank()) {
                    continue;
                }
                Double heal = firstNonNull(getJsonNumber(obj, "Heal"), getJsonNumber(obj, "heal"));
                items.add(new FeedItem(item, heal));
            }
        }
        return items.isEmpty() ? null : items.toArray(new FeedItem[0]);
    }

    static FeedItem[] toFeedItems(String[] itemIds) {
        if (itemIds == null || itemIds.length == 0) {
            return new FeedItem[0];
        }
        ArrayList<FeedItem> items = new ArrayList<>();
        for (String itemId : itemIds) {
            if (itemId == null || itemId.isBlank()) {
                continue;
            }
            items.add(new FeedItem(itemId, null));
        }
        return items.toArray(new FeedItem[0]);
    }

    static String[] extractItemIds(FeedItem[] items) {
        if (items == null || items.length == 0) {
            return new String[0];
        }
        ArrayList<String> ids = new ArrayList<>();
        for (FeedItem item : items) {
            if (item == null) {
                continue;
            }
            String itemId = item.getItem();
            if (itemId != null && !itemId.isBlank()) {
                ids.add(itemId);
            }
        }
        return ids.toArray(new String[0]);
    }

    static String[] filterItemIds(String[] rawValues) {
        if (rawValues == null || rawValues.length == 0) {
            return null;
        }
        ArrayList<String> items = new ArrayList<>();
        for (String item : rawValues) {
            if (item == null || item.isBlank()) {
                continue;
            }
            items.add(item);
        }
        return items.isEmpty() ? null : items.toArray(new String[0]);
    }

    static boolean looksLikeJsonArray(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("[") && trimmed.endsWith("]");
    }

    private static JsonArray parseJsonArray(String json) {
        try {
            JsonElement element = JsonParser.parseString(json.trim());
            if (element != null && element.isJsonArray()) {
                return element.getAsJsonArray();
            }
        } catch (JsonSyntaxException ignored) {
        }
        return null;
    }

    private static String getJsonString(JsonObject object, String key) {
        if (object == null || key == null) {
            return null;
        }
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return element.getAsString();
        }
        return null;
    }

    private static Double getJsonNumber(JsonObject object, String key) {
        if (object == null || key == null) {
            return null;
        }
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return element.getAsDouble();
        }
        return null;
    }

    private static String firstNonBlank(String primary, String secondary) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (secondary != null && !secondary.isBlank()) {
            return secondary;
        }
        return null;
    }

    private static Double firstNonNull(Double primary, Double secondary) {
        return primary != null ? primary : secondary;
    }
}
