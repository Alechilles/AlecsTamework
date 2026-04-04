package com.alechilles.alecstamework.config.overrides;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * JSON helpers for the Tamework override editor and write pipeline.
 */
public final class TwConfigJsonUtil {
    private static final Gson PRETTY_GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private TwConfigJsonUtil() {
    }

    @Nonnull
    public static JsonObject readObjectOrEmpty(@Nullable Path path) {
        JsonObject parsed = readObject(path);
        return parsed == null ? new JsonObject() : parsed;
    }

    @Nullable
    public static JsonObject readObject(@Nullable Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            String raw = Files.readString(path);
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                return null;
            }
            return parsed.getAsJsonObject();
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nonnull
    public static JsonObject copyObject(@Nullable JsonObject object) {
        return object == null ? new JsonObject() : object.deepCopy();
    }

    public static boolean isEmptyObject(@Nullable JsonObject object) {
        return object == null || object.entrySet().isEmpty();
    }

    /**
     * Child overlay merge: nested objects merge missing keys, everything else replaces.
     */
    @Nonnull
    public static JsonObject merge(@Nullable JsonObject base, @Nullable JsonObject childOverride) {
        JsonObject merged = copyObject(base);
        if (childOverride == null) {
            return merged;
        }
        mergeInto(merged, childOverride);
        return merged;
    }

    private static void mergeInto(@Nonnull JsonObject target, @Nonnull JsonObject override) {
        for (Map.Entry<String, JsonElement> entry : override.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            JsonElement existing = target.get(key);
            if (existing != null && existing.isJsonObject() && value != null && value.isJsonObject()) {
                JsonObject next = existing.getAsJsonObject().deepCopy();
                mergeInto(next, value.getAsJsonObject());
                target.add(key, next);
                continue;
            }
            target.add(key, value == null ? null : value.deepCopy());
        }
    }

    @Nonnull
    public static Map<String, JsonElement> flattenLeafPaths(@Nullable JsonObject root) {
        if (root == null) {
            return Map.of();
        }
        LinkedHashMap<String, JsonElement> out = new LinkedHashMap<>();
        flatten(root, "", out);
        return Collections.unmodifiableMap(out);
    }

    private static void flatten(@Nonnull JsonObject object,
                                @Nonnull String prefix,
                                @Nonnull Map<String, JsonElement> out) {
        List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(object.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER));
        for (Map.Entry<String, JsonElement> entry : entries) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (value != null && value.isJsonObject()) {
                flatten(value.getAsJsonObject(), path, out);
                continue;
            }
            out.put(path, value == null ? null : value.deepCopy());
        }
    }

    @Nonnull
    public static List<String> listTopLevelSections(@Nullable JsonObject object) {
        if (object == null || object.entrySet().isEmpty()) {
            return List.of();
        }
        ArrayList<String> sections = new ArrayList<>();
        object.entrySet().forEach(entry -> sections.add(entry.getKey()));
        sections.sort(String.CASE_INSENSITIVE_ORDER);
        return Collections.unmodifiableList(sections);
    }

    @Nullable
    public static JsonElement getPath(@Nullable JsonObject root, @Nullable String dottedPath) {
        if (root == null || dottedPath == null || dottedPath.isBlank()) {
            return null;
        }
        List<PathToken> parts = parsePath(dottedPath);
        if (parts.isEmpty()) {
            return null;
        }
        JsonElement cursor = root;
        for (PathToken part : parts) {
            JsonElement value;
            if (part.objectKey()) {
                if (!cursor.isJsonObject()) {
                    return null;
                }
                value = cursor.getAsJsonObject().get(part.key());
            } else if (part.arrayIndex()) {
                if (!cursor.isJsonArray()) {
                    return null;
                }
                JsonArray array = cursor.getAsJsonArray();
                int index = part.index();
                if (index < 0 || index >= array.size()) {
                    return null;
                }
                value = array.get(index);
            } else {
                return null;
            }
            if (value == null) {
                return null;
            }
            if (value.isJsonNull()) {
                return null;
            }
            cursor = value;
        }
        return cursor.deepCopy();
    }

    public static boolean hasPath(@Nullable JsonObject root, @Nullable String dottedPath) {
        return getPath(root, dottedPath) != null;
    }

    public static void setPath(@Nonnull JsonObject root,
                               @Nonnull String dottedPath,
                               @Nullable JsonElement value) {
        Objects.requireNonNull(root, "root");
        List<PathToken> parts = parsePath(dottedPath);
        if (parts.isEmpty()) {
            return;
        }
        JsonElement cursor = root;
        for (int i = 0; i < parts.size() - 1; i++) {
            PathToken part = parts.get(i);
            PathToken nextPart = parts.get(i + 1);
            if (part.objectKey()) {
                if (!cursor.isJsonObject()) {
                    return;
                }
                JsonObject object = cursor.getAsJsonObject();
                JsonElement next = object.get(part.key());
                if (!isContainerCompatible(next, nextPart)) {
                    next = createContainerFor(nextPart);
                    object.add(part.key(), next);
                }
                cursor = next;
                continue;
            }
            if (!part.arrayIndex() || !cursor.isJsonArray()) {
                return;
            }
            JsonArray array = cursor.getAsJsonArray();
            int index = part.index();
            ensureArrayIndex(array, index);
            JsonElement next = array.get(index);
            if (!isContainerCompatible(next, nextPart)) {
                next = createContainerFor(nextPart);
                array.set(index, next);
            }
            cursor = next;
        }
        PathToken leaf = parts.get(parts.size() - 1);
        JsonElement payload = value == null ? null : value.deepCopy();
        if (leaf.objectKey()) {
            if (!cursor.isJsonObject()) {
                return;
            }
            cursor.getAsJsonObject().add(leaf.key(), payload);
            return;
        }
        if (!leaf.arrayIndex() || !cursor.isJsonArray()) {
            return;
        }
        JsonArray array = cursor.getAsJsonArray();
        int index = leaf.index();
        ensureArrayIndex(array, index);
        array.set(index, payload);
    }

    public static boolean removePath(@Nonnull JsonObject root, @Nonnull String dottedPath) {
        Objects.requireNonNull(root, "root");
        List<PathToken> parts = parsePath(dottedPath);
        if (parts.isEmpty()) {
            return false;
        }
        Deque<ParentRef> stack = new ArrayDeque<>();
        JsonElement cursor = root;
        for (int i = 0; i < parts.size() - 1; i++) {
            PathToken token = parts.get(i);
            JsonElement next;
            if (token.objectKey()) {
                if (!cursor.isJsonObject()) {
                    return false;
                }
                next = cursor.getAsJsonObject().get(token.key());
            } else if (token.arrayIndex()) {
                if (!cursor.isJsonArray()) {
                    return false;
                }
                JsonArray array = cursor.getAsJsonArray();
                int index = token.index();
                if (index < 0 || index >= array.size()) {
                    return false;
                }
                next = array.get(index);
            } else {
                return false;
            }
            if (next == null || next.isJsonNull()) {
                return false;
            }
            stack.push(new ParentRef(cursor, token));
            cursor = next;
        }
        PathToken leaf = parts.get(parts.size() - 1);
        boolean removed;
        if (leaf.objectKey()) {
            if (!cursor.isJsonObject()) {
                return false;
            }
            JsonObject object = cursor.getAsJsonObject();
            if (!object.has(leaf.key())) {
                return false;
            }
            object.remove(leaf.key());
            removed = true;
        } else if (leaf.arrayIndex()) {
            if (!cursor.isJsonArray()) {
                return false;
            }
            JsonArray array = cursor.getAsJsonArray();
            int index = leaf.index();
            if (index < 0 || index >= array.size()) {
                return false;
            }
            array.remove(index);
            trimTrailingNulls(array);
            removed = true;
        } else {
            return false;
        }
        if (!removed) {
            return false;
        }

        JsonElement current = cursor;
        while (!stack.isEmpty()) {
            if (!isContainerEmpty(current)) {
                break;
            }
            ParentRef parentRef = stack.pop();
            JsonElement parentContainer = parentRef.container();
            PathToken token = parentRef.token();
            if (token.objectKey()) {
                if (!parentContainer.isJsonObject()) {
                    break;
                }
                parentContainer.getAsJsonObject().remove(token.key());
            } else if (token.arrayIndex()) {
                if (!parentContainer.isJsonArray()) {
                    break;
                }
                JsonArray parentArray = parentContainer.getAsJsonArray();
                int index = token.index();
                if (index >= 0 && index < parentArray.size()) {
                    parentArray.remove(index);
                    trimTrailingNulls(parentArray);
                }
            }
            current = parentContainer;
        }
        return true;
    }

    public static void writeObjectAtomic(@Nonnull Path path, @Nonnull JsonObject object) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(object, "object");
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String fileName = path.getFileName() == null ? "override" : path.getFileName().toString();
        Path temp = (parent == null ? path.getFileSystem().getPath(".") : parent)
                .resolve(fileName + ".tmp");
        String prettyJson = PRETTY_GSON.toJson(object) + System.lineSeparator();
        Files.writeString(temp, prettyJson, StandardCharsets.UTF_8);
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailure) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Nonnull
    private static List<PathToken> parsePath(@Nullable String dottedPath) {
        if (dottedPath == null || dottedPath.isBlank()) {
            return List.of();
        }
        String[] raw = dottedPath.split("\\.");
        ArrayList<PathToken> parts = new ArrayList<>(raw.length);
        for (String segment : raw) {
            if (segment == null || segment.isBlank()) {
                continue;
            }
            String part = segment.trim();
            int position = 0;
            while (position < part.length()) {
                int open = part.indexOf('[', position);
                if (open < 0) {
                    String key = part.substring(position).trim();
                    if (!key.isBlank()) {
                        parts.add(PathToken.forKey(key));
                    }
                    break;
                }
                String key = part.substring(position, open).trim();
                if (!key.isBlank()) {
                    parts.add(PathToken.forKey(key));
                }
                int close = part.indexOf(']', open + 1);
                if (close <= open + 1) {
                    return List.of();
                }
                String indexText = part.substring(open + 1, close).trim();
                if (!indexText.matches("\\d+")) {
                    return List.of();
                }
                parts.add(PathToken.forIndex(Integer.parseInt(indexText)));
                position = close + 1;
            }
        }
        return parts.isEmpty() ? List.of() : List.copyOf(parts);
    }

    @Nonnull
    private static JsonElement createContainerFor(@Nullable PathToken nextPart) {
        if (nextPart != null && nextPart.arrayIndex()) {
            return new JsonArray();
        }
        return new JsonObject();
    }

    private static boolean isContainerCompatible(@Nullable JsonElement value, @Nullable PathToken nextPart) {
        if (value == null || value.isJsonNull()) {
            return false;
        }
        if (nextPart == null) {
            return true;
        }
        if (nextPart.objectKey()) {
            return value.isJsonObject();
        }
        if (nextPart.arrayIndex()) {
            return value.isJsonArray();
        }
        return false;
    }

    private static void ensureArrayIndex(@Nonnull JsonArray array, int index) {
        if (index < 0) {
            return;
        }
        while (array.size() <= index) {
            array.add((JsonElement) null);
        }
    }

    private static boolean isContainerEmpty(@Nullable JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return true;
        }
        if (value.isJsonObject()) {
            return value.getAsJsonObject().entrySet().isEmpty();
        }
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            trimTrailingNulls(array);
            return array.isEmpty();
        }
        return false;
    }

    private static void trimTrailingNulls(@Nonnull JsonArray array) {
        for (int i = array.size() - 1; i >= 0; i--) {
            JsonElement element = array.get(i);
            if (element != null && !element.isJsonNull()) {
                break;
            }
            array.remove(i);
        }
    }

    private record ParentRef(@Nonnull JsonElement container, @Nonnull PathToken token) {
    }

    private record PathToken(@Nullable String key, @Nullable Integer index) {
        static PathToken forKey(@Nonnull String key) {
            return new PathToken(key, null);
        }

        static PathToken forIndex(int index) {
            return new PathToken(null, index);
        }

        boolean objectKey() {
            return key != null && !key.isBlank();
        }

        boolean arrayIndex() {
            return index != null && index >= 0;
        }
    }
}
