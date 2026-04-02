package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.config.overrides.TwConfigAssetDescriptor;
import com.alechilles.alecstamework.config.overrides.TwConfigFamily;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.config.assets.TwDebugConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwNameItemConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.config.assets.TwSpawnerConfig;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
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
import java.lang.reflect.Method;
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
        return FIELD_CACHE.compute(descriptor.family(), (family, existing) -> {
            if (existing != null && !existing.isEmpty()) {
                return existing;
            }
            return buildFieldsForFamily(family);
        });
    }

    @Nonnull
    private static List<TwConfigEditorFieldPolicy.EditorFieldSpec> buildFieldsForFamily(@Nonnull TwConfigFamily family) {
        AssetCodec<?, ?> codec = resolveCodecForFamily(family);
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
            indexDefinitions(definitions, schema.getDefinitions());
        }
        if (context.getDefinitions() != null) {
            indexDefinitions(definitions, context.getDefinitions());
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

    private static void indexDefinitions(@Nonnull Map<String, Schema> out, @Nonnull Map<String, Schema> source) {
        for (Map.Entry<String, Schema> entry : source.entrySet()) {
            String key = entry.getKey();
            Schema schema = entry.getValue();
            if (schema == null || key == null || key.isBlank()) {
                continue;
            }
            String trimmed = key.trim();
            out.putIfAbsent(trimmed, schema);
            String normalized = normalizeRef(trimmed);
            if (!normalized.isBlank()) {
                out.putIfAbsent(normalized, schema);
            }
        }
    }

    @Nullable
    private static AssetCodec<?, ?> resolveCodecForFamily(@Nonnull TwConfigFamily family) {
        AssetStore<String, ?, ?> store = family.getAssetStore();
        if (store != null && store.getCodec() != null) {
            return store.getCodec();
        }
        return switch (family) {
            case GLOBAL -> TwGlobalConfig.CODEC;
            case COMPANION -> TwCompanionConfig.CODEC;
            case INTERACTION -> TwInteractionConfig.CODEC;
            case SPAWNER -> TwSpawnerConfig.CODEC;
            case NAME_ITEM -> TwNameItemConfig.CODEC;
            case COMMAND_ITEM -> TwCommandItemConfig.CODEC;
            case HAPPINESS -> TwHappinessConfig.CODEC;
            case NEEDS -> TwNeedsConfig.CODEC;
            case BREEDING -> TwBreedingConfig.CODEC;
            case TRAIT -> TwTraitConfig.CODEC;
            case COOP -> TwCoopConfig.CODEC;
            case DEBUG -> TwDebugConfig.CODEC;
            case OTHER -> null;
        };
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
            if (isInternalEditorMetadataKey(key)) {
                continue;
            }
            String path = pathPrefix.isBlank() ? key : (pathPrefix + "." + key);
            Schema raw = entry.getValue();
            Schema resolved = unwrapCompositeSchema(
                    resolveSchema(entry.getValue(), definitions, new HashSet<>()),
                    definitions,
                    new HashSet<>()
            );
            String label = resolveLabel(key, resolved);
            String tooltip = resolveTooltip(raw);
            if (tooltip.isBlank()) {
                tooltip = resolveTooltip(resolved);
            }
            String currentSection = section;
            if (currentSection.isBlank() && !pathPrefix.isBlank()) {
                currentSection = firstPathSegment(pathPrefix);
            }
            if (currentSection.isBlank()) {
                currentSection = pathPrefix.isBlank() ? "General" : firstPathSegment(path);
            }

            if ("Parent".equalsIgnoreCase(path)) {
                out.add(newParentField(path, label, tooltip, depth));
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
                        tooltip,
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
                        tooltip,
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
                        tooltip,
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
                        tooltip,
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
                        tooltip,
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
                        tooltip,
                        depth
                ));
                continue;
            }
            if (resolved instanceof ObjectSchema resolvedObjectSchema) {
                Map<String, Schema> nestedProperties = resolvedObjectSchema.getProperties();
                if (nestedProperties != null && !nestedProperties.isEmpty()) {
                    int before = out.size();
                    String nestedSection = pathPrefix.isBlank() ? label : currentSection;
                    collectObjectFields(
                            resolvedObjectSchema,
                            path,
                            nestedSection,
                            depth + 1,
                            definitions,
                            out
                    );
                    if (out.size() > before) {
                        continue;
                    }
                }
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
                    tooltip,
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
    private static Schema unwrapCompositeSchema(@Nullable Schema schema,
                                                @Nonnull Map<String, Schema> definitions,
                                                @Nonnull Set<Schema> visited) {
        if (schema == null || !visited.add(schema)) {
            return schema;
        }
        Schema resolved = resolveSchema(schema, definitions, new HashSet<>());
        if (resolved == null) {
            return resolved;
        }
        if (resolved != schema && !visited.add(resolved)) {
            return resolved;
        }

        Schema unwrapped = unwrapFromEntries(resolved.getAnyOf(), definitions, visited);
        if (unwrapped != null) {
            return unwrapped;
        }
        unwrapped = unwrapFromEntries(resolved.getOneOf(), definitions, visited);
        if (unwrapped != null) {
            return unwrapped;
        }
        unwrapped = unwrapFromEntries(resolved.getAllOf(), definitions, visited);
        if (unwrapped != null) {
            return unwrapped;
        }
        return resolved;
    }

    @Nullable
    private static Schema unwrapFromEntries(@Nullable Schema[] entries,
                                            @Nonnull Map<String, Schema> definitions,
                                            @Nonnull Set<Schema> visited) {
        if (entries == null || entries.length == 0) {
            return null;
        }
        ArrayList<Schema> candidates = new ArrayList<>();
        for (Schema entry : entries) {
            Schema resolved = resolveSchema(entry, definitions, new HashSet<>());
            if (resolved == null || isNullTypeSchema(resolved)) {
                continue;
            }
            candidates.add(resolved);
        }
        if (candidates.size() != 1) {
            return null;
        }
        return unwrapCompositeSchema(candidates.get(0), definitions, visited);
    }

    private static boolean isNullTypeSchema(@Nullable Schema schema) {
        if (schema == null) {
            return true;
        }
        if ("NullSchema".equals(schema.getClass().getSimpleName())) {
            return true;
        }
        return hasSchemaType(schema, "null");
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
        int definitionIndex = value.indexOf("#/definitions/");
        if (definitionIndex >= 0) {
            return value.substring(definitionIndex + "#/definitions/".length());
        }
        if (value.startsWith("#/definitions/")) {
            return value.substring("#/definitions/".length());
        }
        if (value.startsWith("#")) {
            return value.substring(1);
        }
        int hashIndex = value.indexOf('#');
        if (hashIndex >= 0 && hashIndex + 1 < value.length()) {
            String fragment = value.substring(hashIndex + 1);
            if (fragment.startsWith("/definitions/")) {
                return fragment.substring("/definitions/".length());
            }
            if (fragment.startsWith("/")) {
                return fragment.substring(1);
            }
            return fragment;
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
                                                                       @Nonnull String tooltip,
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
                tooltip,
                depth
        );
    }

    @Nonnull
    private static TwConfigEditorFieldPolicy.EditorFieldSpec newParentField(@Nonnull String path,
                                                                             @Nonnull String label,
                                                                             @Nonnull String tooltip,
                                                                             int depth) {
        return new TwConfigEditorFieldPolicy.EditorFieldSpec(
                path.toLowerCase(Locale.ROOT),
                label,
                path,
                "General",
                TwConfigEditorFieldPolicy.EditorFieldType.OPTION,
                true,
                false,
                true,
                List.of(),
                tooltip,
                depth
        );
    }

    @Nonnull
    private static String resolveTooltip(@Nullable Schema schema) {
        if (schema == null) {
            return "";
        }
        for (String methodName : List.of(
                "getTooltip",
                "getDescription",
                "getMarkdownDescription",
                "getDocumentation",
                "getComment"
        )) {
            String value = invokeStringGetter(schema, methodName);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    @Nullable
    private static String invokeStringGetter(@Nonnull Object target, @Nonnull String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            if (value instanceof String text) {
                return text;
            }
        } catch (Exception ignored) {
        }
        return null;
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

    private static boolean isInternalEditorMetadataKey(@Nonnull String key) {
        return key.startsWith("$");
    }
}
