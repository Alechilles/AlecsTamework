package com.alechilles.alecstamework.config.overrides;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
        String[] parts = splitPath(dottedPath);
        if (parts.length == 0) {
            return null;
        }
        JsonObject cursor = root;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            JsonElement value = cursor.get(part);
            if (value == null) {
                return null;
            }
            boolean last = i == parts.length - 1;
            if (last) {
                return value.deepCopy();
            }
            if (!value.isJsonObject()) {
                return null;
            }
            cursor = value.getAsJsonObject();
        }
        return null;
    }

    public static boolean hasPath(@Nullable JsonObject root, @Nullable String dottedPath) {
        return getPath(root, dottedPath) != null;
    }

    public static void setPath(@Nonnull JsonObject root,
                               @Nonnull String dottedPath,
                               @Nullable JsonElement value) {
        Objects.requireNonNull(root, "root");
        String[] parts = splitPath(dottedPath);
        if (parts.length == 0) {
            return;
        }
        JsonObject cursor = root;
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            JsonElement next = cursor.get(part);
            if (next == null || !next.isJsonObject()) {
                JsonObject created = new JsonObject();
                cursor.add(part, created);
                cursor = created;
                continue;
            }
            cursor = next.getAsJsonObject();
        }
        cursor.add(parts[parts.length - 1], value == null ? null : value.deepCopy());
    }

    public static boolean removePath(@Nonnull JsonObject root, @Nonnull String dottedPath) {
        Objects.requireNonNull(root, "root");
        String[] parts = splitPath(dottedPath);
        if (parts.length == 0) {
            return false;
        }
        Deque<JsonObject> stack = new ArrayDeque<>();
        JsonObject cursor = root;
        stack.push(root);
        for (int i = 0; i < parts.length - 1; i++) {
            JsonElement next = cursor.get(parts[i]);
            if (next == null || !next.isJsonObject()) {
                return false;
            }
            cursor = next.getAsJsonObject();
            stack.push(cursor);
        }
        String leaf = parts[parts.length - 1];
        if (!cursor.has(leaf)) {
            return false;
        }
        cursor.remove(leaf);

        // Prune now-empty parent containers after inherit/reset.
        for (int i = parts.length - 2; i >= 0; i--) {
            JsonObject child = stack.pop();
            JsonObject parent = stack.peek();
            if (parent == null || !child.entrySet().isEmpty()) {
                break;
            }
            parent.remove(parts[i]);
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
    private static String[] splitPath(@Nullable String dottedPath) {
        if (dottedPath == null || dottedPath.isBlank()) {
            return new String[0];
        }
        String[] raw = dottedPath.split("\\.");
        ArrayList<String> parts = new ArrayList<>(raw.length);
        for (String part : raw) {
            if (part == null || part.isBlank()) {
                continue;
            }
            parts.add(part.trim());
        }
        return parts.toArray(new String[0]);
    }
}
