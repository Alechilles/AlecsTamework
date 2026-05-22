package com.alechilles.alecstamework.assets.patches;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Applies optional asset patch operations to parsed JSON-like assets.
 */
public final class AssetPatchEngine {
    private final AssetPatchMacroExpander macroExpander;

    public AssetPatchEngine() {
        this(new AssetPatchMacroExpander());
    }

    AssetPatchEngine(@Nonnull AssetPatchMacroExpander macroExpander) {
        this.macroExpander = macroExpander;
    }

    @Nonnull
    public PatchResult apply(@Nonnull JsonObject source,
                             @Nonnull List<AssetPatchDefinition> definitions) {
        JsonObject working = source.deepCopy();
        AssetPatchStatus status = new AssetPatchStatus();
        List<AssetPatchDefinition> sorted = definitions.stream()
                .filter(AssetPatchDefinition::isEnabled)
                .sorted(Comparator.comparingInt(AssetPatchDefinition::getPriority)
                        .thenComparing(AssetPatchDefinition::getId))
                .toList();

        for (AssetPatchDefinition definition : sorted) {
            for (AssetPatchOperation operation : definition.getOperations()) {
                List<AssetPatchOperation> expanded = macroExpander.expand(operation);
                for (AssetPatchOperation rawOperation : expanded) {
                    applyOperation(working, definition, rawOperation, status);
                }
            }
        }
        return new PatchResult(working, status);
    }

    private void applyOperation(@Nonnull JsonObject root,
                                @Nonnull AssetPatchDefinition definition,
                                @Nonnull AssetPatchOperation operation,
                                @Nonnull AssetPatchStatus status) {
        try {
            OperationOutcome outcome = applyRawOperation(root, operation);
            String label = definition.getId() + ":" + operation.getId();
            if (outcome.isApplied()) {
                status.addApplied(label);
            } else {
                status.addSkipped(label + " (" + outcome.getMessage() + ")");
            }
        } catch (RuntimeException ex) {
            String message = definition.getId() + ":" + operation.getId() + " failed: " + ex.getMessage();
            if (operation.isRequired()) {
                status.addFailed(message);
                throw new PatchFailureException(message, ex);
            }
            status.addSkipped(message);
        }
    }

    @Nonnull
    private OperationOutcome applyRawOperation(@Nonnull JsonObject root,
                                               @Nonnull AssetPatchOperation operation) {
        String op = operation.getOp().toLowerCase(Locale.ROOT);
        return switch (op) {
            case "add" -> add(root, operation);
            case "merge" -> merge(root, operation);
            case "replace" -> replace(root, operation);
            case "remove" -> remove(root, operation);
            case "insert" -> insert(root, operation);
            default -> throw new IllegalArgumentException("Unsupported operation '" + operation.getOp() + "'.");
        };
    }

    @Nonnull
    private OperationOutcome add(@Nonnull JsonObject root, @Nonnull AssetPatchOperation operation) {
        JsonElement value = requiredValue(operation);
        PathTarget target = resolveParent(root, requiredPath(operation), true);
        if (target.parent().isJsonObject()) {
            target.parent().getAsJsonObject().add(target.leaf(), value);
            return OperationOutcome.appliedOutcome();
        }
        if (target.parent().isJsonArray()) {
            insertArrayValue(target.parent().getAsJsonArray(), target.leaf(), value);
            return OperationOutcome.appliedOutcome();
        }
        throw new IllegalArgumentException("Add parent is not an object or array at " + operation.getPath() + ".");
    }

    @Nonnull
    private OperationOutcome merge(@Nonnull JsonObject root, @Nonnull AssetPatchOperation operation) {
        JsonElement value = requiredValue(operation);
        if (!value.isJsonObject()) {
            throw new IllegalArgumentException("Merge value must be an object.");
        }
        JsonElement target = resolve(root, requiredPath(operation));
        if (target == null || !target.isJsonObject()) {
            throw new IllegalArgumentException("Merge target must exist and be an object at " + operation.getPath() + ".");
        }
        deepMerge(target.getAsJsonObject(), value.getAsJsonObject());
        return OperationOutcome.appliedOutcome();
    }

    @Nonnull
    private OperationOutcome replace(@Nonnull JsonObject root, @Nonnull AssetPatchOperation operation) {
        JsonElement value = requiredValue(operation);
        PathTarget target = resolveParent(root, requiredPath(operation), false);
        if (target.parent().isJsonObject()) {
            JsonObject object = target.parent().getAsJsonObject();
            if (!object.has(target.leaf())) {
                throw new IllegalArgumentException("Replace target does not exist at " + operation.getPath() + ".");
            }
            object.add(target.leaf(), value);
            return OperationOutcome.appliedOutcome();
        }
        if (target.parent().isJsonArray()) {
            JsonArray array = target.parent().getAsJsonArray();
            int index = parseArrayIndex(target.leaf(), array.size(), false);
            array.set(index, value);
            return OperationOutcome.appliedOutcome();
        }
        throw new IllegalArgumentException("Replace parent is not an object or array at " + operation.getPath() + ".");
    }

    @Nonnull
    private OperationOutcome remove(@Nonnull JsonObject root, @Nonnull AssetPatchOperation operation) {
        PathTarget target = resolveParent(root, requiredPath(operation), false);
        if (target.parent().isJsonObject()) {
            JsonElement removed = target.parent().getAsJsonObject().remove(target.leaf());
            if (removed == null) {
                throw new IllegalArgumentException("Remove target does not exist at " + operation.getPath() + ".");
            }
            return OperationOutcome.appliedOutcome();
        }
        if (target.parent().isJsonArray()) {
            JsonArray array = target.parent().getAsJsonArray();
            int index = parseArrayIndex(target.leaf(), array.size(), false);
            array.remove(index);
            return OperationOutcome.appliedOutcome();
        }
        throw new IllegalArgumentException("Remove parent is not an object or array at " + operation.getPath() + ".");
    }

    @Nonnull
    private OperationOutcome insert(@Nonnull JsonObject root, @Nonnull AssetPatchOperation operation) {
        JsonElement value = requiredValue(operation);
        JsonElement target = resolve(root, requiredPath(operation));
        if (target == null || !target.isJsonArray()) {
            throw new IllegalArgumentException("Insert target must be an array at " + operation.getPath() + ".");
        }
        JsonArray array = target.getAsJsonArray();
        JsonObject existing = operation.getExisting();
        if (existing != null && findIndex(array, existing) >= 0) {
            return OperationOutcome.skipped("existing matcher already present");
        }

        String position = operation.getPosition() == null ? "End" : operation.getPosition();
        int index = switch (position.toLowerCase(Locale.ROOT)) {
            case "start" -> 0;
            case "end" -> array.size();
            case "before" -> anchorIndex(array, operation, false);
            case "after" -> anchorIndex(array, operation, true);
            default -> throw new IllegalArgumentException("Unsupported insert position '" + position + "'.");
        };
        insertJsonArrayValue(array, index, value);
        return OperationOutcome.appliedOutcome();
    }

    private int anchorIndex(@Nonnull JsonArray array,
                            @Nonnull AssetPatchOperation operation,
                            boolean after) {
        JsonObject find = operation.getFind();
        if (find == null) {
            throw new IllegalArgumentException("Insert " + operation.getPosition() + " requires Find.");
        }
        int index = findIndex(array, find);
        if (index < 0) {
            throw new IllegalArgumentException("Insert anchor not found for " + operation.getId() + ".");
        }
        return after ? index + 1 : index;
    }

    private int findIndex(@Nonnull JsonArray array, @Nonnull JsonObject matcher) {
        for (int i = 0; i < array.size(); i++) {
            if (matches(array.get(i), matcher)) {
                return i;
            }
        }
        return -1;
    }

    static boolean matches(@Nullable JsonElement candidate, @Nonnull JsonObject matcher) {
        JsonElement containsMatcher = matcher.get("$Contains");
        if (containsMatcher != null) {
            if (candidate == null || !candidate.isJsonArray() || !containsMatcher.isJsonObject()) {
                return false;
            }
            for (JsonElement element : candidate.getAsJsonArray()) {
                if (matches(element, containsMatcher.getAsJsonObject())) {
                    return true;
                }
            }
            return false;
        }
        if (candidate == null || !candidate.isJsonObject()) {
            return false;
        }
        JsonObject object = candidate.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : matcher.entrySet()) {
            JsonElement actual = object.get(entry.getKey());
            JsonElement expected = entry.getValue();
            if (expected != null && expected.isJsonObject()) {
                if (!matches(actual, expected.getAsJsonObject())) {
                    return false;
                }
            } else if (actual == null || !actual.equals(expected)) {
                return false;
            }
        }
        return true;
    }

    private static void deepMerge(@Nonnull JsonObject target, @Nonnull JsonObject value) {
        for (Map.Entry<String, JsonElement> entry : value.entrySet()) {
            JsonElement existing = target.get(entry.getKey());
            JsonElement incoming = entry.getValue();
            if (existing != null && existing.isJsonObject() && incoming != null && incoming.isJsonObject()) {
                deepMerge(existing.getAsJsonObject(), incoming.getAsJsonObject());
            } else {
                target.add(entry.getKey(), incoming == null ? null : incoming.deepCopy());
            }
        }
    }

    @Nullable
    private static JsonElement resolve(@Nonnull JsonElement root, @Nonnull String path) {
        JsonElement current = root;
        for (String token : tokens(path)) {
            if (current == null) {
                return null;
            }
            if (current.isJsonObject()) {
                current = current.getAsJsonObject().get(token);
            } else if (current.isJsonArray()) {
                JsonArray array = current.getAsJsonArray();
                int index = parseArrayIndex(token, array.size(), false);
                current = array.get(index);
            } else {
                return null;
            }
        }
        return current;
    }

    @Nonnull
    private static PathTarget resolveParent(@Nonnull JsonObject root, @Nonnull String path, boolean allowMissingLeaf) {
        List<String> tokens = tokens(path);
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("Path must not point to the document root.");
        }
        JsonElement current = root;
        for (int i = 0; i < tokens.size() - 1; i++) {
            String token = tokens.get(i);
            if (current.isJsonObject()) {
                current = current.getAsJsonObject().get(token);
            } else if (current.isJsonArray()) {
                JsonArray array = current.getAsJsonArray();
                current = array.get(parseArrayIndex(token, array.size(), false));
            } else {
                throw new IllegalArgumentException("Path parent is not traversable at " + token + ".");
            }
            if (current == null) {
                throw new IllegalArgumentException("Path parent does not exist at " + token + ".");
            }
        }
        String leaf = tokens.getLast();
        if (!allowMissingLeaf && current.isJsonObject() && !current.getAsJsonObject().has(leaf)) {
            throw new IllegalArgumentException("Path leaf does not exist at " + leaf + ".");
        }
        return new PathTarget(current, leaf);
    }

    @Nonnull
    private static List<String> tokens(@Nonnull String path) {
        if (path.isBlank() || "/".equals(path)) {
            return List.of();
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Path must use JSON pointer syntax and start with '/': " + path);
        }
        String[] raw = path.substring(1).split("/", -1);
        List<String> result = new ArrayList<>(raw.length);
        for (String token : raw) {
            result.add(token.replace("~1", "/").replace("~0", "~"));
        }
        return result;
    }

    private static void insertArrayValue(@Nonnull JsonArray array, @Nonnull String token, @Nonnull JsonElement value) {
        int index = "-".equals(token) ? array.size() : parseArrayIndex(token, array.size() + 1, true);
        insertJsonArrayValue(array, index, value);
    }

    private static void insertJsonArrayValue(@Nonnull JsonArray array, int index, @Nonnull JsonElement value) {
        JsonArray rebuilt = new JsonArray();
        for (int i = 0; i < array.size(); i++) {
            if (i == index) {
                rebuilt.add(value);
            }
            rebuilt.add(array.get(i));
        }
        if (index == array.size()) {
            rebuilt.add(value);
        }
        while (array.size() > 0) {
            array.remove(0);
        }
        for (JsonElement element : rebuilt) {
            array.add(element);
        }
    }

    private static int parseArrayIndex(@Nonnull String token, int size, boolean allowEnd) {
        try {
            int index = Integer.parseInt(token);
            int upperBound = allowEnd ? size : size - 1;
            if (index < 0 || index > upperBound) {
                throw new IllegalArgumentException("Array index out of bounds: " + token + ".");
            }
            return index;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Array path token must be an integer: " + token + ".", ex);
        }
    }

    @Nonnull
    private static String requiredPath(@Nonnull AssetPatchOperation operation) {
        String path = operation.getPath();
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Operation " + operation.getId() + " requires Path.");
        }
        return path;
    }

    @Nonnull
    private static JsonElement requiredValue(@Nonnull AssetPatchOperation operation) {
        JsonElement value = operation.getValue();
        if (value == null) {
            throw new IllegalArgumentException("Operation " + operation.getId() + " requires Value.");
        }
        return value;
    }

    /**
     * Patched JSON plus diagnostics from a patch run.
     */
    public record PatchResult(@Nonnull JsonObject patched, @Nonnull AssetPatchStatus status) {
    }

    private record PathTarget(@Nonnull JsonElement parent, @Nonnull String leaf) {
    }

    private static final class OperationOutcome {
        private final boolean applied;
        private final String message;

        private OperationOutcome(boolean applied, @Nonnull String message) {
            this.applied = applied;
            this.message = message;
        }

        boolean isApplied() {
            return applied;
        }

        @Nonnull
        String getMessage() {
            return message;
        }

        static OperationOutcome appliedOutcome() {
            return new OperationOutcome(true, "");
        }

        static OperationOutcome skipped(@Nonnull String message) {
            return new OperationOutcome(false, message);
        }
    }

    /**
     * Required patch operation failure.
     */
    public static final class PatchFailureException extends RuntimeException {
        public PatchFailureException(@Nonnull String message, @Nonnull Throwable cause) {
            super(message, cause);
        }
    }
}
