package com.alechilles.alecstamework.assets.patches;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Optional gate that decides whether an asset patch should participate in generation.
 */
public final class AssetPatchCondition {
    private enum Kind {
        ALWAYS,
        MOD_INSTALLED,
        ALL,
        ANY,
        NOT
    }

    private static final AssetPatchCondition ALWAYS =
            new AssetPatchCondition(Kind.ALWAYS, null, List.of(), null);

    private final Kind kind;
    private final String value;
    private final List<AssetPatchCondition> children;
    private final AssetPatchCondition child;

    private AssetPatchCondition(@Nonnull Kind kind,
                                String value,
                                @Nonnull List<AssetPatchCondition> children,
                                AssetPatchCondition child) {
        this.kind = kind;
        this.value = value;
        this.children = List.copyOf(children);
        this.child = child;
    }

    @Nonnull
    public static AssetPatchCondition always() {
        return ALWAYS;
    }

    @Nonnull
    public static AssetPatchCondition parseOptional(@Nonnull JsonObject root) {
        JsonElement raw = root.get("When");
        if (raw == null || raw.isJsonNull()) {
            return always();
        }
        if (!raw.isJsonObject()) {
            throw new IllegalArgumentException("When must be an object.");
        }
        return parse(raw.getAsJsonObject());
    }

    @Nonnull
    public static AssetPatchCondition parse(@Nonnull JsonObject object) {
        String conditionKey = null;
        JsonElement conditionValue = null;
        for (var entry : object.entrySet()) {
            String key = entry.getKey();
            if ("$Comment".equals(key)) {
                continue;
            }
            if (!isConditionKey(key)) {
                throw new IllegalArgumentException("Unsupported condition key.");
            }
            if (conditionKey != null) {
                throw new IllegalArgumentException("Condition object must define exactly one condition key.");
            }
            conditionKey = key;
            conditionValue = entry.getValue();
        }
        if (conditionKey == null) {
            throw new IllegalArgumentException("Condition object must define exactly one condition key.");
        }
        if ("ModInstalled".equals(conditionKey)) {
            return modInstalled(readString(conditionValue, "ModInstalled"));
        }
        if ("All".equals(conditionKey)) {
            return composite(Kind.ALL, conditionValue, "All");
        }
        if ("Any".equals(conditionKey)) {
            return composite(Kind.ANY, conditionValue, "Any");
        }
        if ("Not".equals(conditionKey)) {
            if (conditionValue == null || !conditionValue.isJsonObject()) {
                throw new IllegalArgumentException("Not must be a condition object.");
            }
            return new AssetPatchCondition(Kind.NOT, null, List.of(), parse(conditionValue.getAsJsonObject()));
        }
        throw new IllegalArgumentException("Unsupported condition key.");
    }

    public boolean matches(@Nonnull AssetPatchConditionContext context) {
        return switch (kind) {
            case ALWAYS -> true;
            case MOD_INSTALLED -> context.hasInstalledPack(value);
            case ALL -> children.stream().allMatch(condition -> condition.matches(context));
            case ANY -> children.stream().anyMatch(condition -> condition.matches(context));
            case NOT -> !child.matches(context);
        };
    }

    @Nonnull
    public String describe() {
        return switch (kind) {
            case ALWAYS -> "Always";
            case MOD_INSTALLED -> "ModInstalled " + value;
            case ALL -> "All[" + describeChildren() + "]";
            case ANY -> "Any[" + describeChildren() + "]";
            case NOT -> "Not[" + child.describe() + "]";
        };
    }

    @Nonnull
    private static AssetPatchCondition modInstalled(@Nonnull String packId) {
        return new AssetPatchCondition(Kind.MOD_INSTALLED, packId, List.of(), null);
    }

    private static boolean isConditionKey(@Nonnull String key) {
        return "ModInstalled".equals(key) || "All".equals(key) || "Any".equals(key) || "Not".equals(key);
    }

    @Nonnull
    private static AssetPatchCondition composite(@Nonnull Kind kind,
                                                 JsonElement element,
                                                 @Nonnull String name) {
        if (element == null || !element.isJsonArray()) {
            throw new IllegalArgumentException(name + " must be an array of condition objects.");
        }
        JsonArray array = element.getAsJsonArray();
        if (array.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty.");
        }
        List<AssetPatchCondition> children = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JsonElement child = array.get(i);
            if (child == null || !child.isJsonObject()) {
                throw new IllegalArgumentException(name + " entry " + i + " must be a condition object.");
            }
            children.add(parse(child.getAsJsonObject()));
        }
        return new AssetPatchCondition(kind, null, children, null);
    }

    @Nonnull
    private static String readString(JsonElement element, @Nonnull String name) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                || element.getAsString().isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-empty string.");
        }
        return element.getAsString().trim();
    }

    @Nonnull
    private String describeChildren() {
        return String.join(", ", children.stream()
                .map(AssetPatchCondition::describe)
                .toList());
    }
}
