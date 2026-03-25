package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.config.overrides.TwConfigAssetDescriptor;
import com.alechilles.alecstamework.config.overrides.TwConfigFamily;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.codec.AssetCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.BooleanSchema;
import com.hypixel.hytale.codec.schema.config.IntegerSchema;
import com.hypixel.hytale.codec.schema.config.NumberSchema;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Builds editor field descriptors from Tw config codec schema metadata.
 */
final class TwConfigSchemaAdapter {
    private static final int MAX_RECURSION_DEPTH = 8;
    private static final ConcurrentHashMap<TwConfigFamily, List<TwConfigEditorFieldPolicy.EditorFieldSpec>> FIELD_CACHE =
            new ConcurrentHashMap<>();

    private TwConfigSchemaAdapter() {
    }

    @Nonnull
    static List<TwConfigEditorFieldPolicy.EditorFieldSpec> fieldsFor(@Nullable TwConfigAssetDescriptor descriptor) {
        if (descriptor == null || !descriptor.knownType() || descriptor.family() == TwConfigFamily.OTHER) {
            return List.of();
        }
        return FIELD_CACHE.computeIfAbsent(descriptor.family(), TwConfigSchemaAdapter::buildFieldsForFamily);
    }

    @Nonnull
    private static List<TwConfigEditorFieldPolicy.EditorFieldSpec> buildFieldsForFamily(@Nonnull TwConfigFamily family) {
        AssetStore<String, ?, ?> store = family.getAssetStore();
        if (store == null) {
            return List.of();
        }
        AssetCodec<?, ?> codec = store.getCodec();
        if (codec == null) {
            return List.of();
        }

        SchemaContext context = new SchemaContext();
        Schema schema;
        try {
            schema = codec.toSchema(context);
        } catch (Exception ignored) {
            return List.of();
        }
        if (schema == null) {
            return List.of();
        }

        LinkedHashMap<String, Schema> definitions = new LinkedHashMap<>();
        if (schema.getDefinitions() != null) {
            definitions.putAll(schema.getDefinitions());
        }
        if (context.getDefinitions() != null) {
            definitions.putAll(context.getDefinitions());
        }

        Schema resolvedRoot = resolveSchema(schema, definitions, new HashSet<>());
        if (!(resolvedRoot instanceof ObjectSchema rootObject)) {
            return List.of();
        }

        ArrayList<TwConfigEditorFieldPolicy.EditorFieldSpec> out = new ArrayList<>();
        collectObjectFields(
                rootObject,
                "",
                "",
                0,
                definitions,
                out
        );
        boolean hasParent = out.stream().anyMatch(field -> "Parent".equalsIgnoreCase(field.path()));
        if (!hasParent) {
            out.add(0, TwConfigEditorFieldPolicy.parentField(0));
        }
        return List.copyOf(out);
    }

    private static void collectObjectFields(@Nonnull ObjectSchema objectSchema,
                                            @Nonnull String pathPrefix,
                                            @Nonnull String section,
                                            int depth,
                                            @Nonnull Map<String, Schema> definitions,
                                            @Nonnull List<TwConfigEditorFieldPolicy.EditorFieldSpec> out) {
        if (depth > MAX_RECURSION_DEPTH) {
            return;
        }
        Map<String, Schema> properties = objectSchema.getProperties();
        if (properties == null || properties.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Schema> entry : properties.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            String path = pathPrefix.isBlank() ? key : (pathPrefix + "." + key);
            Schema resolved = resolveSchema(entry.getValue(), definitions, new HashSet<>());
            String label = resolveLabel(key, resolved);
            String currentSection = section;
            if (currentSection.isBlank() && !pathPrefix.isBlank()) {
                currentSection = firstPathSegment(pathPrefix);
            }
            if (currentSection.isBlank()) {
                currentSection = pathPrefix.isBlank() ? "General" : firstPathSegment(path);
            }

            if ("Parent".equalsIgnoreCase(path)) {
                out.add(TwConfigEditorFieldPolicy.parentField(depth));
                continue;
            }

            if (isStringEnumSchema(resolved)) {
                StringSchema stringSchema = (StringSchema) resolved;
                out.add(newField(
                        path,
                        label,
                        currentSection,
                        TwConfigEditorFieldPolicy.EditorFieldType.OPTION,
                        true,
                        false,
                        false,
                        Arrays.asList(stringSchema.getEnum()),
                        depth
                ));
                continue;
            }
            if (resolved instanceof BooleanSchema || hasSchemaType(resolved, "boolean")) {
                out.add(newField(
                        path,
                        label,
                        currentSection,
                        TwConfigEditorFieldPolicy.EditorFieldType.BOOLEAN,
                        true,
                        false,
                        false,
                        List.of("true", "false"),
                        depth
                ));
                continue;
            }
            if (resolved instanceof IntegerSchema || hasSchemaType(resolved, "integer")) {
                out.add(newField(
                        path,
                        label,
                        currentSection,
                        TwConfigEditorFieldPolicy.EditorFieldType.INTEGER,
                        true,
                        false,
                        false,
                        List.of(),
                        depth
                ));
                continue;
            }
            if (resolved instanceof NumberSchema || hasSchemaType(resolved, "number")) {
                out.add(newField(
                        path,
                        label,
                        currentSection,
                        TwConfigEditorFieldPolicy.EditorFieldType.DOUBLE,
                        true,
                        false,
                        false,
                        List.of(),
                        depth
                ));
                continue;
            }
            if (isSimpleStringSchema(resolved)) {
                out.add(newField(
                        path,
                        label,
                        currentSection,
                        TwConfigEditorFieldPolicy.EditorFieldType.STRING,
                        true,
                        false,
                        false,
                        List.of(),
                        depth
                ));
                continue;
            }
            if (isStringArraySchema(resolved, definitions)) {
                out.add(newField(
                        path,
                        label,
                        currentSection,
                        TwConfigEditorFieldPolicy.EditorFieldType.STRING_LIST,
                        true,
                        false,
                        false,
                        List.of(),
                        depth
                ));
                continue;
            }
            if (isSimpleObjectSchema(resolved, definitions)) {
                String nestedSection = pathPrefix.isBlank() ? label : currentSection;
                collectObjectFields(
                        (ObjectSchema) resolved,
                        path,
                        nestedSection,
                        depth + 1,
                        definitions,
                        out
                );
                continue;
            }

            out.add(newField(
                    path,
                    label,
                    currentSection,
                    TwConfigEditorFieldPolicy.EditorFieldType.HANDOFF,
                    false,
                    true,
                    false,
                    List.of(),
                    depth
            ));
        }
    }

    private static boolean isSimpleStringSchema(@Nullable Schema schema) {
        if (schema == null) {
            return false;
        }
        if (schema instanceof StringSchema stringSchema) {
            String[] values = stringSchema.getEnum();
            return values == null || values.length == 0;
        }
        return hasSchemaType(schema, "string");
    }

    private static boolean isStringEnumSchema(@Nullable Schema schema) {
        if (!(schema instanceof StringSchema stringSchema)) {
            return false;
        }
        String[] values = stringSchema.getEnum();
        return values != null && values.length > 0;
    }

    private static boolean isStringArraySchema(@Nullable Schema schema, @Nonnull Map<String, Schema> definitions) {
        if (!(schema instanceof ArraySchema arraySchema)) {
            return false;
        }
        if (hasCompositeSchema(arraySchema)) {
            return false;
        }
        Object items = arraySchema.getItems();
        if (!(items instanceof Schema itemSchema)) {
            return false;
        }
        Schema resolved = resolveSchema(itemSchema, definitions, new HashSet<>());
        return isSimpleStringSchema(resolved);
    }

    private static boolean isSimpleObjectSchema(@Nullable Schema schema, @Nonnull Map<String, Schema> definitions) {
        if (!(schema instanceof ObjectSchema objectSchema)) {
            return false;
        }
        if (hasCompositeSchema(objectSchema)) {
            return false;
        }
        Object additionalProperties = objectSchema.getAdditionalProperties();
        if (additionalProperties instanceof Boolean boolValue && boolValue) {
            return false;
        }
        if (additionalProperties instanceof Schema) {
            return false;
        }
        Map<String, Schema> properties = objectSchema.getProperties();
        if (properties == null || properties.isEmpty()) {
            return false;
        }

        for (Schema child : properties.values()) {
            Schema resolved = resolveSchema(child, definitions, new HashSet<>());
            if (isStringEnumSchema(resolved)
                    || isSimpleStringSchema(resolved)
                    || resolved instanceof BooleanSchema
                    || resolved instanceof IntegerSchema
                    || resolved instanceof NumberSchema
                    || hasSchemaType(resolved, "boolean")
                    || hasSchemaType(resolved, "integer")
                    || hasSchemaType(resolved, "number")
                    || isStringArraySchema(resolved, definitions)) {
                continue;
            }
            if (resolved instanceof ObjectSchema && isSimpleObjectSchema(resolved, definitions)) {
                continue;
            }
            return false;
        }
        return true;
    }

    private static boolean hasCompositeSchema(@Nullable Schema schema) {
        if (schema == null) {
            return false;
        }
        return hasEntries(schema.getAnyOf()) || hasEntries(schema.getOneOf()) || hasEntries(schema.getAllOf());
    }

    private static boolean hasEntries(@Nullable Schema[] entries) {
        return entries != null && entries.length > 0;
    }

    @Nullable
    private static Schema resolveSchema(@Nullable Schema schema,
                                        @Nonnull Map<String, Schema> definitions,
                                        @Nonnull Set<String> visitedRefs) {
        if (schema == null) {
            return null;
        }
        String ref = schema.getRef();
        if (ref == null || ref.isBlank()) {
            return schema;
        }
        String key = normalizeRef(ref);
        if (key.isBlank() || visitedRefs.contains(key)) {
            return schema;
        }
        visitedRefs.add(key);
        Schema referenced = definitions.get(key);
        if (referenced == null) {
            return schema;
        }
        return resolveSchema(referenced, definitions, visitedRefs);
    }

    @Nonnull
    private static String normalizeRef(@Nonnull String ref) {
        String value = ref.trim();
        if (value.startsWith("#/definitions/")) {
            return value.substring("#/definitions/".length());
        }
        if (value.startsWith("#")) {
            return value.substring(1);
        }
        return value;
    }

    private static boolean hasSchemaType(@Nullable Schema schema, @Nonnull String expectedType) {
        if (schema == null || schema.getTypes() == null) {
            return false;
        }
        for (String type : schema.getTypes()) {
            if (expectedType.equalsIgnoreCase(type)) {
                return true;
            }
        }
        return false;
    }

    @Nonnull
    private static TwConfigEditorFieldPolicy.EditorFieldSpec newField(@Nonnull String path,
                                                                      @Nonnull String label,
                                                                      @Nonnull String section,
                                                                      @Nonnull TwConfigEditorFieldPolicy.EditorFieldType type,
                                                                      boolean editable,
                                                                      boolean handoffOnly,
                                                                      boolean parentSelector,
                                                                      @Nonnull List<String> options,
                                                                      int depth) {
        return new TwConfigEditorFieldPolicy.EditorFieldSpec(
                path.toLowerCase(Locale.ROOT),
                label,
                path,
                section,
                type,
                editable,
                handoffOnly,
                parentSelector,
                List.copyOf(options),
                depth
        );
    }

    @Nonnull
    private static String resolveLabel(@Nonnull String key, @Nullable Schema schema) {
        String title = schema == null ? null : schema.getTitle();
        if (title != null && !title.isBlank()) {
            return title;
        }
        String spaced = key
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replace('_', ' ')
                .replace('-', ' ')
                .trim();
        if (spaced.isBlank()) {
            return key;
        }
        if (spaced.length() == 1) {
            return spaced.toUpperCase(Locale.ROOT);
        }
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    @Nonnull
    private static String firstPathSegment(@Nonnull String path) {
        int dotIndex = path.indexOf('.');
        if (dotIndex < 0) {
            return path;
        }
        return path.substring(0, dotIndex);
    }
}
