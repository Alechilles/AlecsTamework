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

/**
 * Loads item feature configs into the registry.
 */
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
            logger.at(Level.INFO).log(
                    "Loaded item feature config: item=" + itemId
                            + " captureOwnerRestricted=" + config.isCaptureOwnerRestricted()
                            + " spawnOwnerRestricted=" + config.isSpawnOwnerRestricted()
                            + " source=" + sourceLabel
            );
            loaded++;
        }
        return loaded;
    }

    private ItemFeatureConfig parseItemConfig(JsonObject obj, ItemFeatureConfig base) {
        // Start with existing config values so overrides can be partial.
        boolean spawnerEnabled = base != null && base.isSpawnerEnabled();
        boolean whistleEnabled = base != null && base.isWhistleEnabled();
        boolean captureClearsOwner = base == null || base.isCaptureClearsOwner();
        boolean captureOwnerRestricted = base == null || base.isCaptureOwnerRestricted();
        boolean spawnAssignsOwner = base == null || base.isSpawnAssignsOwner();
        boolean spawnOwnerRestricted = base == null || base.isSpawnOwnerRestricted();
        boolean captureRequireTamed = base == null || base.isCaptureRequireTamed();
        int whistleRadius = base != null ? base.getWhistleRadius() : 64;
        List<String> spawnerRoleAllowlist = base != null ? base.getSpawnerRoleAllowlist() : List.of();
        List<String> spawnerRoleDenylist = base != null ? base.getSpawnerRoleDenylist() : List.of();
        ItemFeatureConfig.RoleListMode spawnerRoleListMode =
                base != null ? base.getSpawnerRoleListMode() : ItemFeatureConfig.RoleListMode.ANY;
        Boolean captureRequireOwnerOverride = base != null ? base.getCaptureRequireOwnerOverride() : null;
        Boolean spawnRequireOwnerOverride = base != null ? base.getSpawnRequireOwnerOverride() : null;
        String captureParticleSystem = base != null ? base.getCaptureParticleSystem() : null;
        String spawnParticleSystem = base != null ? base.getSpawnParticleSystem() : null;
        String captureSoundEvent = base != null ? base.getCaptureSoundEvent() : null;
        String spawnSoundEvent = base != null ? base.getSpawnSoundEvent() : null;
        int captureCooldownMs = base != null ? base.getCaptureCooldownMs() : 0;
        int spawnCooldownMs = base != null ? base.getSpawnCooldownMs() : 0;
        double captureMaxDistance = base != null ? base.getCaptureMaxDistance() : 0.0;
        double spawnMaxDistance = base != null ? base.getSpawnMaxDistance() : 0.0;
        String spawnerFilledItemId = base != null ? base.getSpawnerFilledItemId() : null;
        String spawnerIconDefault = base != null ? base.getSpawnerIconDefault() : null;
        List<ItemFeatureConfig.SpawnerIconOverride> spawnerIconOverrides =
                base != null ? base.getSpawnerIconOverrides() : List.of();
        Map<String, List<ItemFeatureConfig.SpawnerIconOverride>> spawnerIconOverridesByRole =
                base != null ? base.getSpawnerIconOverridesByRole() : Map.of();

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
        if (obj.has("CaptureOwnerRestricted")) {
            captureOwnerRestricted = readBoolean(obj, "CaptureOwnerRestricted", captureOwnerRestricted);
        }
        if (obj.has("SpawnOwnerRestricted")) {
            spawnOwnerRestricted = readBoolean(obj, "SpawnOwnerRestricted", spawnOwnerRestricted);
        }
        if (obj.has("CaptureRequireTamed")) {
            captureRequireTamed = readBoolean(obj, "CaptureRequireTamed", captureRequireTamed);
        } else if (obj.has("RequireTamed")) {
            captureRequireTamed = readBoolean(obj, "RequireTamed", captureRequireTamed);
        }
        if (obj.has("WhistleRadius")) {
            whistleRadius = readInt(obj, "WhistleRadius", whistleRadius);
        }
        if (obj.has("RoleAllowlist")) {
            spawnerRoleAllowlist = readStringList(obj, "RoleAllowlist", spawnerRoleAllowlist);
        } else if (obj.has("SpawnerRoleAllowlist")) {
            spawnerRoleAllowlist = readStringList(obj, "SpawnerRoleAllowlist", spawnerRoleAllowlist);
        }
        if (obj.has("RoleDenylist")) {
            spawnerRoleDenylist = readStringList(obj, "RoleDenylist", spawnerRoleDenylist);
        } else if (obj.has("SpawnerRoleDenylist")) {
            spawnerRoleDenylist = readStringList(obj, "SpawnerRoleDenylist", spawnerRoleDenylist);
        }

        if (obj.has("RoleAllowlistMode")) {
            String mode = readString(obj, "RoleAllowlistMode", null);
            if (mode != null) {
                spawnerRoleListMode = ItemFeatureConfig.RoleListMode.fromString(mode);
            }
        } else if (obj.has("SpawnerRoleAllowlistMode")) {
            String mode = readString(obj, "SpawnerRoleAllowlistMode", null);
            if (mode != null) {
                spawnerRoleListMode = ItemFeatureConfig.RoleListMode.fromString(mode);
            }
        }
        if (obj.has("CaptureRequireOwner")) {
            captureRequireOwnerOverride = readOptionalBoolean(obj, "CaptureRequireOwner", captureRequireOwnerOverride);
        }
        if (obj.has("SpawnRequireOwner")) {
            spawnRequireOwnerOverride = readOptionalBoolean(obj, "SpawnRequireOwner", spawnRequireOwnerOverride);
        }
        if (obj.has("CaptureParticleSystem")) {
            captureParticleSystem = readString(obj, "CaptureParticleSystem", captureParticleSystem);
        }
        if (obj.has("SpawnParticleSystem")) {
            spawnParticleSystem = readString(obj, "SpawnParticleSystem", spawnParticleSystem);
        }
        if (obj.has("CaptureSoundEvent")) {
            captureSoundEvent = readString(obj, "CaptureSoundEvent", captureSoundEvent);
        }
        if (obj.has("SpawnSoundEvent")) {
            spawnSoundEvent = readString(obj, "SpawnSoundEvent", spawnSoundEvent);
        }
        if (obj.has("CaptureCooldownMs")) {
            captureCooldownMs = readInt(obj, "CaptureCooldownMs", captureCooldownMs);
        }
        if (obj.has("SpawnCooldownMs")) {
            spawnCooldownMs = readInt(obj, "SpawnCooldownMs", spawnCooldownMs);
        }
        if (obj.has("CaptureMaxDistance")) {
            captureMaxDistance = readDouble(obj, "CaptureMaxDistance", captureMaxDistance);
        }
        if (obj.has("SpawnMaxDistance")) {
            spawnMaxDistance = readDouble(obj, "SpawnMaxDistance", spawnMaxDistance);
        }
        if (obj.has("FilledItemId")) {
            spawnerFilledItemId = readString(obj, "FilledItemId", spawnerFilledItemId);
        } else if (obj.has("SpawnerFilledItemId")) {
            spawnerFilledItemId = readString(obj, "SpawnerFilledItemId", spawnerFilledItemId);
        }
        if (obj.has("IconDefault")) {
            spawnerIconDefault = readString(obj, "IconDefault", spawnerIconDefault);
        } else if (obj.has("SpawnerIconDefault")) {
            spawnerIconDefault = readString(obj, "SpawnerIconDefault", spawnerIconDefault);
        }
        if (obj.has("IconOverrides")) {
            spawnerIconOverrides = readIconOverrides(obj, "IconOverrides", spawnerIconOverrides);
        } else if (obj.has("SpawnerIconOverrides")) {
            spawnerIconOverrides = readIconOverrides(obj, "SpawnerIconOverrides", spawnerIconOverrides);
        }
        if (obj.has("IconOverridesByRole")) {
            spawnerIconOverridesByRole = readIconOverridesByRole(obj, "IconOverridesByRole", spawnerIconOverridesByRole);
        } else if (obj.has("SpawnerIconOverridesByRole")) {
            spawnerIconOverridesByRole = readIconOverridesByRole(obj, "SpawnerIconOverridesByRole", spawnerIconOverridesByRole);
        }

        return ItemFeatureConfig.builder()
                .spawnerEnabled(spawnerEnabled)
                .whistleEnabled(whistleEnabled)
                .captureClearsOwner(captureClearsOwner)
                .captureRequireTamed(captureRequireTamed)
                .spawnAssignsOwner(spawnAssignsOwner)
                .captureOwnerRestricted(captureOwnerRestricted)
                .spawnOwnerRestricted(spawnOwnerRestricted)
                .whistleRadius(whistleRadius)
                .spawnerRoleAllowlist(spawnerRoleAllowlist)
                .spawnerRoleDenylist(spawnerRoleDenylist)
                .spawnerRoleListMode(spawnerRoleListMode)
                .captureRequireOwnerOverride(captureRequireOwnerOverride)
                .spawnRequireOwnerOverride(spawnRequireOwnerOverride)
                .captureParticleSystem(captureParticleSystem)
                .spawnParticleSystem(spawnParticleSystem)
                .captureSoundEvent(captureSoundEvent)
                .spawnSoundEvent(spawnSoundEvent)
                .captureCooldownMs(captureCooldownMs)
                .spawnCooldownMs(spawnCooldownMs)
                .captureMaxDistance(captureMaxDistance)
                .spawnMaxDistance(spawnMaxDistance)
                .spawnerFilledItemId(spawnerFilledItemId)
                .spawnerIconDefault(spawnerIconDefault)
                .spawnerIconOverrides(spawnerIconOverrides)
                .spawnerIconOverridesByRole(spawnerIconOverridesByRole)
                .build();
    }

    private boolean readBoolean(JsonObject obj, String key, boolean fallback) {
        JsonElement element = obj.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
                ? element.getAsBoolean()
                : fallback;
    }

    private Boolean readOptionalBoolean(JsonObject obj, String key, Boolean fallback) {
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
            return element.getAsBoolean();
        }
        return fallback;
    }

    private double readDouble(JsonObject obj, String key, double fallback) {
        JsonElement element = obj.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? element.getAsDouble()
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


    private List<String> readStringList(JsonObject obj, String key, List<String> fallback) {
        JsonElement element = obj.get(key);
        if (element == null || !element.isJsonArray()) {
            return fallback;
        }
        List<String> values = new ArrayList<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            if (entry != null && entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) {
                String value = entry.getAsString();
                if (value != null && !value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return values.isEmpty() ? fallback : values;
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

    private Map<String, List<ItemFeatureConfig.SpawnerIconOverride>> readIconOverridesByRole(
            JsonObject obj,
            String key,
            Map<String, List<ItemFeatureConfig.SpawnerIconOverride>> fallback) {
        JsonElement element = obj.get(key);
        if (element == null || !element.isJsonObject()) {
            return fallback;
        }
        Map<String, List<ItemFeatureConfig.SpawnerIconOverride>> result = new LinkedHashMap<>();
        JsonObject roleObj = element.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : roleObj.entrySet()) {
            String roleId = entry.getKey();
            if (roleId == null || roleId.isBlank()) {
                continue;
            }
            List<ItemFeatureConfig.SpawnerIconOverride> overrides = readIconOverridesArray(entry.getValue());
            if (!overrides.isEmpty()) {
                result.put(roleId, overrides);
            }
        }
        return result.isEmpty() ? fallback : result;
    }


    private List<ItemFeatureConfig.SpawnerIconOverride> readIconOverridesArray(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return List.of();
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
        return overrides;
    }
}







