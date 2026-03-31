package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.config.overrides.TwConfigAssetDescriptor;
import com.alechilles.alecstamework.config.overrides.TwConfigSnapshot;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Field policy for the player/admin config editor.
 * Uses schema-derived descriptors and augments with effective JSON fallback shape.
 */
final class TwConfigEditorFieldPolicy {
    private static final String FIELD_PARENT = "Parent";
    private static final String DEFAULT_SECTION = "General";
    private static final int MAX_FALLBACK_DEPTH = 8;

    private TwConfigEditorFieldPolicy() {
    }

    static boolean isAssetEditable(@Nullable TwConfigAssetDescriptor descriptor) {
        return descriptor != null && descriptor.knownType() && descriptor.editable();
    }

    @Nonnull
    static List<EditorFieldSpec> fieldsFor(@Nullable TwConfigAssetDescriptor descriptor,
                                           @Nullable TwConfigSnapshot snapshot,
                                           @Nullable JsonObject effectiveJson) {
        if (descriptor == null) {
            return List.of();
        }
        List<EditorFieldSpec> schemaFields = TwConfigSchemaAdapter.fieldsFor(descriptor);
        List<EditorFieldSpec> fallbackFields = buildFallbackFieldsFromJson(effectiveJson);
        if (schemaFields.isEmpty()) {
            return fallbackFields;
        }
        if (fallbackFields.isEmpty()) {
            return schemaFields;
        }
        return mergeSchemaAndFallbackFields(schemaFields, fallbackFields);
    }

    @Nonnull
    static List<String> optionsFor(@Nonnull EditorFieldSpec field,
                                   @Nonnull TwConfigAssetDescriptor descriptor,
                                   @Nullable TwConfigSnapshot snapshot) {
        if (field.parentSelector()) {
            return resolveParentOptions(descriptor, snapshot);
        }
        return field.options();
    }

    @Nullable
    static EditorFieldSpec findField(@Nonnull List<EditorFieldSpec> fields, @Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (EditorFieldSpec field : fields) {
            if (field.id().equalsIgnoreCase(id.trim())) {
                return field;
            }
        }
        return null;
    }

    @Nonnull
    private static List<String> resolveParentOptions(@Nonnull TwConfigAssetDescriptor descriptor,
                                                     @Nullable TwConfigSnapshot snapshot) {
        if (snapshot == null) {
            return List.of();
        }
        ArrayList<String> out = new ArrayList<>();
        for (TwConfigAssetDescriptor candidate : snapshot.getDescriptors()) {
            if (candidate == null || !candidate.knownType()) {
                continue;
            }
            if (!candidate.familyKey().equalsIgnoreCase(descriptor.familyKey())) {
                continue;
            }
            if (candidate.assetId().equalsIgnoreCase(descriptor.assetId())) {
                continue;
            }
            out.add(candidate.assetId());
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    @Nonnull
    private static List<EditorFieldSpec> buildFallbackFieldsFromJson(@Nullable JsonObject effectiveJson) {
        if (effectiveJson == null || effectiveJson.entrySet().isEmpty()) {
            return List.of();
        }
        ArrayList<EditorFieldSpec> out = new ArrayList<>();
        collectFallbackFields(effectiveJson, "", 0, out);
        boolean hasParent = out.stream().anyMatch(field -> FIELD_PARENT.equalsIgnoreCase(field.path()));
        if (!hasParent) {
            out.add(0, parentField(0));
        }
        return List.copyOf(out);
    }

    @Nonnull
    private static List<EditorFieldSpec> mergeSchemaAndFallbackFields(@Nonnull List<EditorFieldSpec> schemaFields,
                                                                      @Nonnull List<EditorFieldSpec> fallbackFields) {
        LinkedHashSet<String> fallbackPaths = new LinkedHashSet<>();
        for (EditorFieldSpec fallback : fallbackFields) {
            String path = normalizePath(fallback.path());
            if (!path.isBlank()) {
                fallbackPaths.add(path);
            }
        }

        LinkedHashMap<String, EditorFieldSpec> merged = new LinkedHashMap<>();
        for (EditorFieldSpec schema : schemaFields) {
            String path = normalizePath(schema.path());
            if (path.isBlank()) {
                continue;
            }
            if (shouldSuppressSchemaGroupRow(schema, path, fallbackPaths)) {
                continue;
            }
            merged.putIfAbsent(path, schema);
        }
        for (EditorFieldSpec fallback : fallbackFields) {
            String path = normalizePath(fallback.path());
            if (path.isBlank()) {
                continue;
            }
            merged.putIfAbsent(path, fallback);
        }

        ArrayList<EditorFieldSpec> ordered = new ArrayList<>();
        EditorFieldSpec parent = merged.remove(FIELD_PARENT.toLowerCase(Locale.ROOT));
        if (parent != null) {
            ordered.add(parent);
        }
        ordered.addAll(merged.values());
        return List.copyOf(ordered);
    }

    private static boolean shouldSuppressSchemaGroupRow(@Nonnull EditorFieldSpec schemaField,
                                                        @Nonnull String normalizedPath,
                                                        @Nonnull Set<String> fallbackPaths) {
        if (!schemaField.handoffOnly() && schemaField.type() != EditorFieldType.HANDOFF) {
            return false;
        }
        String childPrefix = normalizedPath + ".";
        for (String fallbackPath : fallbackPaths) {
            if (fallbackPath.startsWith(childPrefix)) {
                return true;
            }
        }
        return false;
    }

    @Nonnull
    private static String normalizePath(@Nullable String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static void collectFallbackFields(@Nonnull JsonObject object,
                                              @Nonnull String prefix,
                                              int depth,
                                              @Nonnull List<EditorFieldSpec> out) {
        if (depth > MAX_FALLBACK_DEPTH) {
            return;
        }
        for (String key : object.keySet()) {
            if (key == null || key.isBlank() || key.startsWith("$")) {
                continue;
            }
            String path = prefix.isBlank() ? key : (prefix + "." + key);
            JsonElement value = object.get(key);

            if (FIELD_PARENT.equalsIgnoreCase(path)) {
                out.add(parentField(depth));
                continue;
            }

            if (value == null || value.isJsonNull()) {
                out.add(new EditorFieldSpec(
                        path.toLowerCase(Locale.ROOT),
                        key,
                        path,
                        sectionForPath(path),
                        EditorFieldType.STRING,
                        true,
                        false,
                        false,
                        List.of(),
                        depth
                ));
                continue;
            }

            if (value.isJsonObject()) {
                JsonObject child = value.getAsJsonObject();
                if (child.entrySet().isEmpty()) {
                    out.add(new EditorFieldSpec(
                            path.toLowerCase(Locale.ROOT),
                            key,
                            path,
                            sectionForPath(path),
                            EditorFieldType.HANDOFF,
                            false,
                            true,
                            false,
                            List.of(),
                            depth
                    ));
                    continue;
                }
                collectFallbackFields(child, path, depth + 1, out);
                continue;
            }

            if (value.isJsonArray()) {
                JsonArray array = value.getAsJsonArray();
                EditorFieldType type = isStringArray(array)
                        ? EditorFieldType.STRING_LIST
                        : EditorFieldType.HANDOFF;
                boolean editable = type != EditorFieldType.HANDOFF;
                out.add(new EditorFieldSpec(
                        path.toLowerCase(Locale.ROOT),
                        key,
                        path,
                        sectionForPath(path),
                        type,
                        editable,
                        !editable,
                        false,
                        List.of(),
                        depth
                ));
                continue;
            }

            if (value.isJsonPrimitive()) {
                EditorFieldType type;
                if (value.getAsJsonPrimitive().isBoolean()) {
                    type = EditorFieldType.BOOLEAN;
                } else if (value.getAsJsonPrimitive().isNumber()) {
                    String raw = value.getAsJsonPrimitive().getAsString();
                    type = raw != null && raw.matches("^-?\\d+$")
                            ? EditorFieldType.INTEGER
                            : EditorFieldType.DOUBLE;
                } else {
                    type = EditorFieldType.STRING;
                }
                out.add(new EditorFieldSpec(
                        path.toLowerCase(Locale.ROOT),
                        key,
                        path,
                        sectionForPath(path),
                        type,
                        true,
                        false,
                        false,
                        List.of(),
                        depth
                ));
                continue;
            }

            out.add(new EditorFieldSpec(
                    path.toLowerCase(Locale.ROOT),
                    key,
                    path,
                    sectionForPath(path),
                    EditorFieldType.HANDOFF,
                    false,
                    true,
                    false,
                    List.of(),
                    depth
            ));
        }
    }

    private static boolean isStringArray(@Nonnull JsonArray array) {
        for (JsonElement element : array) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                return false;
            }
        }
        return true;
    }

    @Nonnull
    private static String sectionForPath(@Nonnull String path) {
        int dot = path.indexOf('.');
        return dot < 0 ? DEFAULT_SECTION : path.substring(0, dot);
    }

    enum EditorFieldType {
        STRING,
        INTEGER,
        DOUBLE,
        BOOLEAN,
        OPTION,
        STRING_LIST,
        HANDOFF
    }

    record EditorFieldSpec(
            @Nonnull String id,
            @Nonnull String label,
            @Nonnull String path,
            @Nonnull String section,
            @Nonnull EditorFieldType type,
            boolean editable,
            boolean handoffOnly,
            boolean parentSelector,
            @Nonnull List<String> options,
            int depth
    ) {
        boolean usesOptions() {
            return type == EditorFieldType.BOOLEAN
                    || type == EditorFieldType.OPTION
                    || parentSelector;
        }

        boolean usesTextInput() {
            return type == EditorFieldType.STRING
                    || type == EditorFieldType.INTEGER
                    || type == EditorFieldType.DOUBLE;
        }

        boolean usesTokenEditor() {
            return type == EditorFieldType.STRING_LIST;
        }

        boolean isEditableValue() {
            return editable && !handoffOnly && type != EditorFieldType.HANDOFF;
        }
    }

    @Nonnull
    static EditorFieldSpec parentField(int depth) {
        return new EditorFieldSpec(
                FIELD_PARENT.toLowerCase(Locale.ROOT),
                FIELD_PARENT,
                FIELD_PARENT,
                DEFAULT_SECTION,
                EditorFieldType.OPTION,
                true,
                false,
                true,
                List.of(),
                depth
        );
    }
}
