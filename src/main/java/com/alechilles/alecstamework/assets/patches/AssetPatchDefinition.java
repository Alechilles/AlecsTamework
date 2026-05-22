package com.alechilles.alecstamework.assets.patches;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Parsed optional integration patch that targets one JSON-like asset.
 */
public final class AssetPatchDefinition {
    private final String id;
    private final String target;
    private final int priority;
    private final boolean enabled;
    private final List<AssetPatchOperation> operations;
    private final String sourcePack;
    private final String sourcePath;

    private AssetPatchDefinition(@Nonnull String id,
                                       @Nonnull String target,
                                       int priority,
                                       boolean enabled,
                                       @Nonnull List<AssetPatchOperation> operations,
                                       @Nonnull String sourcePack,
                                       @Nonnull String sourcePath) {
        this.id = id;
        this.target = target;
        this.priority = priority;
        this.enabled = enabled;
        this.operations = List.copyOf(operations);
        this.sourcePack = sourcePack;
        this.sourcePath = sourcePath;
    }

    @Nonnull
    public static AssetPatchDefinition parse(@Nonnull JsonObject root,
                                                   @Nonnull String sourcePack,
                                                   @Nonnull String sourcePath) {
        String fallbackId = sourcePack + ":" + sourcePath;
        String id = readString(root, "Id", fallbackId);
        String target = readRequiredString(root, "Target", id);
        int priority = readInt(root, "Priority", 0);
        boolean enabled = readBoolean(root, "Enabled", true);
        List<AssetPatchOperation> operations = readOperations(root, id);
        return new AssetPatchDefinition(id, normalizeAssetPath(target), priority, enabled, operations, sourcePack, sourcePath);
    }

    @Nonnull
    public String getId() {
        return id;
    }

    @Nonnull
    public String getTarget() {
        return target;
    }

    public int getPriority() {
        return priority;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Nonnull
    public List<AssetPatchOperation> getOperations() {
        return operations;
    }

    @Nonnull
    public String getSourcePack() {
        return sourcePack;
    }

    @Nonnull
    public String getSourcePath() {
        return sourcePath;
    }

    @Nonnull
    static String normalizeAssetPath(@Nonnull String path) {
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    @Nonnull
    private static List<AssetPatchOperation> readOperations(@Nonnull JsonObject root, @Nonnull String patchId) {
        JsonElement rawOperations = root.get("Operations");
        if (rawOperations == null || !rawOperations.isJsonArray()) {
            throw new IllegalArgumentException("Patch '" + patchId + "' must define an Operations array.");
        }
        JsonArray array = rawOperations.getAsJsonArray();
        if (array.isEmpty()) {
            return Collections.emptyList();
        }
        List<AssetPatchOperation> operations = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (element == null || !element.isJsonObject()) {
                throw new IllegalArgumentException("Patch '" + patchId + "' operation " + i + " must be an object.");
            }
            operations.add(AssetPatchOperation.parse(element.getAsJsonObject(), patchId, i));
        }
        return operations;
    }

    @Nonnull
    static String readRequiredString(@Nonnull JsonObject object, @Nonnull String name, @Nonnull String context) {
        String value = readString(object, name, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(context + " must define non-empty " + name + ".");
        }
        return value;
    }

    @Nullable
    static String readString(@Nonnull JsonObject object, @Nonnull String name, @Nullable String fallback) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(name + " must be a string.");
        }
        return element.getAsString();
    }

    static int readInt(@Nonnull JsonObject object, @Nonnull String name, int fallback) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(name + " must be a number.");
        }
        return element.getAsInt();
    }

    static boolean readBoolean(@Nonnull JsonObject object, @Nonnull String name, boolean fallback) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(name + " must be a boolean.");
        }
        return element.getAsBoolean();
    }
}
