package com.alechilles.alecstamework.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.logger.HytaleLogger;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public final class ItemFeatureConfigLoader {
    public int loadFromResource(String resourcePath,
                                ItemFeatureRegistry registry,
                                HytaleLogger logger) {
        if (resourcePath == null || resourcePath.isBlank()) {
            logger.at(Level.WARNING).log("Item feature config path is blank.");
            return 0;
        }
        InputStream stream = ItemFeatureConfigLoader.class
                .getClassLoader()
                .getResourceAsStream(resourcePath);
        if (stream == null) {
            logger.at(Level.INFO).log("No item feature config found at: " + resourcePath);
            return 0;
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return loadFromReader(reader, registry, logger, resourcePath);
        } catch (Exception ex) {
            logger.at(Level.WARNING).withCause(ex).log("Failed to load item feature config from resource: " + resourcePath);
            return 0;
        }
    }

    public int loadFromReader(Reader reader,
                              ItemFeatureRegistry registry,
                              HytaleLogger logger,
                              String sourceLabel) {
        if (reader == null) {
            return 0;
        }
        String label = sourceLabel == null || sourceLabel.isBlank() ? "<unknown>" : sourceLabel;
        try {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                logger.at(Level.WARNING).log("Item feature config root must be a JSON object: " + label);
                return 0;
            }
            return loadFromJsonObject(root.getAsJsonObject(), registry, logger, label);
        } catch (Exception ex) {
            logger.at(Level.WARNING).withCause(ex).log("Failed to load item feature config: " + label);
            return 0;
        }
    }

    private int loadFromJsonObject(JsonObject rootObj,
                                   ItemFeatureRegistry registry,
                                   HytaleLogger logger,
                                   String sourceLabel) {
        if (rootObj == null) {
            return 0;
        }
        JsonObject itemsObj = rootObj.getAsJsonObject("Items");
        if (itemsObj == null) {
            logger.at(Level.WARNING).log("Item feature config missing 'Items' object: " + sourceLabel);
            return 0;
        }
        int loaded = 0;
        for (Map.Entry<String, JsonElement> entry : itemsObj.entrySet()) {
            String itemId = entry.getKey();
            if (!entry.getValue().isJsonObject()) {
                logger.at(Level.WARNING).log("Item config for '" + itemId + "' must be an object: " + sourceLabel);
                continue;
            }
            JsonObject itemObj = entry.getValue().getAsJsonObject();
            ItemFeatureConfig base = registry.get(itemId);
            ItemFeatureConfig config = parseItemConfig(itemObj, base);
            registry.register(itemId, config);
            loaded++;
        }
        return loaded;
    }

    private ItemFeatureConfig parseItemConfig(JsonObject obj, ItemFeatureConfig base) {
        boolean spawnerEnabled = base != null && base.isSpawnerEnabled();
        boolean whistleEnabled = base != null && base.isWhistleEnabled();
        boolean captureClearsOwner = base != null && base.isCaptureClearsOwner();
        boolean spawnAssignsOwner = base != null && base.isSpawnAssignsOwner();
        boolean ownerRestricted = base != null && base.isOwnerRestricted();
        boolean spawnerAllowUncaptured = base != null && base.isSpawnerAllowUncaptured();
        int whistleRadius = base != null ? base.getWhistleRadius() : 64;
        String spawnerRoleId = base != null ? base.getSpawnerRoleId() : null;
        String spawnerFilledItemId = base != null ? base.getSpawnerFilledItemId() : null;
        String spawnerIconDefault = base != null ? base.getSpawnerIconDefault() : null;
        List<ItemFeatureConfig.SpawnerIconOverride> spawnerIconOverrides =
                base != null ? base.getSpawnerIconOverrides() : List.of();

        if (obj.has("Spawner")) {
            spawnerEnabled = readBoolean(obj, "Spawner", spawnerEnabled);
        }
        if (obj.has("Whistle")) {
            whistleEnabled = readBoolean(obj, "Whistle", whistleEnabled);
        }
        if (obj.has("CaptureClearsOwner")) {
            captureClearsOwner = readBoolean(obj, "CaptureClearsOwner", captureClearsOwner);
        }
        if (obj.has("SpawnAssignsOwner")) {
            spawnAssignsOwner = readBoolean(obj, "SpawnAssignsOwner", spawnAssignsOwner);
        }
        if (obj.has("OwnerRestricted")) {
            ownerRestricted = readBoolean(obj, "OwnerRestricted", ownerRestricted);
        }
        if (obj.has("SpawnerAllowUncaptured")) {
            spawnerAllowUncaptured = readBoolean(obj, "SpawnerAllowUncaptured", spawnerAllowUncaptured);
        }
        if (obj.has("WhistleRadius")) {
            whistleRadius = readInt(obj, "WhistleRadius", whistleRadius);
        }
        if (obj.has("SpawnerRoleId")) {
            spawnerRoleId = readString(obj, "SpawnerRoleId", spawnerRoleId);
        }
        if (obj.has("SpawnerFilledItemId")) {
            spawnerFilledItemId = readString(obj, "SpawnerFilledItemId", spawnerFilledItemId);
        }
        if (obj.has("SpawnerIconDefault")) {
            spawnerIconDefault = readString(obj, "SpawnerIconDefault", spawnerIconDefault);
        }
        if (obj.has("SpawnerIconOverrides")) {
            spawnerIconOverrides = readIconOverrides(obj, "SpawnerIconOverrides", spawnerIconOverrides);
        }

        return ItemFeatureConfig.builder()
                .spawnerEnabled(spawnerEnabled)
                .whistleEnabled(whistleEnabled)
                .captureClearsOwner(captureClearsOwner)
                .spawnAssignsOwner(spawnAssignsOwner)
                .ownerRestricted(ownerRestricted)
                .spawnerAllowUncaptured(spawnerAllowUncaptured)
                .whistleRadius(whistleRadius)
                .spawnerRoleId(spawnerRoleId)
                .spawnerFilledItemId(spawnerFilledItemId)
                .spawnerIconDefault(spawnerIconDefault)
                .spawnerIconOverrides(spawnerIconOverrides)
                .build();
    }

    private boolean readBoolean(JsonObject obj, String key, boolean fallback) {
        JsonElement element = obj.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
                ? element.getAsBoolean()
                : fallback;
    }

    private int readInt(JsonObject obj, String key, int fallback) {
        JsonElement element = obj.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? element.getAsInt()
                : fallback;
    }

    private String readString(JsonObject obj, String key, String fallback) {
        JsonElement element = obj.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString()
                : fallback;
    }

    private List<ItemFeatureConfig.SpawnerIconOverride> readIconOverrides(JsonObject obj,
                                                                          String key,
                                                                          List<ItemFeatureConfig.SpawnerIconOverride> fallback) {
        JsonElement element = obj.get(key);
        if (element == null || !element.isJsonArray()) {
            return fallback;
        }
        List<ItemFeatureConfig.SpawnerIconOverride> overrides = new ArrayList<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            if (entry == null || !entry.isJsonObject()) {
                continue;
            }
            JsonObject overrideObj = entry.getAsJsonObject();
            String icon = readString(overrideObj, "Icon", null);
            if (icon == null || icon.isBlank()) {
                continue;
            }
            Map<String, String> attachments = new LinkedHashMap<>();
            JsonObject attachmentsObj = overrideObj.getAsJsonObject("Attachments");
            if (attachmentsObj != null) {
                for (Map.Entry<String, JsonElement> attachmentEntry : attachmentsObj.entrySet()) {
                    JsonElement value = attachmentEntry.getValue();
                    if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                        attachments.put(attachmentEntry.getKey(), value.getAsString());
                    }
                }
            }
            overrides.add(new ItemFeatureConfig.SpawnerIconOverride(attachments, icon));
        }
        return overrides.isEmpty() ? fallback : overrides;
    }
}
