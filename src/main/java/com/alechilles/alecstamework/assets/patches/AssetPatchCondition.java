package com.alechilles.alecstamework.assets.patches;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
        ASSET_EXISTS,
        ASSET_MISSING,
        TARGET_EXISTS,
        MOD_VERSION,
        SERVER_VERSION,
        JSON_PATH_EXISTS,
        JSON_PATH_EQUALS,
        TAMEWORK_SETTING,
        ALL,
        ANY,
        NOT
    }

    private static final AssetPatchCondition ALWAYS =
            new AssetPatchCondition(Kind.ALWAYS, null, null, List.of(), null);

    private final Kind kind;
    private final String value;
    private final JsonObject config;
    private final List<AssetPatchCondition> children;
    private final AssetPatchCondition child;

    private AssetPatchCondition(@Nonnull Kind kind,
                                String value,
                                JsonObject config,
                                @Nonnull List<AssetPatchCondition> children,
                                AssetPatchCondition child) {
        this.kind = kind;
        this.value = value;
        this.config = config == null ? null : config.deepCopy();
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
        if ("AssetExists".equals(conditionKey)) {
            return assetReference(Kind.ASSET_EXISTS, conditionValue, "AssetExists");
        }
        if ("AssetMissing".equals(conditionKey)) {
            return assetReference(Kind.ASSET_MISSING, conditionValue, "AssetMissing");
        }
        if ("TargetExists".equals(conditionKey)) {
            readTrue(conditionValue, "TargetExists");
            return new AssetPatchCondition(Kind.TARGET_EXISTS, null, null, List.of(), null);
        }
        if ("ModVersion".equals(conditionKey)) {
            JsonObject conditionObject = readObject(conditionValue, "ModVersion");
            readRequiredString(conditionObject, "ModVersion", "Mod");
            requireVersionMatcher(conditionObject, "ModVersion");
            return new AssetPatchCondition(Kind.MOD_VERSION, null, conditionObject, List.of(), null);
        }
        if ("ServerVersion".equals(conditionKey) || "GameVersion".equals(conditionKey)) {
            JsonObject conditionObject = readObject(conditionValue, conditionKey);
            requireVersionMatcher(conditionObject, conditionKey);
            return new AssetPatchCondition(Kind.SERVER_VERSION, conditionKey, conditionObject, List.of(), null);
        }
        if ("JsonPathExists".equals(conditionKey)) {
            JsonObject conditionObject = readObject(conditionValue, "JsonPathExists");
            readRequiredString(conditionObject, "JsonPathExists", "Path");
            return new AssetPatchCondition(Kind.JSON_PATH_EXISTS, null, conditionObject, List.of(), null);
        }
        if ("JsonPathEquals".equals(conditionKey)) {
            JsonObject conditionObject = readObject(conditionValue, "JsonPathEquals");
            readRequiredString(conditionObject, "JsonPathEquals", "Path");
            readExpectedValue(conditionObject, "JsonPathEquals");
            return new AssetPatchCondition(Kind.JSON_PATH_EQUALS, null, conditionObject, List.of(), null);
        }
        if ("TameworkSetting".equals(conditionKey)) {
            JsonObject conditionObject = readObject(conditionValue, "TameworkSetting");
            readRequiredString(conditionObject, "TameworkSetting", "Path");
            readExpectedValue(conditionObject, "TameworkSetting");
            return new AssetPatchCondition(Kind.TAMEWORK_SETTING, null, conditionObject, List.of(), null);
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
            return new AssetPatchCondition(Kind.NOT, null, null, List.of(), parse(conditionValue.getAsJsonObject()));
        }
        throw new IllegalArgumentException("Unsupported condition key.");
    }

    public boolean matches(@Nonnull AssetPatchConditionContext context) {
        return matches(context, null);
    }

    public boolean matches(@Nonnull AssetPatchConditionContext context, @Nullable String target) {
        return switch (kind) {
            case ALWAYS -> true;
            case MOD_INSTALLED -> context.hasInstalledPack(value);
            case ASSET_EXISTS -> context.assetExists(resolveAsset(config, target));
            case ASSET_MISSING -> {
                String asset = resolveAsset(config, target);
                yield asset != null && !context.assetExists(asset);
            }
            case TARGET_EXISTS -> target != null && context.assetExists(target);
            case MOD_VERSION -> versionMatches(context.installedPackVersion(readRequiredString(config, "ModVersion", "Mod")), config);
            case SERVER_VERSION -> versionMatches(context.serverVersion(), config);
            case JSON_PATH_EXISTS -> context.jsonPathValue(
                    resolveAsset(config, target),
                    readRequiredString(config, "JsonPathExists", "Path")
            ).isPresent();
            case JSON_PATH_EQUALS -> jsonEquals(
                    context.jsonPathValue(resolveAsset(config, target), readRequiredString(config, "JsonPathEquals", "Path")),
                    readExpectedValue(config, "JsonPathEquals")
            );
            case TAMEWORK_SETTING -> jsonEquals(
                    context.settingValue(readRequiredString(config, "TameworkSetting", "Path")),
                    readExpectedValue(config, "TameworkSetting")
            );
            case ALL -> children.stream().allMatch(condition -> condition.matches(context, target));
            case ANY -> children.stream().anyMatch(condition -> condition.matches(context, target));
            case NOT -> !child.matches(context, target);
        };
    }

    @Nonnull
    public String describe() {
        return switch (kind) {
            case ALWAYS -> "Always";
            case MOD_INSTALLED -> "ModInstalled " + value;
            case ASSET_EXISTS -> "AssetExists " + describeAsset(config);
            case ASSET_MISSING -> "AssetMissing " + describeAsset(config);
            case TARGET_EXISTS -> "TargetExists";
            case MOD_VERSION -> "ModVersion " + readRequiredString(config, "ModVersion", "Mod") + " " + describeVersion(config);
            case SERVER_VERSION -> value + " " + describeVersion(config);
            case JSON_PATH_EXISTS -> "JsonPathExists " + describeAsset(config) + " " + readRequiredString(config, "JsonPathExists", "Path");
            case JSON_PATH_EQUALS -> "JsonPathEquals " + describeAsset(config) + " "
                    + readRequiredString(config, "JsonPathEquals", "Path") + " == " + readExpectedValue(config, "JsonPathEquals");
            case TAMEWORK_SETTING -> "TameworkSetting " + readRequiredString(config, "TameworkSetting", "Path")
                    + " == " + readExpectedValue(config, "TameworkSetting");
            case ALL -> "All[" + describeChildren() + "]";
            case ANY -> "Any[" + describeChildren() + "]";
            case NOT -> "Not[" + child.describe() + "]";
        };
    }

    @Nonnull
    private static AssetPatchCondition modInstalled(@Nonnull String packId) {
        return new AssetPatchCondition(Kind.MOD_INSTALLED, packId, null, List.of(), null);
    }

    @Nonnull
    private static AssetPatchCondition assetReference(@Nonnull Kind kind,
                                                      JsonElement element,
                                                      @Nonnull String name) {
        JsonObject object = new JsonObject();
        if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String asset = readString(element, name);
            object.addProperty("Asset", asset);
            return new AssetPatchCondition(kind, null, object, List.of(), null);
        }
        if (element != null && element.isJsonObject()) {
            JsonObject parsed = element.getAsJsonObject();
            readRequiredString(parsed, name, "Asset");
            return new AssetPatchCondition(kind, null, parsed, List.of(), null);
        }
        throw new IllegalArgumentException(name + " must be a string or object with Asset.");
    }

    private static boolean isConditionKey(@Nonnull String key) {
        return "ModInstalled".equals(key)
                || "AssetExists".equals(key)
                || "AssetMissing".equals(key)
                || "TargetExists".equals(key)
                || "ModVersion".equals(key)
                || "ServerVersion".equals(key)
                || "GameVersion".equals(key)
                || "JsonPathExists".equals(key)
                || "JsonPathEquals".equals(key)
                || "TameworkSetting".equals(key)
                || "All".equals(key)
                || "Any".equals(key)
                || "Not".equals(key);
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
        return new AssetPatchCondition(kind, null, null, children, null);
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
    private static JsonObject readObject(JsonElement element, @Nonnull String name) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException(name + " must be an object.");
        }
        return element.getAsJsonObject();
    }

    @Nonnull
    private static String readRequiredString(@Nonnull JsonObject object,
                                             @Nonnull String conditionName,
                                             @Nonnull String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                || element.getAsString().isBlank()) {
            throw new IllegalArgumentException(conditionName + "." + field + " must be a non-empty string.");
        }
        return element.getAsString().trim();
    }

    private static void readTrue(JsonElement element, @Nonnull String name) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()
                || !element.getAsBoolean()) {
            throw new IllegalArgumentException(name + " must be true.");
        }
    }

    @Nonnull
    private static JsonElement readExpectedValue(@Nonnull JsonObject object, @Nonnull String conditionName) {
        JsonElement value = object.get("Value");
        if (value == null) {
            value = object.get("Equals");
        }
        if (value == null) {
            throw new IllegalArgumentException(conditionName + " must define Value or Equals.");
        }
        return value;
    }

    private static void requireVersionMatcher(@Nonnull JsonObject object, @Nonnull String conditionName) {
        if (!hasAny(object, "Equals", "AtLeast", "AtMost", "Above", "Below")) {
            throw new IllegalArgumentException(
                    conditionName + " must define Equals, AtLeast, AtMost, Above, or Below."
            );
        }
        validateVersionField(object, conditionName, "Equals");
        validateVersionField(object, conditionName, "AtLeast");
        validateVersionField(object, conditionName, "AtMost");
        validateVersionField(object, conditionName, "Above");
        validateVersionField(object, conditionName, "Below");
    }

    private static boolean hasAny(@Nonnull JsonObject object, @Nonnull String... keys) {
        for (String key : keys) {
            if (object.has(key)) {
                return true;
            }
        }
        return false;
    }

    private static void validateVersionField(@Nonnull JsonObject object,
                                             @Nonnull String conditionName,
                                             @Nonnull String field) {
        JsonElement element = object.get(field);
        if (element != null && !element.isJsonNull()) {
            readString(element, conditionName + "." + field);
        }
    }

    @Nullable
    private static String resolveAsset(@Nullable JsonObject object, @Nullable String target) {
        if (object == null) {
            return null;
        }
        JsonElement element = object.get("Asset");
        if (element == null || element.isJsonNull()) {
            return target;
        }
        String asset = readString(element, "Asset");
        return "$Target".equals(asset) ? target : asset;
    }

    @Nonnull
    private static String describeAsset(@Nullable JsonObject object) {
        if (object == null) {
            return "$Target";
        }
        JsonElement element = object.get("Asset");
        if (element == null || element.isJsonNull()) {
            return "$Target";
        }
        return readString(element, "Asset");
    }

    private static boolean jsonEquals(@Nonnull Optional<JsonElement> actual, @Nonnull JsonElement expected) {
        return actual.isPresent() && actual.get().equals(expected);
    }

    private static boolean versionMatches(@Nullable String actual, @Nonnull JsonObject matcher) {
        String normalized = trimToNull(actual);
        if (normalized == null) {
            return false;
        }
        return matchesVersionField(normalized, matcher, "Equals", 0)
                && matchesVersionField(normalized, matcher, "AtLeast", 1)
                && matchesVersionField(normalized, matcher, "AtMost", -1)
                && matchesVersionField(normalized, matcher, "Above", 2)
                && matchesVersionField(normalized, matcher, "Below", -2);
    }

    private static boolean matchesVersionField(@Nonnull String actual,
                                               @Nonnull JsonObject matcher,
                                               @Nonnull String field,
                                               int mode) {
        JsonElement element = matcher.get(field);
        if (element == null || element.isJsonNull()) {
            return true;
        }
        String expected = readString(element, field);
        int compare = compareVersions(actual, expected);
        return switch (mode) {
            case 0 -> compare == 0;
            case 1 -> compare >= 0;
            case -1 -> compare <= 0;
            case 2 -> compare > 0;
            case -2 -> compare < 0;
            default -> true;
        };
    }

    private static int compareVersions(@Nonnull String left, @Nonnull String right) {
        String[] leftParts = normalizeVersion(left).split("\\.");
        String[] rightParts = normalizeVersion(right).split("\\.");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            int leftValue = i < leftParts.length ? parseVersionPart(leftParts[i]) : 0;
            int rightValue = i < rightParts.length ? parseVersionPart(rightParts[i]) : 0;
            int compare = Integer.compare(leftValue, rightValue);
            if (compare != 0) {
                return compare;
            }
        }
        return 0;
    }

    @Nonnull
    private static String normalizeVersion(@Nonnull String version) {
        String trimmed = version.trim().toLowerCase(Locale.ROOT);
        if (trimmed.startsWith("=") || trimmed.startsWith("^") || trimmed.startsWith("~")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed.replace('x', '0').replace('*', '0');
    }

    private static int parseVersionPart(@Nonnull String rawPart) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < rawPart.length(); i++) {
            char c = rawPart.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            } else {
                break;
            }
        }
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    @Nonnull
    private static String describeVersion(@Nonnull JsonObject matcher) {
        List<String> parts = new ArrayList<>();
        appendVersionPart(parts, matcher, "Equals", "==");
        appendVersionPart(parts, matcher, "AtLeast", ">=");
        appendVersionPart(parts, matcher, "AtMost", "<=");
        appendVersionPart(parts, matcher, "Above", ">");
        appendVersionPart(parts, matcher, "Below", "<");
        return String.join(" ", parts);
    }

    private static void appendVersionPart(@Nonnull List<String> parts,
                                          @Nonnull JsonObject matcher,
                                          @Nonnull String field,
                                          @Nonnull String operator) {
        JsonElement element = matcher.get(field);
        if (element != null) {
            parts.add(operator + " " + readString(element, field));
        }
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    @Nonnull
    private String describeChildren() {
        return String.join(", ", children.stream()
                .map(AssetPatchCondition::describe)
                .toList());
    }
}
